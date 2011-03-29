/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jbpm.ejb.impl;

import java.io.Serializable;

import javax.ejb.CreateException;
import javax.ejb.MessageDrivenBean;
import javax.ejb.MessageDrivenContext;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.jbpm.JbpmException;
import org.jbpm.command.Command;
import org.jbpm.ejb.LocalCommandService;
import org.jbpm.ejb.LocalCommandServiceHome;
import org.jbpm.persistence.db.DbPersistenceService;
import org.jbpm.persistence.db.StaleObjectLogConfigurer;

/**
 * This message-driven bean listens for {@link ObjectMessage object messages} containing a
 * command instance. The received commands are executed by the {@link CommandServiceBean command
 * service} bean, using the local interface.
 * 
 * The body of the message must be a Java object that implements the {@link Command} interface.
 * The message properties, if any, are ignored.
 * 
 * <h3>Environment</h3>
 * 
 * <p>
 * The environment entries and resources available for customization are summarized in the table
 * below.
 * </p>
 * 
 * <table border="1">
 * <tr>
 * <th>Name</th>
 * <th>Type</th>
 * <th>Description</th>
 * </tr>
 * <tr>
 * <td><code>ejb/LocalCommandServiceBean</code></td>
 * <td>EJB Reference</td>
 * <td>Link to the local {@linkplain CommandServiceBean session bean} that executes commands on
 * a separate jBPM context.</td>
 * </tr>
 * <tr>
 * <td><code>jms/JbpmConnectionFactory</code></td>
 * <td>Resource Manager Reference</td>
 * <td>Logical name of the factory that provides JMS connections for producing result messages.
 * Required for command messages that indicate a reply destination.</td>
 * </tr>
 * </table>
 * 
 * @author Jim Rigsbee
 * @author Alejandro Guizar
 * @author Tom Baeyens
 */
public class CommandListenerBean implements MessageDrivenBean, MessageListener {

  private static final long serialVersionUID = 1L;

  private MessageDrivenContext messageDrivenContext;
  private LocalCommandService commandService;
  private ConnectionFactory jmsConnectionFactory;

  private static final Log log = LogFactory.getLog(CommandListenerBean.class);

  public void onMessage(Message message) {
    try {
      // extract command from message
      Command command = extractCommand(message);
      if (command == null) return;

      // execute command via local command executor bean
      Object result;
      try {
        result = commandService.execute(command);
      }
      catch (RuntimeException e) {
        // if this is a locking exception, keep it quiet
        if (DbPersistenceService.isLockingException(e)) {
          StaleObjectLogConfigurer.getStaleObjectExceptionsLog().error("failed to execute "
            + command, e);
        }
        else {
          log.error("failed to execute " + command, e);
        }
        // MDBs are not supposed to throw exceptions
        messageDrivenContext.setRollbackOnly();
        return;
      }

      // send the result back if a "reply-to" destination is set
      Destination replyTo;
      if (jmsConnectionFactory != null
        && (replyTo = message.getJMSReplyTo()) != null
        && (result instanceof Serializable || result == null)) {
        sendResult((Serializable) result, replyTo, message.getJMSMessageID());
      }
    }
    catch (JMSException e) {
      messageDrivenContext.setRollbackOnly();
      log.error("failed to process message " + message, e);
    }
  }

  protected Command extractCommand(Message message) throws JMSException {
    if (message instanceof ObjectMessage) {
      ObjectMessage objectMessage = (ObjectMessage) message;
      Serializable object = objectMessage.getObject();
      if (object instanceof Command) {
        return (Command) object;
      }
      else {
        log.warn("not a command: " + object);
      }
    }
    else {
      log.warn("not an object message: " + message);
    }
    return null;
  }

  private void sendResult(Serializable result, Destination destination, String correlationId)
    throws JMSException {
    if (log.isDebugEnabled()) log.debug("sending " + result + " to " + destination);
    Connection jmsConnection = jmsConnectionFactory.createConnection();
    try {
      /*
       * if the connection supports xa, the session will be transacted, else the session will
       * auto acknowledge; in either case no explicit transaction control must be performed -
       * see ejb 2.1 - 17.3.5
       */
      Session jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
      Message resultMessage = jmsSession.createObjectMessage(result);
      resultMessage.setJMSCorrelationID(correlationId);
      jmsSession.createProducer(destination).send(resultMessage);
    }
    finally {
      jmsConnection.close();
    }
  }

  public void setMessageDrivenContext(MessageDrivenContext messageDrivenContext) {
    this.messageDrivenContext = messageDrivenContext;
  }

  public void ejbRemove() {
    jmsConnectionFactory = null;
    commandService = null;
    messageDrivenContext = null;
  }

  public void ejbCreate() {
    try {
      Context jndiContext = new InitialContext();
      // create command service
      LocalCommandServiceHome commandServiceHome = (LocalCommandServiceHome) jndiContext.lookup("java:comp/env/ejb/LocalCommandServiceBean");
      commandService = commandServiceHome.create();
      // look for optional connection factory
      try {
        jmsConnectionFactory = (ConnectionFactory) jndiContext.lookup("java:comp/env/jms/JbpmConnectionFactory");
      }
      catch (NamingException e) {
        log.warn("error retrieving connection factory, results will not be sent back", e);
      }
      jndiContext.close();
    }
    catch (NamingException e) {
      throw new JbpmException("error retrieving command service home", e);
    }
    catch (CreateException e) {
      throw new JbpmException("failed to create command service", e);
    }
  }
}
