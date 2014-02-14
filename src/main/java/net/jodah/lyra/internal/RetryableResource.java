package net.jodah.lyra.internal;

import static net.jodah.lyra.internal.util.Exceptions.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.jodah.lyra.internal.util.Collections;
import net.jodah.lyra.internal.util.Exceptions;
import net.jodah.lyra.internal.util.Reflection;
import net.jodah.lyra.internal.util.concurrent.InterruptableWaiter;
import net.jodah.lyra.internal.util.concurrent.ReentrantCircuit;
import net.jodah.lyra.util.Duration;

import com.rabbitmq.client.AMQP.Queue;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;

/**
 * A resource which supports invocation retries and failure recovery.
 * 
 * @author Jonathan Halterman
 */
abstract class RetryableResource {
  final Logger log = Logger.getLogger(getClass().getCanonicalName());
  final ReentrantCircuit circuit = new ReentrantCircuit();
  final InterruptableWaiter retryWaiter = new InterruptableWaiter();
  final List<ShutdownListener> shutdownListeners = Collections.synchronizedList();
  volatile boolean closed;

  void afterClosure() {
  }

  /**
   * Calls the {@code callable} with retries, throwing a failure if retries are exhausted.
   */
  <T> T callWithRetries(Callable<T> callable, RecurringPolicy<?> recurringPolicy,
      RecurringStats retryStats, boolean recoverable, boolean logFailures) throws Exception {
    boolean recovery = retryStats != null;

    while (true) {
      try {
        return callable.call();
      } catch (Exception e) {
        ShutdownSignalException sse = extractCause(e, ShutdownSignalException.class);
        if (sse == null && logFailures && recurringPolicy != null
            && recurringPolicy.allowsAttempts())
            log.log(Level.SEVERE, "Invocation of "+ callable +" failed.", e);

        if (sse != null && (recovery || !recoverable))
          throw e;

        if (!closed) {
          try {
            // Retry on channel recovery failure or retryable exception
            boolean retryable = recurringPolicy != null && recurringPolicy.allowsAttempts()
                && isRetryable(e, sse);
            long startTime = System.nanoTime();

            if (retryable) {
              // Wait for pending recovery
              if (sse != null) {
                if (recurringPolicy.getMaxDuration() == null)
                  circuit.await();
                else if (!circuit.await(retryStats.getMaxWaitTime())) {
                    log.fine("Exceeded max wait time while waiting for "+ this +" to recover");
                  throw e;
                }
              }

              // Continue retries
              if (retryStats == null)
                retryStats = new RecurringStats(recurringPolicy);
              retryStats.incrementAttempts();
              if (!retryStats.isPolicyExceeded()) {
                long remainingWaitTime = retryStats.getWaitTime().toNanos()
                    - (System.nanoTime() - startTime);
                if (remainingWaitTime > 0)
                  retryWaiter.await(Duration.nanos(remainingWaitTime));
                continue;
              }
            }
          } catch (Throwable ignore) {
          }
        }

        throw e;
      }
    }
  }

  /**
   * Handles common method invocations.
   */
  boolean handleCommonMethods(Object delegate, Method method, Object[] args) throws Throwable {
    if ("abort".equals(method.getName()) || "close".equals(method.getName())) {
      try {
        Reflection.invoke(delegate, method, args);
        return true;
      } finally {
        closed = true;
        afterClosure();
        interruptWaiters();
      }
    } else if ("addShutdownListener".equals(method.getName()) && args[0] != null)
      shutdownListeners.add((ShutdownListener) args[0]);
    else if ("removeShutdownListener".equals(method.getName()) && args[0] != null)
      shutdownListeners.remove((ShutdownListener) args[0]);
    return false;
  }

  void interruptWaiters() {
    circuit.interruptWaiters();
    retryWaiter.interruptWaiters();
  }

  /** Returns the channel to use for recovery. */
  abstract Channel getRecoveryChannel() throws IOException;

  /** Whether a failure on recovery should always result in a throw. */
  abstract boolean throwOnRecoveryFailure();

  /** Recovers an exchange using the {@code channelSupplier}. */
  void recoverExchange(String exchangeName, ResourceDeclaration exchangeDeclaration)
      throws Exception {
    try {
      log.info("Recovering exchange "+ exchangeName +" via "+ this);
      exchangeDeclaration.invoke(getRecoveryChannel());
    } catch (Exception e) {
      log.log(Level.SEVERE, "Failed to recover exchange "+ exchangeName +" via "+ this, e);
      if (throwOnRecoveryFailure() || Exceptions.isCausedByConnectionClosure(e))
        throw e;
    }
  }

  /** Recover exchange bindings using the {@code channelSupplier}. */
  void recoverExchangeBindings(Iterable<Binding> exchangeBindings) throws Exception {
    if (exchangeBindings != null)
      synchronized (exchangeBindings) {
        for (Binding binding : exchangeBindings)
          try {
            log.info("Recovering exchange binding from "+ binding.source +" to "+ binding.destination 
            		+" with "+ binding.routingKey +" via "+ this);
            getRecoveryChannel().exchangeBind(binding.destination, binding.source,
                binding.routingKey, binding.arguments);
          } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to recover exchange binding from "+ binding.source +" to "+ binding.destination 
            		+" with "+ binding.routingKey +" via "+ this, e);
            if (throwOnRecoveryFailure() || Exceptions.isCausedByConnectionClosure(e))
              throw e;
          }
      }
  }

  /** Recovers a queue using the {@code channelSupplier}, returning the recovered queue's name. */
  String recoverQueue(String queueName, QueueDeclaration queueDeclaration) throws Exception {
    try {
      String newQueueName = ((Queue.DeclareOk) queueDeclaration.invoke(getRecoveryChannel())).getQueue();
      if (queueName.equals(newQueueName))
        log.info("Recovered queue "+ queueName +" via "+ this);
      else {
        log.info("Recovered queue "+ queueName +" as "+ newQueueName +" via "+ this);
        queueDeclaration.name = newQueueName;
      }

      return newQueueName;
    } catch (Exception e) {
      log.log(Level.SEVERE, "Failed to recover queue "+ queueName +" via "+ this, e);
      if (throwOnRecoveryFailure() || Exceptions.isCausedByConnectionClosure(e))
        throw e;
      return queueName;
    }
  }

  /** Recovers queue bindings using the {@code channelSupplier}. */
  void recoverQueueBindings(Iterable<Binding> queueBindings) throws Exception {
    if (queueBindings != null)
      synchronized (queueBindings) {
        for (Binding binding : queueBindings)
          try {
            log.info("Recovering queue binding from "+ binding.source +" to "+ binding.destination 
            		+" with "+ binding.routingKey +" via "+ this);
            getRecoveryChannel().queueBind(binding.destination, binding.source, binding.routingKey,
                binding.arguments);
          } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to recover queue binding from "+ binding.source +" to "+ binding.destination 
            		+" with "+ binding.routingKey +" via "+ this, e);
            if (throwOnRecoveryFailure() || Exceptions.isCausedByConnectionClosure(e))
              throw e;
          }
      }
  }
}
