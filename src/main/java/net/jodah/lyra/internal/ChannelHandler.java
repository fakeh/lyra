package net.jodah.lyra.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import net.jodah.lyra.config.ChannelConfig;
import net.jodah.lyra.config.Config;
import net.jodah.lyra.event.ChannelListener;
import net.jodah.lyra.event.ConsumerListener;
import net.jodah.lyra.internal.util.Collections;
import net.jodah.lyra.internal.util.Exceptions;
import net.jodah.lyra.internal.util.Reflection;

import com.rabbitmq.client.AMQP.Queue;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.FlowListener;
import com.rabbitmq.client.ReturnListener;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;

/**
 * Handles channel method invocations and performs channel recovery.
 * 
 * @author Jonathan Halterman
 */
public class ChannelHandler extends RetryableResource implements InvocationHandler {
  private final ConnectionHandler connectionHandler;
  private final Config config;
  volatile long previousMaxDeliveryTag;
  volatile long maxDeliveryTag;
  volatile String lastGeneratedQueueName;
  Channel proxy;
  Channel delegate;

  // Recovery state
  private AtomicBoolean recoveryPending = new AtomicBoolean();
  private RecurringStats recoveryStats;
  private Map<String, ConsumerDeclaration> recoveryConsumers;
  private ShutdownSignalException lastShutdownSignal;

  // Delegate state
  final Map<String, ConsumerDeclaration> consumerDeclarations = Collections.synchronizedMap();
  private final List<ConfirmListener> confirmListeners = new CopyOnWriteArrayList<ConfirmListener>();
  private final List<FlowListener> flowListeners = new CopyOnWriteArrayList<FlowListener>();
  private final List<ReturnListener> returnListeners = new CopyOnWriteArrayList<ReturnListener>();
  private boolean flowDisabled;
  private ResourceDeclaration basicQos;
  private boolean confirmSelect;
  private boolean txSelect;

  public ChannelHandler(ConnectionHandler connectionHandler, Channel delegate, Config config) {
    this.connectionHandler = connectionHandler;
    this.delegate = delegate;
    this.config = config;

    ShutdownListener listener = new ChannelShutdownListener();
    shutdownListeners.add(listener);
    delegate.addShutdownListener(listener);
  }

  /**
   * Handles channel shutdowns.
   */
  private class ChannelShutdownListener implements ShutdownListener {
    @Override
    public void shutdownCompleted(ShutdownSignalException e) {
      channelShutdown();
      if (!e.isInitiatedByApplication()) {
          log.severe("Channel "+ ChannelHandler.this +" was closed unexpectedly");
        lastShutdownSignal = e;
        if (!Exceptions.isConnectionClosure(e) && canRecover())
          ConnectionHandler.RECOVERY_EXECUTORS.execute(new Runnable() {
            @Override
            public void run() {
              try {
                recoveryPending.set(true);
                recoverChannel(false);
              } catch (Throwable ignore) {
              }
            }
          });
      }
    }
  }

  @Override
  public Object invoke(Object ignored, final Method method, final Object[] args) throws Throwable {
    if (closed && method.getDeclaringClass().isAssignableFrom(Channel.class))
      throw new AlreadyClosedException("Attempt to use closed channel", proxy);

    Callable<Object> callable = new Callable<Object>() {
      @Override
      public Object call() throws Exception {
        if (method.getDeclaringClass().isAssignableFrom(ChannelConfig.class))
          return Reflection.invoke(config, method, args);

        String methodName = method.getName();

        if ("basicAck".equals(methodName) || "basicNack".equals(methodName)
            || "basicReject".equals(methodName)) {
          long deliveryTag = (Long) args[0] - previousMaxDeliveryTag;
          if (deliveryTag > 0)
            args[0] = deliveryTag;
          else
            return null;
        } else if ("basicConsume".equals(methodName))
          return handleConsumerDeclare(method, args);
        else if ("basicCancel".equals(methodName) && args[0] != null)
          consumerDeclarations.remove((String) args[0]);
        else if ("exchangeDelete".equals(methodName) && args[0] != null)
          connectionHandler.exchangeDeclarations.remove((String) args[0]);
        else if ("exchangeUnbind".equals(methodName) && args[0] != null)
          connectionHandler.exchangeBindings.remove((String) args[0], new Binding(args));
        else if ("queueDelete".equals(methodName) && args[0] != null)
          connectionHandler.queueDeclarations.remove((String) args[0]);
        else if ("queueUnbind".equals(methodName) && args[0] != null)
          connectionHandler.queueBindings.remove((String) args[0], new Binding(args));

        Object result = Reflection.invoke(delegate, method, args);

        if ("exchangeDeclare".equals(methodName))
          handleExchangeDeclare(method, args);
        else if ("exchangeBind".equals(methodName))
          handleExchangeBind(args);
        else if ("queueDeclare".equals(methodName))
          handleQueueDeclare(((Queue.DeclareOk) result).getQueue(), method, args);
        else if ("queueBind".equals(methodName))
          handleQueueBind(method, args);
        else if ("flow".equals(methodName))
          flowDisabled = !(Boolean) args[0];
        else if ("basicQos".equals(methodName)) {
          // Store non-global Qos
          if (args.length < 3 || !(Boolean) args[2])
            basicQos = new ResourceDeclaration(method, args);
        } else if ("confirmSelect".equals(methodName))
          confirmSelect = true;
        else if ("txSelect".equals(methodName))
          txSelect = true;
        else if (methodName.startsWith("add"))
          handleAdd(methodName, args[0]);
        else if (methodName.startsWith("remove"))
          handleRemove(methodName, args[0]);
        else if (methodName.startsWith("clear"))
          handleClear(methodName);

        return result;
      }

      @Override
      public String toString() {
        return Reflection.toString(method);
      }
    };

    return handleCommonMethods(delegate, method, args) ? null : callWithRetries(callable,
        config.getChannelRetryPolicy(), null, canRecover(), true);
  }

  @Override
  public String toString() {
    return String.format("channel-%s on %s", delegate.getChannelNumber(), connectionHandler);
  }

  @Override
  void afterClosure() {
    connectionHandler.removeChannel(delegate.getChannelNumber());
  }

  boolean canRecover() {
    return connectionHandler.canRecover() && config.getChannelRecoveryPolicy() != null
        && config.getChannelRecoveryPolicy().allowsAttempts();
  }

  void channelShutdown() {
    circuit.open();
    synchronized (consumerDeclarations) {
      for (ResourceDeclaration invocation : consumerDeclarations.values())
        ((ConsumerDelegate) invocation.args[invocation.args.length - 1]).close();
    }
  }

  /**
   * Atomically recovers the channel.
   * 
   * @throws Exception when recovery fails due to a connection closure
   */
  synchronized void recoverChannel(boolean viaConnectionRecovery) throws Exception {
    recoveryPending.set(false);
    if (circuit.isClosed())
      return;

    if (recoveryStats == null) {
      recoveryConsumers = consumerDeclarations.isEmpty() ? null
          : new HashMap<String, ConsumerDeclaration>(consumerDeclarations);
      recoveryStats = new RecurringStats(config.getChannelRecoveryPolicy());
      recoveryStats.incrementTime();
    } else if (recoveryStats.isPolicyExceeded()) {
      recoveryFailed(lastShutdownSignal);
      if (!viaConnectionRecovery)
        return;
    }

    try {
      delegate = callWithRetries(new Callable<Channel>() {
        @Override
        public Channel call() throws Exception {
          log.info("Recovering "+ ChannelHandler.this);
          previousMaxDeliveryTag = maxDeliveryTag;
          Channel channel = connectionHandler.createChannel(delegate.getChannelNumber());
          migrateConfiguration(channel);
          log.info("Recovered "+ ChannelHandler.this);
          return channel;
        }
      }, config.getChannelRecoveryPolicy(), recoveryStats, true, false);
      notifyRecovery();
      recoverConsumers(!viaConnectionRecovery);
      recoverySucceeded();
    } catch (Exception e) {
      ShutdownSignalException sse = Exceptions.extractCause(e, ShutdownSignalException.class);
      if (sse != null) {
        if (Exceptions.isConnectionClosure(sse))
          throw e;
      } else if (recoveryStats.isPolicyExceeded())
        recoveryFailed(e);
    }
  }

  private void handleAdd(String methodName, Object arg) {
    if ("addConfirmListener".equals(methodName))
      confirmListeners.add((ConfirmListener) arg);
    else if ("addFlowListener".equals(methodName))
      flowListeners.add((FlowListener) arg);
    else if ("addReturnListener".equals(methodName))
      returnListeners.add((ReturnListener) arg);
  }

  private void handleClear(String methodName) {
    if ("clearConfirmListeners".equals(methodName))
      confirmListeners.clear();
    else if ("clearFlowListeners".equals(methodName))
      flowListeners.clear();
    else if ("clearReturnListeners".equals(methodName))
      returnListeners.clear();
  }

  private String handleConsumerDeclare(Method method, Object[] args) throws Exception {
    if (config.isConsumerRecoveryEnabled()) {
      Consumer consumer = (Consumer) args[args.length - 1];
      args[args.length - 1] = new ConsumerDelegate(this, consumer);
      String consumerTag = (String) Reflection.invoke(delegate, method, args);
      if (args.length > 3)
        args[2] = consumerTag;
      String queueName = "".equals(args[0]) ? lastGeneratedQueueName : (String) args[0];
      QueueDeclaration queueDeclaration = connectionHandler.queueDeclarations.get(queueName);
      if (queueDeclaration != null)
        queueName = queueDeclaration.name;
      consumerDeclarations.put(consumerTag, new ConsumerDeclaration(queueDeclaration, method, args));
      log.info("".equals(queueName) ? "Created consumer-"+ consumerTag +" via " + this
          : "Created consumer-"+ consumerTag +" of "+ queueName +" via "+ this);
      return consumerTag;
    } else
      return (String) Reflection.invoke(delegate, method, args);
  }

  private void handleExchangeBind(Object[] args) {
    if (config.isExchangeRecoveryEnabled())
      connectionHandler.exchangeBindings.put((String) args[0], new Binding(args));
  }

  private void handleExchangeDeclare(Method method, Object[] args) {
    if (config.isExchangeRecoveryEnabled()) {
      boolean autoDelete = args.length > 3 && (Boolean) args[3]; //DJM Fixed, AIOOBE when using minimal exchangeDeclare(String, String)
      boolean durable = args.length > 2 && (Boolean) args[2];
      if (autoDelete || !durable)
        connectionHandler.exchangeDeclarations.put((String) args[0], new ResourceDeclaration(
            method, args));
    }
  }

  private void handleQueueBind(Method method, Object[] args) {
    if (config.isQueueRecoveryEnabled())
      connectionHandler.queueBindings.put("".equals(args[0]) ? lastGeneratedQueueName
          : (String) args[0], new Binding(args));
  }

  private void handleQueueDeclare(String queueName, Method method, Object[] args) {
    if (args == null)
      lastGeneratedQueueName = queueName;

    if (config.isQueueRecoveryEnabled()) {
      boolean autoDelete = args == null || (Boolean) args[3];
      boolean durable = args != null && (Boolean) args[1];
      if (autoDelete || !durable)
        connectionHandler.queueDeclarations.put(queueName, new QueueDeclaration(queueName, method,
            args));
    }
  }

  private void handleRemove(String methodName, Object arg) {
    if ("removeConfirmListener".equals(methodName))
      confirmListeners.remove((ConfirmListener) arg);
    else if ("removeFlowListener".equals(methodName))
      flowListeners.remove((FlowListener) arg);
    else if ("removeReturnListener".equals(methodName))
      returnListeners.remove((ReturnListener) arg);
  }

  /**
   * Migrates the channel's configuration to the given {@code channel}.
   */
  private void migrateConfiguration(Channel channel) throws Exception {
    channel.setDefaultConsumer(delegate.getDefaultConsumer());
    if (flowDisabled)
      channel.flow(false);
    if (basicQos != null)
      basicQos.invoke(channel);
    if (confirmSelect)
      channel.confirmSelect();
    if (txSelect)
      channel.txSelect();
    synchronized (shutdownListeners) {
      for (ShutdownListener listener : shutdownListeners)
        channel.addShutdownListener(listener);
    }
    for (ConfirmListener listener : confirmListeners)
      channel.addConfirmListener(listener);
    for (FlowListener listener : flowListeners)
      channel.addFlowListener(listener);
    for (ReturnListener listener : returnListeners)
      channel.addReturnListener(listener);
  }

  private void notifyAfterConsumerRecovery(Consumer consumer) {
    for (ConsumerListener listener : config.getConsumerListeners())
      try {
        listener.onAfterRecovery(consumer, proxy);
      } catch (Exception ignore) {
      }
  }

  private void notifyBeforeConsumerRecovery(Consumer consumer) {
    for (ConsumerListener listener : config.getConsumerListeners())
      try {
        listener.onBeforeRecovery(consumer, proxy);
      } catch (Exception ignore) {
      }
  }

  private void notifyConsumerRecovery() {
    for (ChannelListener listener : config.getChannelListeners())
      try {
        listener.onConsumerRecovery(proxy);
      } catch (Exception ignore) {
      }
  }

  private void notifyConsumerRecoveryFailure(Consumer consumer, Exception e) {
    for (ConsumerListener listener : config.getConsumerListeners())
      try {
        listener.onRecoveryFailure(consumer, proxy, e);
      } catch (Exception ignore) {
      }
  }

  private void notifyRecovery() {
    for (ChannelListener listener : config.getChannelListeners())
      try {
        if (!recoveryPending.get())
          listener.onRecovery(proxy);
      } catch (Exception ignore) {
      }
  }

  /**
   * Recovers the channel's consumers along with any exchanges, exchange bindings, queues and queue
   * bindings that are referenced by the consumer. If a consumer recovery fails due to a channel
   * closure, then we will not attempt to recover that consumer or its references again.
   * 
   * @param recoverReferences whether consumer references should be recovered
   * @throws Exception when recovery fails due to a resource closure
   */
  private void recoverConsumers(boolean recoverReferences) throws Exception {
    if (config.isConsumerRecoveryEnabled() && !recoveryPending.get() && recoveryConsumers != null) {
      Set<QueueDeclaration> recoveredQueues = new HashSet<QueueDeclaration>();
      Set<String> recoveredExchanges = new HashSet<String>();

      for (Iterator<Map.Entry<String, ConsumerDeclaration>> it = recoveryConsumers.entrySet()
          .iterator(); it.hasNext();) {
        Map.Entry<String, ConsumerDeclaration> entry = it.next();
        ConsumerDeclaration consumerDeclaration = entry.getValue();
        Object[] args = consumerDeclaration.args;
        ConsumerDelegate consumer = (ConsumerDelegate) args[args.length - 1];
        String queueName = consumerDeclaration.queueDeclaration != null ? consumerDeclaration.queueDeclaration.name
            : (String) args[0];

        try {
          // Recover referenced exchanges, queues and bindings
          if (recoverReferences) {
            List<Binding> queueBindings = connectionHandler.queueBindings.get(queueName);
            recoverRelatedExchanges(recoveredExchanges, queueBindings);
            if (consumerDeclaration.queueDeclaration != null
                && recoveredQueues.add(consumerDeclaration.queueDeclaration))
              queueName = recoverQueue(consumerDeclaration.queueDeclaration, queueName,
                  queueBindings);
          }

          // Recover consumer
          notifyBeforeConsumerRecovery(consumer);
          log.info(queueName == "" ? "Recovering consumer-"+ entry.getKey() +" via "+ this
              : "Recovering consumer-"+ entry.getKey() +" of "+ queueName +" via "+ this);
          consumer.open();
          consumerDeclaration.invoke(delegate);
          notifyAfterConsumerRecovery(consumer);
        } catch (Exception e) {
          ShutdownSignalException sse = Exceptions.extractCause(e, ShutdownSignalException.class);
          log.log(Level.SEVERE, "Failed to recover consumer-"+ entry.getKey() +" via {}"+ this, e);
          notifyConsumerRecoveryFailure(consumer, e);
          if (sse != null) {
            if (!Exceptions.isConnectionClosure(sse))
              it.remove();
            throw e;
          }
        }
      }

      notifyConsumerRecovery();
    }
  }

  /** Recovers the {@code queueName} along with its {@code queueBindings}. */
  private String recoverQueue(QueueDeclaration queueDeclaration, String queueName,
      List<Binding> queueBindings) throws Exception {
    String newQueueName = null;

    if (config.isQueueRecoveryEnabled()) {
      if (queueDeclaration != null) {
        newQueueName = ((Queue.DeclareOk) queueDeclaration.invoke(delegate)).getQueue();
        if (queueName.equals(newQueueName))
          log.info("Recovered queue "+ queueName +" via "+ this);
        else {
          // Update dependencies for new queue names
          log.info("Recovered queue "+ queueName +" as "+ newQueueName +" via "+ this);
          queueDeclaration.name = newQueueName;
          connectionHandler.queueDeclarations.remove(queueName);
          connectionHandler.queueDeclarations.put(newQueueName, queueDeclaration);
          connectionHandler.updateQueueBindingReferences(queueName, newQueueName);
        }
      }

      if (queueBindings != null)
        synchronized (queueBindings) {
          for (Binding qb : queueBindings) {
            log.info("Recovering queue binding from "+ qb.source +" to "+ qb.destination +" with "+ qb.routingKey +" via "+ this);
            delegate.queueBind(qb.destination, qb.source, qb.routingKey, qb.arguments);
          }
        }
    }

    return newQueueName == null ? queueName : newQueueName;
  }

  /**
   * Recovers exchanges and bindings related to the {@code queueBindings} that are not present in
   * {@code recoveredExchanges}, adding recovered exchanges to the {@code recoveredExchanges}.
   */
  private void recoverRelatedExchanges(Set<String> recoveredExchanges, List<Binding> queueBindings)
      throws Exception {
    if (config.isExchangeRecoveryEnabled() && queueBindings != null)
      synchronized (queueBindings) {
        for (Binding queueBinding : queueBindings) {
          String exchangeName = queueBinding.source;
          if (recoveredExchanges.add(exchangeName)) {
            ResourceDeclaration exchangeDeclaration = connectionHandler.exchangeDeclarations.get(exchangeName);
            if (exchangeDeclaration != null) {
              log.info("Recovering exchange "+ exchangeName +" via "+ this);
              exchangeDeclaration.invoke(delegate);
            }

            List<Binding> exchangeBindings = connectionHandler.exchangeBindings.get(exchangeName);
            if (exchangeBindings != null)
              synchronized (exchangeBindings) {
                for (Binding eb : exchangeBindings) {
                  log.info("Recovering exchange binding from "+ eb.source +" to "+ eb.destination +" with "+ eb.routingKey +" via "+ this);
                  delegate.exchangeBind(eb.destination, eb.source, eb.routingKey, eb.arguments);
                }
              }
          }
        }
      }
  }

  private void recoveryComplete() {
    recoveryStats = null;
    recoveryConsumers = null;
    lastShutdownSignal = null;
  }

  private void recoveryFailed(Exception e) {
    log.log(Level.SEVERE, "Failed to recover "+ this, e);
    recoveryComplete();
    interruptWaiters();
    for (ChannelListener listener : config.getChannelListeners())
      try {
        listener.onRecoveryFailure(proxy, e);
      } catch (Exception ignore) {
      }
  }

  private void recoverySucceeded() {
    if (!recoveryPending.get()) {
      recoveryComplete();
      circuit.close();
    }
  }
}
