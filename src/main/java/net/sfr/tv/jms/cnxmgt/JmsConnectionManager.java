/**
 * Copyright 2012,2013 - SFR (http://www.sfr.com/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package net.sfr.tv.jms.cnxmgt;

import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Session;
import javax.naming.Context;
import net.sfr.tv.jms.context.JmsContext;
import net.sfr.tv.jms.context.JmsSubscriptionContext;
import net.sfr.tv.messaging.api.ConnectionManager;
import net.sfr.tv.messaging.impl.MessagingServerDescriptor;
import net.sfr.tv.messaging.impl.AbstractConnectionManager;
import net.sfr.tv.model.Credentials;
import org.apache.log4j.Logger;

/**
 * Stateful management of JMS connection, handling failover & reconnections
 * 
 * @author matthieu.chaplin@sfr.com
 */
public abstract class JmsConnectionManager extends AbstractConnectionManager implements ConnectionManager, ExceptionListener {
    
    private static final Logger logger = Logger.getLogger(JmsConnectionManager.class);
    
    /** JMS Connection Factory JNDI name */
    private final String cnxFactoryJndiName;
    
    /** JMS ClientID */
    private final String clientId;

    /** Currently used JMS resources */
    protected JmsContext context;
    
    /** Current JNDI context */
    protected Context jndiContext;
    
    public JmsConnectionManager(final String name, final Set<MessagingServerDescriptor> servers, final String preferredServer, final String clientId, final String cnxFactoryJndiName, final Credentials credentials) {
        super(name, credentials, servers, preferredServer);
        this.clientId = clientId;
        this.cnxFactoryJndiName = cnxFactoryJndiName;
        
        lookup(activeServer, 2);
        
        logger.info(getName().concat(" : Service provider URL : ").concat(activeServer.getProviderUrl()));
    }
    
    public final void lookup(MessagingServerDescriptor jndiServer, long delay) {
        ScheduledFuture<Context> futureContext = null;
        JndiLookupTask jlt;
        boolean initConnect = true;
        try {
            while (futureContext == null || (jndiContext = futureContext.get()) == null) {
                // reschedule a task
                jlt = new JndiLookupTask(jndiServer);
                futureContext = scheduler.schedule(jlt, initConnect ? 0 : delay, TimeUnit.SECONDS);
                initConnect = false;
            }
            
        } catch (InterruptedException ex) {
            logger.error(getName().concat(" : ").concat(ex.getMessage()).concat(" : Caused by : ").concat(ex.getCause().getMessage()));
        } catch (ExecutionException ex) {
            logger.error(getName().concat(" : ").concat(ex.getMessage()).concat(" : Caused by : ").concat(ex.getCause().getMessage()));
        }
    }
    
    /**
     * Establish a JMS connection and session.
     * 
     * @param delay     Periodic attempts delay.
     */
    public final void connect(long delay) {
        ScheduledFuture<JmsContext> futureContext = null;
        ConnectTask ct;
        boolean initConnect = true;
        try {
            while (futureContext == null || (context = futureContext.get()) == null) {
                // reschedule a task
                ct = new ConnectTask(jndiContext, clientId, cnxFactoryJndiName, credentials, this);
                futureContext = scheduler.schedule(ct, initConnect ? 0 : delay, TimeUnit.SECONDS);
                initConnect = false;
            }
            
        } catch (InterruptedException ex) {
            logger.error(getName().concat(" : ").concat(ex.getMessage()).concat(" : Caused by : ").concat(ex.getCause().getMessage()));
        } catch (ExecutionException ex) {
            logger.error(getName().concat(" : ").concat(ex.getMessage()).concat(" : Caused by : ").concat(ex.getCause().getMessage()));
        }
    }
    
    /**
     * Starts message delivery for subscriptions associated to a connection.
     * 
     * @throws JMSException 
     */
    public final void start() throws JMSException {
        context.getConnection().start();
    }
    
    /**
     * Release a JMS subscription
     * 
     * @param consumer
     * @param session
     * @param subscriptionName 
     */
    public final void unsubscribe(JmsSubscriptionContext subscription, Session session) {

        if (logger.isDebugEnabled()) {
            logger.debug(getName().concat(" : About to unsubscribe : ").concat(subscription.getSubscriptionName()));
        }
        
        // CLOSE CONSUMTER
        if (subscription.getConsumer() != null) {
            try {
                subscription.getConsumer().close();
            } catch (JMSException ex) {
                logger.warn(ex.getMessage());
            }

        }
        
        // UNSUBSCRIBE
        if (session != null && subscription.getDescriptor().isIsTopicSubscription() && !subscription.getDescriptor().isIsDurableSubscription()) {
            // Unsubscribe, to prevent leaving a potential 'shadow' queue & permit reusing the same clientId later on.
            try {
                ((Session) session).unsubscribe(subscription.getSubscriptionName());
                logger.info(getName().concat(" : Unsubscribed : ").concat(subscription.getSubscriptionName()));
            } catch (JMSException ex) {
                logger.error(getName().concat(ex.getMessage()).concat(" : Caused by : ").concat(ex.getCause().getMessage()));
            }
        }
    }
    
    /**
     * Release a connection, terminating associated resources :
     * <ul>
     *  <li> Subscriptions
     *  <li> Session
     * </ul>
     */
    public void disconnect() {
      
        logger.info(getName().concat(" : Disconnecting.."));
        
        // TERMINATE SESSION
        if (context.getSession() != null) {
            try {
                ((Session) context.getSession()).close();
            } catch (JMSException ex) {
                logger.warn(ex.getMessage());
            }
        }
        
        // CLOSE CONNECTION
        if (context.getConnection() != null) {
            try {
                context.getConnection().stop();
                context.getConnection().close();
            } catch (JMSException ex) {
                logger.warn(ex.getMessage());
            }
        }
    }

    @Override
    public void onException(JMSException jmse) {
        
        logger.error(getName().concat(" : onException : ").concat(jmse.getMessage()));
        
        if (jmse.getMessage().toUpperCase().indexOf("DISCONNECTED") != -1) {

            // BLACKLIST ACTIVE SERVER
            logger.error(getName().concat(" : Active Server not available anymore ! ").concat(activeServer.getProviderUrl()));
            if (availableServers.size() > 1) {
                for (MessagingServerDescriptor srv : availableServers) {
                    if (!srv.equals(activeServer)) {
                        activeServer = srv;
                        break;
                    }
                }            
            }

            // LOOKUP NEW JNDI CONTEXT
            lookup(activeServer, 2);
            logger.info(getName().concat(" : JNDI service provider URL : ").concat(activeServer.getProviderUrl()));

            // CONNECT TO NEW ACTIVE SERVER WITH A 2 SECONDS PERIOD.
            connect(2);   
        }
    }
    
    public final String getName() {
        return name;
    }

    public final String getJndiConnectionFactory() {
        return cnxFactoryJndiName;
    }

    public final Context getJndiContext() {
        return jndiContext;
    }

    public final JmsContext getJmsContext() {
        return context;
    }

    public final String getClientId() {
        return clientId;
    }
}