// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2013-2020 VMware, Inc. or its affiliates. All rights reserved.
package com.rabbitmq.jms.client;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

import javax.jms.BytesMessage;
import javax.jms.Destination;
import javax.jms.IllegalStateException;
import javax.jms.InvalidSelectorException;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.QueueReceiver;
import javax.jms.QueueSender;
import javax.jms.QueueSession;
import javax.jms.Session;
import javax.jms.StreamMessage;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.jms.TopicPublisher;
import javax.jms.TopicSession;
import javax.jms.TopicSubscriber;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.jms.util.WhiteListObjectInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.jms.admin.RMQDestination;
import com.rabbitmq.jms.client.message.RMQBytesMessage;
import com.rabbitmq.jms.client.message.RMQMapMessage;
import com.rabbitmq.jms.client.message.RMQObjectMessage;
import com.rabbitmq.jms.client.message.RMQStreamMessage;
import com.rabbitmq.jms.client.message.RMQTextMessage;
import com.rabbitmq.jms.parse.sql.SqlCompiler;
import com.rabbitmq.jms.parse.sql.SqlEvaluator;
import com.rabbitmq.jms.parse.sql.SqlExpressionType;
import com.rabbitmq.jms.parse.sql.SqlParser;
import com.rabbitmq.jms.parse.sql.SqlTokenStream;
import com.rabbitmq.jms.util.RMQJMSException;
import com.rabbitmq.jms.util.RMQJMSSelectorException;
import com.rabbitmq.jms.util.Util;
/**
 * RabbitMQ implementation of JMS {@link Session}
 */
public class RMQSession implements Session, QueueSession, TopicSession {

    private final Logger logger = LoggerFactory.getLogger(RMQSession.class);

    /** The connection that created this session */
    private final RMQConnection connection;
    /** Set to true if this session is transacted. */
    private final boolean transacted;

    /** This value must be the maximum allowed, and contiguous with valid values for {@link #acknowledgeMode}. */
    public static final int CLIENT_INDIVIDUAL_ACKNOWLEDGE = 4; // mode in {0, 1, 2, 3, 4} is valid
    /** The ack mode used when receiving message */
    private final int acknowledgeMode;
    /** Flag to remember if session is {@link #CLIENT_INDIVIDUAL_ACKNOWLEDGE} */
    private final boolean isIndividualAck;

    /**
     * Whether {@link MessageProducer} properties (delivery mode,
     * priority, TTL) take precedence over respective {@link Message}
     * properties or not.
     * Default is true.
     */
    private final boolean preferProducerMessageProperty;

    /**
     * Whether requeue message on {@link RuntimeException} in the
     * {@link javax.jms.MessageListener} or not.
     * Default is false.
     */
    private final boolean requeueOnMessageListenerException;

    /**
     * Whether to commit nack on rollback or not.
     * Default is false.
     *
     * @since 1.14.0
     */
    private final boolean nackOnRollback;

    /**
     * Whether using auto-delete for server-named queues for non-durable topics.
     * If set to true, those queues will be deleted when the session is closed.
     * If set to false, queues will be deleted when the owning connection is closed.
     * Default is false.
     * @since 1.8.0
     */
    private final boolean cleanUpServerNamedQueuesForNonDurableTopics;

    /**
     * Callback to customise properties of outbound AMQP messages.
     *
     * @since 1.9.0
     */
    private final BiFunction<AMQP.BasicProperties.Builder, Message, AMQP.BasicProperties.Builder> amqpPropertiesCustomiser;

    /**
     * Callback before sending a message.
     *
     * @since 1.11.0
     */
    private final SendingContextConsumer sendingContextConsumer;

    private final ReceivingContextConsumer receivingContextConsumer;

    private final PublishingListener publishingListener;

    /** The main RabbitMQ channel we use under the hood */
    private final Channel channel;
    /** Set to true if close() has been called and completed */
    private volatile boolean closed = false;
    /** The message listener for this session. */
    private volatile MessageListener messageListener;
    /** A list of all the producers created by this session.
     * When a producer is closed, it will be removed from this list */
    private final ArrayList<RMQMessageProducer> producers = new ArrayList<RMQMessageProducer>();
    /** A list of all the consumers created by this session.
     * When a consumer is closed, it will be removed from this list */
    private final ArrayList<RMQMessageConsumer> consumers = new ArrayList<RMQMessageConsumer>();
    /** We keep an ordered set of the message tags (acknowledgement tags) for all messages received and unacknowledged.
     * Each message acknowledgement must ACK all (unacknowledged) messages received up to this point, and
     * we must never acknowledge a message more than once (nor acknowledge a message that doesn't exist). */
    private final SortedSet<Long> unackedMessageTags = Collections.synchronizedSortedSet(new TreeSet<Long>());

    /* Holds the uncommited tags to commit a nack on rollback */
    private final List<Long> uncommittedMessageTags = new ArrayList<Long>(); // GuardedBy("commitLock");

    /** List of all our durable subscriptions so we can track them */
    private final Map<String, RMQMessageConsumer> subscriptions;

    /** Lock for waiting for close */
    private final Object closeLock = new Object();

    /** Lock and parms for commit and rollback blocking of other commands */
    private final Object commitLock = new Object();
    private static final long COMMIT_WAIT_MAX = 2000L; // 2 seconds
    private boolean committing = false; // GuardedBy("commitLock");

    /** Client version obtained from compiled class. */
    private static final GenericVersion CLIENT_VERSION = new GenericVersion(RMQSession.class.getPackage().getImplementationVersion());

    private static final String RJMS_CLIENT_VERSION = CLIENT_VERSION.toString();
    static {
        if (RJMS_CLIENT_VERSION.equals("0.0.0"))
            System.out.println("WARNING: Running test version of RJMS Client with no version information.");
    }

    /** Selector exchange for topic selection */
    private volatile String durableTopicSelectorExchange;
    /** Selector exchange for topic selection */
    private volatile String nonDurableTopicSelectorExchange;
    /** Selector exchange arg key for erlang selector expression */
    private static final String RJMS_COMPILED_SELECTOR_ARG = "rjms_erlang_selector";
    /** Selector exchange arg key for client version */
    private static final String RJMS_VERSION_ARG = "rjms_version";
    /** Selector exchange arguments */
    private static final Map<String, Object> RJMS_SELECTOR_EXCHANGE_ARGS
        = Collections.singletonMap(RJMS_VERSION_ARG, (Object)RJMS_CLIENT_VERSION);

    private static Map<String, SqlExpressionType> generateJMSTypeIdents() {
        Map<String, SqlExpressionType> map = new HashMap<String, SqlExpressionType>(6);  // six elements only
        map.put("JMSDeliveryMode",  SqlExpressionType.STRING);
        map.put("JMSPriority",      SqlExpressionType.ARITH );
        map.put("JMSMessageID",     SqlExpressionType.STRING);
        map.put("JMSTimestamp",     SqlExpressionType.ARITH );
        map.put("JMSCorrelationID", SqlExpressionType.STRING);
        map.put("JMSType",          SqlExpressionType.STRING);
        return Collections.unmodifiableMap(map);
    }
    static final Map<String, SqlExpressionType> JMS_TYPE_IDENTS = generateJMSTypeIdents();

    private static final String JMS_TOPIC_SELECTOR_EXCHANGE_TYPE = "x-jms-topic";

    private final DeliveryExecutor deliveryExecutor;

    /** The channels we use for browsing queues (there may be more than one in operation at a time) */
    private Set<Channel> browsingChannels = new HashSet<Channel>(); // @GuardedBy(bcLock)
    private final Object bcLock = new Object();

    /**
     * Classes in these packages can be transferred via ObjectMessage.
     *
     * @see WhiteListObjectInputStream
     */
    private final List<String> trustedPackages;

    /**
     * Arguments to be used when declaring a queue while creating a producer
     *
     * @since 1.14.0
     */
    private Map<String, Object> queueDeclareArguments = null;

    /**
     * Creates a session object associated with a connection
     * @param sessionParams parameters for this session
     * @throws JMSException if we fail to create a {@link Channel} object on the connection, or if the acknowledgement mode is incorrect
     */
    public RMQSession(SessionParams sessionParams) throws JMSException {
        if (sessionParams.getMode() < 0 || sessionParams.getMode() > CLIENT_INDIVIDUAL_ACKNOWLEDGE) {
            throw new JMSException(String.format("cannot create session with acknowledgement mode = %d.", sessionParams.getMode()));
        }
        this.connection = sessionParams.getConnection();
        this.transacted = sessionParams.isTransacted();
        this.subscriptions = sessionParams.getSubscriptions();
        this.deliveryExecutor = new DeliveryExecutor(sessionParams.getOnMessageTimeoutMs());
        this.preferProducerMessageProperty = sessionParams.willPreferProducerMessageProperty();
        this.requeueOnMessageListenerException = sessionParams.willRequeueOnMessageListenerException();
        this.nackOnRollback = sessionParams.willNackOnRollback();
        this.cleanUpServerNamedQueuesForNonDurableTopics = sessionParams.isCleanUpServerNamedQueuesForNonDurableTopics();
        this.amqpPropertiesCustomiser = sessionParams.getAmqpPropertiesCustomiser();
        this.sendingContextConsumer = sessionParams.getSendingContextConsumer();
        this.receivingContextConsumer = sessionParams.getReceivingContextConsumer() == null ?
            ReceivingContextConsumer.NO_OP : sessionParams.getReceivingContextConsumer();
        this.trustedPackages = sessionParams.getTrustedPackages();

        if (transacted) {
            this.acknowledgeMode = Session.SESSION_TRANSACTED;
            this.isIndividualAck = false;
        } else if (sessionParams.getMode() == CLIENT_INDIVIDUAL_ACKNOWLEDGE) {
            this.acknowledgeMode = Session.CLIENT_ACKNOWLEDGE;
            this.isIndividualAck = true;
        } else {
            this.acknowledgeMode = sessionParams.getMode();
            this.isIndividualAck = false;
        }
        try {
            this.channel = connection.createRabbitChannel(transacted);
            if (sessionParams.getConfirmListener() != null) {
                this.publishingListener = PublisherConfirmsUtils.configurePublisherConfirmsSupport(
                        this.channel, sessionParams.getConfirmListener()
                );
            } else {
                this.publishingListener = null;
            }
        } catch (Exception x) { // includes unchecked exceptions, e.g. ShutdownSignalException
            throw new RMQJMSException(x);
        }
    }

    /**
     * Creates a session object associated with a connection
     * @param connection the connection that we will send data on
     * @param transacted whether this session is transacted or not
     * @param onMessageTimeoutMs how long to wait for onMessage to return, in milliseconds
     * @param mode the (fixed) acknowledgement mode for this session
     * @param subscriptions the connection's subscriptions, shared with all sessions
     * @throws JMSException if we fail to create a {@link Channel} object on the connection, or if the acknowledgement mode is incorrect
     */
    public RMQSession(RMQConnection connection, boolean transacted, int onMessageTimeoutMs, int mode, Map<String, RMQMessageConsumer> subscriptions) throws JMSException {
        this(new SessionParams()
            .setConnection(connection)
            .setTransacted(transacted)
            .setOnMessageTimeoutMs(onMessageTimeoutMs)
            .setMode(mode)
            .setSubscriptions(subscriptions)
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BytesMessage createBytesMessage() throws JMSException {
        illegalStateExceptionIfClosed();
        return new RMQBytesMessage();
    }

    private void illegalStateExceptionIfClosed() throws IllegalStateException {
        if (this.closed) throw new IllegalStateException("Session is closed");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MapMessage createMapMessage() throws JMSException {
        illegalStateExceptionIfClosed();
        return new RMQMapMessage();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Message createMessage() throws JMSException {
        illegalStateExceptionIfClosed();
        return createTextMessage();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectMessage createObjectMessage() throws JMSException {
        illegalStateExceptionIfClosed();
        return new RMQObjectMessage();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectMessage createObjectMessage(Serializable object) throws JMSException {
        illegalStateExceptionIfClosed();
        ObjectMessage message = createObjectMessage();
        message.setObject(object);
        return message;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StreamMessage createStreamMessage() throws JMSException {
        illegalStateExceptionIfClosed();
        return new RMQStreamMessage();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TextMessage createTextMessage() throws JMSException {
        illegalStateExceptionIfClosed();
        return new RMQTextMessage();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TextMessage createTextMessage(String text) throws JMSException {
        illegalStateExceptionIfClosed();
        TextMessage msg = createTextMessage();
        msg.setText(text);
        return msg;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean getTransacted() throws JMSException {
        illegalStateExceptionIfClosed();
        return getTransactedNoException();
    }

    /**
     * Same as {@link #getTransacted()}
     * but does not declare a JMSException in the throw clause
     * @return true if this session is transacted
     */
    private boolean getTransactedNoException() {
        return this.transacted;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getAcknowledgeMode() throws JMSException {
        illegalStateExceptionIfClosed();
        return getAcknowledgeModeNoException();
    }

    public List<String> getTrustedPackages() {
        return trustedPackages;
    }

    /**
     * Set arguments to be used when declaring a queue while creating a producer.
     * <p>
     * Use this method only when you need to customize the creation of an AMQP queue.
     * Note calling this method requires to cast the JMS {@link Session} to {@link RMQSession},
     * coupling the code to the JMS implementation. Common usage is to keep this call in
     * one place, not scattered in the application code.
     *
     * @param queueDeclareArguments
     * @since 1.14.0
     */
    public void setQueueDeclareArguments(Map<String, Object> queueDeclareArguments) { this.queueDeclareArguments = queueDeclareArguments; }

    /**
     * Same as {@link RMQSession#getAcknowledgeMode()} but without
     * a declared exception in the throws clause.
     * @return the acknowledge mode, one of {@link Session#AUTO_ACKNOWLEDGE},
     * {@link Session#CLIENT_ACKNOWLEDGE}, {@link Session#DUPS_OK_ACKNOWLEDGE} or
     * {@link Session#SESSION_TRANSACTED}
     */
    int getAcknowledgeModeNoException() {
        return this.acknowledgeMode;
    }

    private boolean enterCommittingBlock() {
        synchronized(this.commitLock){
            try {
                while(this.committing) {
                    this.commitLock.wait(COMMIT_WAIT_MAX);
                }
                this.committing = true;
                return true;
            } catch(InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void leaveCommittingBlock() {
        synchronized(this.commitLock){
            this.committing = false;
            this.commitLock.notifyAll();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void commit() throws JMSException {
        logger.info("[RMQSession::commit] commit transaction on session {}", this);
        illegalStateExceptionIfClosed();
        if (!this.transacted) throw new IllegalStateException("Session is not transacted");
        if (this.enterCommittingBlock()) {
            try {
                // Call commit on the channel.
                // All messages ought already to have been acked.
                this.channel.txCommit();
                this.clearUncommittedTags();
            } catch (Exception x) {
                this.logger.error("RabbitMQ exception on channel.txCommit() in session {}", this, x);
                throw new RMQJMSException(x);
            } finally {
                this.leaveCommittingBlock();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void rollback() throws JMSException {
        logger.info("[RMQSession::rollback] rollback transaction on session {}", this);
        illegalStateExceptionIfClosed();
        if (!this.transacted) throw new IllegalStateException("Session is not transacted");
        if (this.enterCommittingBlock()) {
            try {
                // rollback the RabbitMQ transaction which may cause some messages to become unacknowledged
                this.channel.txRollback();
                if (this.nackOnRollback && this.uncommittedMessageTags.size() > 0) {
                    for (Long dtag : this.uncommittedMessageTags) {
                        this.channel.basicNack(dtag, false, false);
                    }
                    this.channel.txCommit();
                    this.clearUncommittedTags();
                }
                // requeue all unacknowledged messages (not automatically done by RabbitMQ)
                this.channel.basicRecover(true); // requeue
            } catch (IOException x) {
                this.logger.error("RabbitMQ exception on channel.txRollback() or channel.basicRecover(true) in session {}",
                                  this, x);
                throw new RMQJMSException(x);
            } finally {
                this.leaveCommittingBlock();
            }
        }
    }

    void explicitAck(long deliveryTag) {
        if (this.enterCommittingBlock()) {
            try {
                this.channel.basicAck(deliveryTag, false);
            } catch (Exception x) {
                // this is problematic, we have received a message, but we can't ACK it to the server
                this.logger.error("Cannot acknowledge message received (dTag={})", deliveryTag, x);
                // TODO should we deliver the message at this time, knowing that we can't ack it?
                // My recommendation is that we bail out here and not proceed -- but how?
            } finally {
                this.leaveCommittingBlock();
            }
        }
    }

    void explicitNack(long deliveryTag) {
        if (this.enterCommittingBlock()) {
            try {
                this.channel.basicNack(deliveryTag, false, true);
            } catch (Exception x) {
                // TODO logging impl debug message
                this.logger.warn("Cannot reject/requeue message received (dTag={})", deliveryTag, x);
                // this is fine. we didn't ack the message in the first place
            } finally {
                this.leaveCommittingBlock();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws JMSException {
        this.getConnection().sessionClose(this);
    }

    void internalClose() throws JMSException {
        if (this.closed) return;
        logger.info("[RMQSession::internalClose] close session {}", this);

        synchronized (this.closeLock) {
            try {
                // close consumers first (to prevent requeues being consumed)
                closeAllConsumers();

                // rollback anything not committed already
                if (this.getTransactedNoException()) {
                    // don't nack messages on close
                    this.clearUncommittedTags();
                    this.rollback();
                }

                //clear up potential executor
                this.deliveryExecutor.close();

                //close all producers created by this session
                for (RMQMessageProducer producer : this.producers) {
                    producer.internalClose();
                }
                this.producers.clear();

                //now commit anything done during close
                if (this.getTransactedNoException()) {
                    this.commit();
                }

                this.closeRabbitChannels();

            } finally {
                this.closed = true;
            }
        }
    }

    private void closeAllConsumers() {
        //close all consumers created by this session
        for (RMQMessageConsumer consumer : this.consumers) {
            try {
                consumer.internalClose();
            } catch (JMSException x) {
                this.logger.error("Consumer ({}) cannot be closed", consumer, x);
            }
        }
        this.consumers.clear();
    }

    void deliverMessage(RMQMessage rmqMessage, MessageListener messageListener) throws JMSException, InterruptedException {
        this.deliveryExecutor.deliverMessageWithProtection(rmqMessage, messageListener);
    }

    private void closeRabbitChannels() throws JMSException {
        this.clearBrowsingChannels(); // does not throw exception
        if (this.channel == null)
            return;
        try {
            this.channel.close();
        } catch (ShutdownSignalException x) {
            // nothing to do
        } catch (Exception x) {
            if (x instanceof IOException) {
                IOException ioe = (IOException) x;
                if (!(ioe.getCause() instanceof ShutdownSignalException)) {
                    this.logger.warn("RabbitMQ channel({}) failed to close on session {}", this.channel, this, ioe);
                    throw new RMQJMSException(ioe);
                }
            } else if (x instanceof TimeoutException) {
                TimeoutException te = (TimeoutException) x;
                this.logger.warn("RabbitMQ channel({}) timed out trying to close session {}", this.channel, this, te);
                throw new RMQJMSException(te);
            } else {
                throw new RMQJMSException("Unexpected exception from channel.close()", x);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void recover() throws JMSException {
        illegalStateExceptionIfClosed();
        if (getTransactedNoException()) {
            throw new javax.jms.IllegalStateException("Session is transacted.");
        } else {
            synchronized (this.unackedMessageTags) {
                /* If we have messages to recover */
                if (!this.unackedMessageTags.isEmpty()) {
                    try {
                        this.channel.basicRecover(true); // requeue
                    } catch (IOException x) {
                        logger.warn("basicRecover on channel({}) failed", this.channel, x);
                        throw new RMQJMSException(x);
                    }
                    this.unackedMessageTags.clear();
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageListener getMessageListener() throws JMSException {
        illegalStateExceptionIfClosed();
        return this.messageListener;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setMessageListener(MessageListener listener) throws JMSException {
        // not implemented
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        //this will not be implemented
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     * <p>
     * <b>Note</b>: The destination may be null.
     * </p>
     */
    @Override
    public MessageProducer createProducer(Destination destination) throws JMSException {
        logger.info("[RMQSession::createProducer] create producer for destination '{}' on session '{}'", destination, this);
        illegalStateExceptionIfClosed();
        RMQDestination dest = (RMQDestination) destination;
        declareDestinationIfNecessary(dest);
        RMQMessageProducer producer = new RMQMessageProducer(this, dest, this.preferProducerMessageProperty,
            this.amqpPropertiesCustomiser, this.sendingContextConsumer, this.publishingListener);
        this.producers.add(producer);
        return producer;
    }

    void declareDestinationIfNecessary(RMQDestination destination) throws JMSException {
        if (destination != null && !destination.isAmqp() && !destination.isDeclared()) {
            if (destination.isQueue()) {
                logger.info("[RMQSession::declareDestinationIfNecessary] declareRMQQueue for destination '{}' on session '{}'", destination, this);
                declareRMQQueue(destination, null, false, true);
            } else {
                logger.info("[RMQSession::declareDestinationIfNecessary] declareTopic for destination '{}' on session '{}'", destination, this);
                declareTopic(destination);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageConsumer createConsumer(Destination destination) throws JMSException {
        logger.info("[RMQSession::createConsumer] create consumer for destination '{}' on session '{}'", destination, this);
        illegalStateExceptionIfClosed();
        RMQDestination rmqDestination = null;
        if (!(destination instanceof RMQDestination)) {
            rmqDestination = convertorToRMQDestination(destination);
        } else {
            rmqDestination = (RMQDestination) destination;
        }
        return createConsumerInternal(rmqDestination, null, false, null);
    }

    /** All consumers on a session must be used all synchronously or all asynchronously */
    boolean syncAllowed() {
        // Return (None of the MessageConsumers have a (non-null) MessageListener set).
        for (RMQMessageConsumer mc : consumers) {
            if (mc.messageListenerIsSet()) return false;
        }
        return true;
    }
    boolean aSyncAllowed() {
        // Return (Number of receives is zero for all MessageConsumers.)
        for (RMQMessageConsumer mc : consumers) {
            if (0 != mc.getNumberOfReceives()) return false;
        }
        return true;
    }

    /**
     * Creates a consumer for a destination.
     * @param dest internal destination object
     * @param uuidTag only used for topics, if null, one is generated as the queue name for this topic
     * @param durableSubscriber true if this is a durable topic subscription
     * @param jmsSelector selector expression - null if no selection required
     * @return {@link #createConsumer(Destination)}
     * @throws JMSException if destination is null or we fail to create the destination on the broker
     * @see #createConsumer(Destination)
     */
    private RMQMessageConsumer createConsumerInternal(RMQDestination dest, String uuidTag, boolean durableSubscriber, String jmsSelector) throws JMSException {
        String consumerTag = uuidTag != null ? uuidTag : Util.generateUUID("jms-cons-");
        logger.info("[RMQSession::createConsumerInternal] create consumer for destination '{}' with consumerTag '{}' and selector '{}'", dest, consumerTag, jmsSelector);
        declareDestinationIfNecessary(dest);

        if (!dest.isQueue()) {
            // This is a topic, we need to define a queue, and bind to it.
            // The queue name is distinct for each consumer.
            try {
                String queueName = consumerTag;
                this.declareRMQQueue(dest, queueName, durableSubscriber, false);
                if (nullOrEmpty(jmsSelector)) {
                    // bind the queue to the exchange with the correct routing key
                    this.channel.queueBind(queueName, dest.getAmqpExchangeName(), dest.getAmqpRoutingKey());
                } else {
                    // get this session's topic selector exchange (name)
                    String selectionExchange = this.getSelectionExchange(durableSubscriber);
                    // bind it to the topic exchange with the topic routing key
                    this.channel.exchangeBind(selectionExchange, dest.getAmqpExchangeName(), dest.getAmqpRoutingKey());
                    this.bindSelectorQueue(dest, jmsSelector, queueName, selectionExchange);
                }
            } catch (IOException x) {
                logger.error("consumer with tag '{}' could not be created", consumerTag, x);
                throw new RMQJMSException("RabbitMQ Exception creating Consumer", x);
            }
        }
        RMQMessageConsumer consumer = new RMQMessageConsumer(this, dest, consumerTag, getConnection().isStopped(),
            jmsSelector, this.requeueOnMessageListenerException, this.receivingContextConsumer);
        this.consumers.add(consumer);
        return consumer;
    }

    private void bindSelectorQueue(RMQDestination dest, String jmsSelector, String queueName, String selectionExchange)
            throws InvalidSelectorException, IOException {
        SqlCompiler compiler = new SqlCompiler(new SqlEvaluator(new SqlParser(new SqlTokenStream(jmsSelector)), JMS_TYPE_IDENTS));
        if (compiler.compileOk()) {
            Map<String, Object> args = new HashMap<String, Object>(5);
            args.put(RJMS_COMPILED_SELECTOR_ARG, (Object)compiler.compile());
            args.put(RJMS_VERSION_ARG, (Object)RJMS_CLIENT_VERSION);
            // bind the queue to the topic selector exchange with the jmsSelector expression as argument
            this.channel.queueBind(queueName, selectionExchange, dest.getAmqpRoutingKey(), args);
        } else {
            throw new RMQJMSSelectorException(String.format("Selector expression failure: \"%s\".", jmsSelector));
        }
    }

    /**
     * The topic selector exchange may be created for this session (there are at most two per session).
     * @param durableSubscriber - set to true if we need to use a durable exchange, false otherwise.
     * @return this session's Selection Exchange
     * @throws IOException
     */
    private String getSelectionExchange(boolean durableSubscriber) throws IOException {
        if (durableSubscriber) {
            return this.getDurableTopicSelectorExchange();
        } else {
            return this.getNonDurableTopicSelectorExchange();
        }
    }

    private String getDurableTopicSelectorExchange() throws IOException {
        if (this.durableTopicSelectorExchange==null) {
            this.durableTopicSelectorExchange = Util.generateUUID("jms-dutop-slx-");
        }
        this.channel.exchangeDeclare(this.durableTopicSelectorExchange, JMS_TOPIC_SELECTOR_EXCHANGE_TYPE, true, true, RJMS_SELECTOR_EXCHANGE_ARGS);
        return this.durableTopicSelectorExchange;
    }

    private String getNonDurableTopicSelectorExchange() throws IOException {
        if (this.nonDurableTopicSelectorExchange==null) {
            this.nonDurableTopicSelectorExchange = Util.generateUUID("jms-ndtop-slx-");
        }
        this.channel.exchangeDeclare(this.nonDurableTopicSelectorExchange, JMS_TOPIC_SELECTOR_EXCHANGE_TYPE, false, true, RJMS_SELECTOR_EXCHANGE_ARGS);
        return this.nonDurableTopicSelectorExchange;
    }

    /**
     * {@inheritDoc}
     * @throws UnsupportedOperationException - method not implemented until we support selectors
     */
    @Override
    public MessageConsumer createConsumer(Destination destination, String messageSelector) throws JMSException {
        logger.info("[RMQSession::createConsumer] create consumer for destination '{}' with '{}' on session '{}'", destination, messageSelector, this);

        illegalStateExceptionIfClosed();

        if (nullOrEmpty(messageSelector)) {
            return createConsumer(destination);
        } else if (isTopic(destination)) {
            RMQDestination rmqDestination = null;
            if (!(destination instanceof RMQDestination)) {
                rmqDestination = convertorToRMQDestination(destination);
            } else {
                rmqDestination = (RMQDestination) destination;
            }
            return createConsumerInternal(rmqDestination, null, false, messageSelector);
        } else {
            // selectors are not supported for queues
            throw new UnsupportedOperationException();
        }
    }

    private RMQDestination convertorToRMQDestination(Destination destination) throws JMSException {
        logger.info("[RMQSession::convertorToRMQDestination] convert '{}' on session '{}'", destination, this);

        String name = "";
        boolean isQueue = false;
        boolean temporary = false;

        if (destination instanceof Queue) {
            isQueue = true;
            name = ((Queue) destination).getQueueName();
        } else if (destination instanceof Topic) {
            name = ((Topic) destination).getTopicName();
        } else {
            throw new UnsupportedOperationException();
        }

        if (destination instanceof TemporaryQueue || destination instanceof TemporaryTopic) {
            temporary = true;
        }
        
        RMQDestination rmqDestination = new RMQDestination(name, isQueue, temporary);
        return rmqDestination;
    }

    private static boolean nullOrEmpty(String str) {
        return str==null || str.trim().isEmpty();
    }

    private static boolean isTopic(Destination destination) {
        if (destination instanceof RMQDestination) {
            return !((RMQDestination) destination).isQueue();
        }
        
        if (destination instanceof Topic) {
            return true;
        }

        return false;
    }

    /**
     * {@inheritDoc}
     * @throws UnsupportedOperationException - method not implemented until we support selectors
     */
    @Override
    public MessageConsumer createConsumer(Destination destination, String messageSelector, boolean noLocal) throws JMSException {
        logger.info("[RMQSession::createConsumer] create consumer '{}' with '{}' on session '{}'", destination, messageSelector, this);
        illegalStateExceptionIfClosed();
        if (nullOrEmpty(messageSelector)) {
            RMQMessageConsumer consumer = (RMQMessageConsumer)createConsumer(destination);
            consumer.setNoLocal(noLocal);
            return consumer;
        } else if (isTopic(destination)) {
            RMQDestination rmqDestination = convertorToRMQDestination(destination);
            RMQMessageConsumer consumer = createConsumerInternal(rmqDestination, null, false, messageSelector);
            consumer.setNoLocal(noLocal);
            return consumer;
        }  else {
            // selectors are not supported for queues
            throw new UnsupportedOperationException();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Queue createQueue(String queueName) throws JMSException {
        logger.info("[RMQSession::createQueue] create queue '{}' on session '{}'", queueName, this);
        illegalStateExceptionIfClosed();
        RMQDestination dest = new RMQDestination(queueName, true, false);
        declareRMQQueue(dest, null, false, true);
        return dest;
    }

    /**
     * Invokes {@link Channel#queueDeclare(String, boolean, boolean, boolean, java.util.Map)} to define a queue on the RabbitMQ broker
     * this method invokes {@link RMQDestination#setDeclared(boolean)} with a true value
     * @param dest - the Queue Destination object
     * @param queueNameOverride name of queue to declare (if different from destination name)
     * @param durableSubscriber - true if the subscriber ius
     * @throws JMSException if an IOException occurs in the {@link Channel#queueDeclare(String, boolean, boolean, boolean, java.util.Map)} call
     */
    private void declareRMQQueue(RMQDestination dest, String queueNameOverride, boolean durableSubscriber, boolean bind) throws JMSException {
        logger.info("[RMQSession::createProducer] declare RabbitMQ queue for destination '{}', explicitName '{}', durableSubscriber={}", dest, queueNameOverride, durableSubscriber);
        String queueName = queueNameOverride!=null ? queueNameOverride : dest.getQueueName();

        String exchangeName = dest.getAmqpExchangeName();
        String exchangeType = dest.amqpExchangeType();

        /*
         * We only want destinations to survive server restarts if
         * 1. They are durable topic subscriptions OR
         * 2. They are permanent queues
         */
        boolean durable = durableSubscriber || (dest.isQueue() & (!dest.isTemporary()));

        /*
         * A queue is exclusive, meaning it can only be accessed by the current connection
         * and will be deleted when the connection is closed if
         * 1. It's a temporary destination OR
         * 2. It's a non durable topic
         */
        boolean exclusive = dest.isTemporary() || ((!dest.isQueue()) && (!durableSubscriber));

        if (dest.isQueue()) {
            if (dest.noNeedToDeclareExchange()) {
                logger.warn("no need to declare built-in exchange for queue destination '{}'", dest);
            }
            else {
                logger.info("[RMQSession::declareRMQQueue] declare RabbitMQ exchange for queue destinations '{}'", dest);
                try {
                    this.channel.exchangeDeclare(exchangeName, exchangeType, durable,
                                                 false, // autoDelete
                                                 false, // internal
                                                 null); // object properties
                } catch (Exception x) {
                    throw new RMQJMSException(x);
                }
            }
        }

        /* broker queues declared for a non-durable topic that have an auto-generated name must go down with
           consumer/producer or the broker will leak them until the connection is brought down
        */
        boolean autoDelete = cleanUpServerNamedQueuesForNonDurableTopics ?
            !durable && queueNameOverride != null && !dest.isQueue() : false;

        try { /* Declare the queue to RabbitMQ -- this creates it if it doesn't already exist */
            this.logger.debug("declare RabbitMQ queue name({}), durable({}), exclusive({}), auto-delete({}), arguments({})",
                              queueName, durable, exclusive, false, queueDeclareArguments);
            this.channel.queueDeclare(queueName,
                                      durable,
                                      exclusive,
                                      autoDelete,
                                      queueDeclareArguments);

            /* Temporary or 'topic queues' are exclusive and therefore get deleted by RabbitMQ on close */
        } catch (Exception x) {
            this.logger.error("RabbitMQ exception on queue declare name({}), durable({}), exclusive({}), auto-delete({}), arguments({})",
                              queueName, durable, exclusive, autoDelete, queueDeclareArguments, x);
            throw new RMQJMSException(x);
        }

        if (bind) {
            try { /* Bind the queue to our exchange -- this allows publications to succeed. */
                this.logger.debug("bind queue name({}), to exchange({}), with r-key({}), no arguments",
                        queueName, exchangeName, queueName);
                this.channel.queueBind(queueName, exchangeName,
                        queueName, // routing key
                        null); // arguments
            } catch (Exception x) {
                this.logger.error("RabbitMQ exception on queue declare name({}), durable({}), exclusive({}), auto-delete({}), properties({})",
                        queueName, durable, exclusive, false, queueDeclareArguments, x);
                throw new RMQJMSException(x);
            }
        }
        dest.setDeclared(true);
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public Topic createTopic(String topicName) throws JMSException {
        logger.info("[RMQSession::createTopic] create topic '{}' on session '{}'", topicName, this);
        illegalStateExceptionIfClosed();
        RMQDestination dest = new RMQDestination(topicName, false, false);
        declareTopic(dest);
        return dest;
    }

    /**
     * Declares a topic exchange in RabbitMQ.
     * @param dest the topic destination
     * @throws JMSException
     */
    private void declareTopic(RMQDestination dest) throws JMSException {
        if (dest.noNeedToDeclareExchange()) {
            logger.warn("no need to declare built-in exchange for topic destination '{}'", dest);
        }
        else {
            logger.info("[RMQSession::declareTopic] declare RabbitMQ exchange for topic destination '{}'", dest);
            try {
                this.channel.exchangeDeclare(/* the name of the exchange */
                                             dest.getAmqpExchangeName(),
                                             /* the type of exchange to use */
                                             dest.amqpExchangeType(),
                                             /* durable for all except temporary topics */
                                             !dest.isTemporary(),
                                             // TODO: how do we delete exchanges used for temporary topics
                                             /* auto delete is always false */
                                             false,
                                             /* internal is false: JMS clients will want to publish directly to the exchange */
                                             false,
                                             /* object parameters */
                                             null);
            } catch (IOException x) {
                throw new RMQJMSException(x);
            }
        }
        dest.setDeclared(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TopicSubscriber createDurableSubscriber(Topic topic, String name) throws JMSException {
        logger.info("[RMQSession::createDurableSubscriber] create topic '{}' with '{}' on session '{}'", topic, name, this);
        return createDurableSubscriber(topic, name, null, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TopicSubscriber createDurableSubscriber(Topic topic, String name, String messageSelector, boolean noLocal) throws JMSException {
        logger.info("[RMQSession::createDurableSubscriber] create topic '{}' with '{}' and '{}' on session '{}'", topic, name, messageSelector, this);
        illegalStateExceptionIfClosed();

        RMQDestination topicDest = (RMQDestination) topic;
        RMQMessageConsumer previousConsumer = this.subscriptions.get(name);
        if (previousConsumer!=null) {
            // we are changing subscription, or not, if called with the same topic
            RMQDestination prevDest = previousConsumer.getDestination();
            if (prevDest.equals(topicDest)) {
                if (previousConsumer.isClosed()) {
                    // They called TopicSubscriber.close but didn't unsubscribe
                    // and they are simply resubscribing with a new one
                    logger.warn("Re-subscribing to topic '{}' with name '{}'", topicDest, name);
                } else {
                    logger.error("Subscription with name '{}' for topic '{}' already exists", name, topicDest);
                    throw new JMSException(String.format("Subscription with name [%s] and topic [%s] already exists", name, topicDest));
                }
            } else {
                logger.warn("Previous subscription with name '{}' was for topic '{}' and is replaced by one for topic '{}'", name, prevDest, topicDest);
                unsubscribe(name);
            }
        }
        // Create a new subscription
        RMQMessageConsumer consumer = (RMQMessageConsumer)createConsumerInternal(topicDest, name, true, messageSelector);
        consumer.setDurable(true);
        consumer.setNoLocal(noLocal);
        this.subscriptions.put(name, consumer);
        return consumer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public QueueBrowser createBrowser(Queue queue) throws JMSException {
        logger.info("[RMQSession::createBrowser] create browser '{}' on session '{}'", queue, this);
        illegalStateExceptionIfClosed();
        return createBrowser(queue, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public QueueBrowser createBrowser(Queue queue, String messageSelector) throws JMSException {
        logger.info("[RMQSession::createBrowser] create browser '{}' with '{}' on session '{}'", queue, messageSelector, this);
        illegalStateExceptionIfClosed();
        if (queue instanceof RMQDestination) {
            RMQDestination rmqDest = (RMQDestination) queue;
            if (rmqDest.isQueue()) {
                return new BrowsingMessageQueue(this, rmqDest, messageSelector,
                    this.connection.getQueueBrowserReadMax(), this.receivingContextConsumer);
            }
        }
        throw new UnsupportedOperationException("Unknown destination");
    }

    /**
     * Get a (new) channel for queue browsing.
     * @return channel for browsing queues
     * @throws JMSException if channel not available
     */
    Channel getBrowsingChannel() throws JMSException {
        logger.info("[RMQSession::getBrowsingChannel] get browsing channel on session '{}'", this);
        try {
            synchronized (this.bcLock) {
                Channel chan = this.getConnection().createRabbitChannel(false); // not transactional
                this.browsingChannels.add(chan);
                return chan;
            }
        } catch (Exception e) { // includes unchecked exceptions, e.g. ShutdownSignalException
            throw new RMQJMSException("Cannot create browsing channel", e);
        }
    }

    /**
     * Silently close and discard browsing channels, if any.
     */
    private void clearBrowsingChannels() {
        logger.info("[RMQSession::clearBrowsingChannels] clear browsing channel on session '{}'", this);
        synchronized (this.bcLock) {
            for (Channel chan : this.browsingChannels) {
                try {
                    if (chan.isOpen())
                        chan.close();
                } catch (Exception e) {
                    // ignore any failures, we are clearing up
                }
            }
            this.browsingChannels.clear();
        }
    }

    /**
     * Close a specific browsing channel.
     */
    void closeBrowsingChannel(Channel chan) {
        logger.info("[RMQSession::closeBrowsingChannel] close browsing channel on session '{}'", this);
        try {
            synchronized (this.bcLock) {
                if (this.browsingChannels.remove(chan)) {
                    if (chan.isOpen())
                        chan.close();
                }
            }
        } catch (Exception e) {
            // throw new RMQJMSException("Cannot close browsing channel", _);
            // ignore errors in clearing up
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TemporaryQueue createTemporaryQueue() throws JMSException {
        logger.info("[RMQSession::createTemporaryQueue] create temporary queue on session '{}'", this);
        illegalStateExceptionIfClosed();
        return new RMQDestination(Util.generateUUID("jms-temp-queue-"), true, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TemporaryTopic createTemporaryTopic() throws JMSException {
        logger.info("[RMQSession::createTemporaryTopic] create temporary topic on session '{}'", this);
        illegalStateExceptionIfClosed();
        return new RMQDestination(Util.generateUUID("jms-temp-topic-"), false, true);
    }

    /**
     * This is only available for topic subscriptions.
     * {@inheritDoc}
     */
    @Override
    public void unsubscribe(String name) throws JMSException {
        logger.info("[RMQSession::unsubscribe] unsubscribe on session '{}'", this);
        illegalStateExceptionIfClosed();
        try {
            if (name != null && this.subscriptions.remove(name) != null) {
                // remove the queue
                this.channel.queueDelete(name);
            } else {
                logger.warn("Cannot unsubscribe subscription named '{}'", name);
            }
        } catch (IOException x) {
            logger.error("RabbitMQ Queue delete for queue named '{}' failed", name, x);
            throw new RMQJMSException(x);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public QueueReceiver createReceiver(Queue queue) throws JMSException {
        logger.info("[RMQSession::createReceiver] create receiver on session '{}'", this);
        illegalStateExceptionIfClosed();
        return (QueueReceiver) this.createConsumer(queue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public QueueReceiver createReceiver(Queue queue, String messageSelector) throws JMSException {
        logger.info("[RMQSession::createReceiver] create receiver '{}' with '{}' on session '{}'", queue, messageSelector, this);
        illegalStateExceptionIfClosed();
        return (QueueReceiver) this.createConsumer(queue, messageSelector);
    }

    /**
     * {@inheritDoc}
     * <p>
     * <b>Note</b>: The queue may be null -- see {@link #createProducer}.
     * </p>
     */
    @Override
    public QueueSender createSender(Queue queue) throws JMSException {
        logger.info("[RMQSession::createSender] create receiver '{}' on session '{}'", queue, this);
        illegalStateExceptionIfClosed();
        return (QueueSender) this.createProducer(queue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TopicSubscriber createSubscriber(Topic topic) throws JMSException {
        logger.info("[RMQSession::createSubscriber] create receiver '{}' on session '{}'", topic, this);
        illegalStateExceptionIfClosed();
        return (TopicSubscriber) this.createConsumer(topic);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TopicSubscriber createSubscriber(Topic topic, String messageSelector, boolean noLocal) throws JMSException {
        logger.info("[RMQSession::createSubscriber] create receiver '{}' with '{}' on session '{}'", topic, messageSelector, this);
        illegalStateExceptionIfClosed();
        RMQMessageConsumer consumer = createConsumerInternal((RMQDestination) topic, null, false, messageSelector);
        consumer.setNoLocal(noLocal);
        return consumer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TopicPublisher createPublisher(Topic topic) throws JMSException {
        logger.info("[RMQSession::createPublisher] create receiver '{}' on session '{}'", topic, this);
        illegalStateExceptionIfClosed();
        return (TopicPublisher) this.createProducer(topic);
    }

    /**
     * Returns the connection this session is associated with
     * @return
     */
    RMQConnection getConnection() {
        logger.info("[RMQSession::getConnection] get connection on session '{}'", this);
        return this.connection;
    }

    /**
     * Returns the {@link Channel} this session has created
     * @return
     */
    Channel getChannel() {
        logger.info("[RMQSession::getChannel] get channel on session '{}'", this);
        return this.channel;
    }

    void consumerClose(RMQMessageConsumer consumer) throws JMSException {
        logger.info("[RMQSession::consumerClose] consumer close '{}' on session '{}'", consumer, this);
        if (this.consumers.remove(consumer)) {
            //TODO: if (consumer.isDurable()) { don't cancel it? cancel it? -- decide }
            consumer.internalClose();
        }
    }

    void removeProducer(RMQMessageProducer producer) {
        logger.info("[RMQSession::removeProducer] remove producer '{}' on session '{}'", producer, this);
        if (this.producers.remove(producer)) {
            producer.internalClose();
        }
    }

    boolean isAutoAck() {
        logger.info("[RMQSession::isAutoAck] is auto ack on session '{}'", this);
        return (getAcknowledgeModeNoException()!=Session.CLIENT_ACKNOWLEDGE);  // only case when auto ack not required
    }

    /**
     * Stops all consumers from receiving messages. This is called by the
     * session indirectly after {@link javax.jms.Connection#stop()} has been
     * invoked. In this implementation, any async consumers will be cancelled,
     * only to be re-subscribed when
     *
     * @throws javax.jms.JMSException if the thread is interrupted
     */
    void pause() throws JMSException {
        logger.info("[RMQSession::pause] pause on session '{}'", this);
        for (RMQMessageConsumer consumer : this.consumers) {
            try {
                consumer.pause();
            } catch (JMSException e) {
                throw e;
            } catch (InterruptedException x) {
                logger.error("Consumer({}) pause interrupted", consumer, x);
                throw new RMQJMSException(x);
            } catch (Exception x) {
                logger.error("Error while pausing consumer({})", consumer, x);
                throw new RMQJMSException(x);
            }
        }
    }

    /**
     * Resubscribes all async listeners and continues to receive messages
     *
     * @see javax.jms.Connection#stop()
     * @throws javax.jms.JMSException if the thread is interrupted
     */
    void resume() throws JMSException {
        logger.info("[RMQSession::resume] resume on session '{}'", this);
        for (RMQMessageConsumer consumer : this.consumers) {
            try {
                consumer.resume();
            } catch (IllegalStateException x) {
                throw new RMQJMSException(x);
            }
        }
    }

    void unackedMessageReceived(long dTag) {
        logger.info("[RMQSession::unackedMessageReceived] unackedMessageReceived on session '{}'", this);
        if (!getTransactedNoException()) {
            synchronized (this.unackedMessageTags) {
                this.unackedMessageTags.add(dTag);
            }
        }
    }

    /**
     * Acknowledges messages in this session.
     * Invoked when the method {@link javax.jms.Message#acknowledge()} is called.
     * @param message - the message to be acknowledged, or the carrier to acknowledge all messages
     */
    void acknowledgeMessage(RMQMessage message) throws JMSException {
        logger.info("[RMQSession::acknowledgeMessage] acknowledgeMessage on session '{}'", this);
        illegalStateExceptionIfClosed();

        boolean individualAck = this.getIndividualAck();
        boolean groupAck      = true;  // This assumption is new in RJMS 1.2.0 and is consistent with other implementations. It allows a form of group acknowledge.
        if (!isAutoAck() && !this.unackedMessageTags.isEmpty()) {
            /**
             * Per JMS specification of {@link Message#acknowledge()}, <i>if we ack the last message in a group, we will ack all the ones prior received</i>.
             * <p>But, JMS spec 11.2.21 says:</p>
             * <pre>"Note that the acknowledge method of Message acknowledges all messages
             * received on that message's session."</pre>
             * <p>
             * The groupAck option acknowledges all previous messages in this session (and this one, too, if not acknowledged already).
             * The individualAck option is set by session mode (CLIENT_INDIVIDUAL_ACKNOWLEDGE) and overrides groupAck (default) and acknowledges at most a single message.
             * </p>
             */
            synchronized (this.unackedMessageTags) {
                try {
                    if (individualAck) {
                        long messageTag = message.getRabbitDeliveryTag();
                        if (!this.unackedMessageTags.contains(messageTag)) return; // this message already acknowledged
                        /* ACK a single message */
                        this.getChannel().basicAck(messageTag, false); // we ack the single message with this tag
                        this.unackedMessageTags.remove(messageTag);
                    } else if (groupAck) {
                        long messageTag = message.getRabbitDeliveryTag();
                        /** The tags that precede the given one, and the given one, if unacknowledged */
                        SortedSet<Long> previousTags = this.unackedMessageTags.headSet(messageTag+1);
                        if (previousTags.isEmpty()) return; // no message to acknowledge
                        /* ack multiple message up until the existing tag */
                        this.getChannel().basicAck(previousTags.last(), // we ack the latest one (which might be this one, but might not be)
                                              true);               // and everything prior to that
                        // now remove all the tags <= messageTag
                        previousTags.clear();
                    } else {
                        // this block is no longer possible (groupAck == true) after RJMS 1.2.0
                        this.getChannel().basicAck(this.unackedMessageTags.last(), // we ack the highest tag
                                              true);                          // and everything prior to that
                        this.unackedMessageTags.clear();
                    }
                } catch (IOException x) {
                    this.logger.error("RabbitMQ exception on basicAck of message {}; on session '{}'", message, this, x);
                    throw new RMQJMSException(x);
                }
            }
        }
    }

    private final boolean getIndividualAck() {
        logger.info("[RMQSession::getIndividualAck] getIndividualAck on session '{}'", this);
        return this.isIndividualAck;
    }

    void addUncommittedTag(long deliveryTag) {
        logger.info("[RMQSession::addUncommittedTag] addUncommittedTag on session '{}'", this);
        if (this.nackOnRollback && this.getTransactedNoException()) {
            if (this.enterCommittingBlock()) {
                this.uncommittedMessageTags.add(deliveryTag);
                this.leaveCommittingBlock();
            }
        }
    }

    private void clearUncommittedTags() {
        logger.info("[RMQSession::clearUncommittedTags] clearUncommittedTags on session '{}'", this);
        if (this.nackOnRollback) {
            this.uncommittedMessageTags.clear();
        }
    }
}
