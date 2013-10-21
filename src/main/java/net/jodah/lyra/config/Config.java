package net.jodah.lyra.config;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import net.jodah.lyra.Connections;
import net.jodah.lyra.event.ChannelListener;
import net.jodah.lyra.event.ConnectionListener;
import net.jodah.lyra.event.ConsumerListener;
import net.jodah.lyra.retry.RetryPolicy;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

/**
 * Lyra configuration. Changes are reflected in the resources created with this configuration.
 * 
 * @author Jonathan Halterman
 */
public class Config implements ConnectionConfig {
  private final Config parent;
  private RetryPolicy retryPolicy;
  private RetryPolicy recoveryPolicy;
  private RetryPolicy connectRetryPolicy;
  private RetryPolicy connectionRecoveryPolicy;
  private RetryPolicy connectionRetryPolicy;
  private RetryPolicy channelRecoveryPolicy;
  private RetryPolicy channelRetryPolicy;
  private Collection<ConnectionListener> connectionListeners = Collections.emptyList();
  private Collection<ChannelListener> channelListeners = Collections.emptyList();
  private Collection<ConsumerListener> consumerListeners = Collections.emptyList();

  public Config() {
    parent = null;
  }

  /**
   * Creates a new Config object that inherits configuration from the {@code parent}.
   */
  public Config(Config parent) {
    this.parent = parent;
  }

  @Override
  public Collection<ChannelListener> getChannelListeners() {
    return channelListeners != null ? channelListeners
        : parent != null ? parent.getChannelListeners() : null;
  }

  @Override
  public RetryPolicy getChannelRecoveryPolicy() {
    RetryPolicy result = channelRecoveryPolicy == null ? recoveryPolicy : channelRecoveryPolicy;
    return result != null ? result : parent != null ? parent.getChannelRecoveryPolicy() : null;
  }

  @Override
  public RetryPolicy getChannelRetryPolicy() {
    RetryPolicy result = channelRetryPolicy == null ? retryPolicy : channelRetryPolicy;
    return result != null ? result : parent != null ? parent.getChannelRetryPolicy() : null;
  }

  @Override
  public Collection<ConnectionListener> getConnectionListeners() {
    return connectionListeners != null ? connectionListeners
        : parent != null ? parent.getConnectionListeners() : null;
  }

  @Override
  public RetryPolicy getConnectionRecoveryPolicy() {
    RetryPolicy result = connectionRecoveryPolicy == null ? recoveryPolicy
        : connectionRecoveryPolicy;
    return result != null ? result : parent != null ? parent.getConnectionRecoveryPolicy() : null;
  }

  @Override
  public RetryPolicy getConnectionRetryPolicy() {
    RetryPolicy result = connectionRetryPolicy == null ? retryPolicy : connectionRetryPolicy;
    return result != null ? result : parent != null ? parent.getConnectionRetryPolicy() : null;
  }

  /**
   * Sets the policy to use for handling {@link Connections#create(Options, Config) connection
   * attempt} errors. Overrides the {@link #withRetryPolicy(RetryPolicy) global retry policy}.
   */
  public RetryPolicy getConnectRetryPolicy() {
    RetryPolicy result = connectRetryPolicy == null ? retryPolicy : connectRetryPolicy;
    return result != null ? result : parent != null ? parent.getConnectRetryPolicy() : null;
  }

  @Override
  public Collection<ConsumerListener> getConsumerListeners() {
    return consumerListeners != null ? consumerListeners
        : parent != null ? parent.getConsumerListeners() : null;
  }

  @Override
  public Config withChannelListeners(ChannelListener... channelListeners) {
    this.channelListeners = Arrays.asList(channelListeners);
    return this;
  }

  @Override
  public Config withChannelRecoveryPolicy(RetryPolicy channelRecoveryPolicy) {
    this.channelRecoveryPolicy = channelRecoveryPolicy;
    return this;
  }

  @Override
  public Config withChannelRetryPolicy(RetryPolicy channelRetryPolicy) {
    this.channelRetryPolicy = channelRetryPolicy;
    return this;
  }

  @Override
  public Config withConnectionListeners(ConnectionListener... connectionListeners) {
    this.connectionListeners = Arrays.asList(connectionListeners);
    return this;
  }

  @Override
  public Config withConnectionRecoveryPolicy(RetryPolicy connectionRecoveryPolicy) {
    this.connectionRecoveryPolicy = connectionRecoveryPolicy;
    return this;
  }

  @Override
  public Config withConnectionRetryPolicy(RetryPolicy connectionRetryPolicy) {
    this.connectionRetryPolicy = connectionRetryPolicy;
    return this;
  }

  /**
   * Sets the policy to use for handling {@link Connections#create(Options, Config) connection
   * attempt} errors. Overrides the {@link #withRetryPolicy(RetryPolicy) global retry policy}.
   */
  public Config withConnectRetryPolicy(RetryPolicy connectRetryPolicy) {
    this.connectRetryPolicy = connectRetryPolicy;
    return this;
  }

  @Override
  public Config withConsumerListeners(ConsumerListener... consumerListeners) {
    this.consumerListeners = Arrays.asList(consumerListeners);
    return this;
  }

  /**
   * Sets the policy to use for the recovery of Connections/Channels/Consumers after an unexpected
   * Connection/Channel closure. Can be overridden with specific policies via
   * {@link #withConnectionRecoveryPolicy(RetryPolicy)} and
   * {@link #withChannelRecoveryPolicy(RetryPolicy)}.
   */
  public Config withRecoveryPolicy(RetryPolicy recoveryPolicy) {
    this.recoveryPolicy = recoveryPolicy;
    return this;
  }

  /**
   * Sets the policy to use for handling {@link Connections#create(Options) connection attempt},
   * {@link Connection} invocation, and {@link Channel} invocation errors. Can be overridden with
   * specific policies via {@link #withConnectRetryPolicy(RetryPolicy)},
   * {@link #withConnectionRetryPolicy(RetryPolicy)}, and
   * {@link #withChannelRetryPolicy(RetryPolicy)}.
   */
  public Config withRetryPolicy(RetryPolicy retryPolicy) {
    this.retryPolicy = retryPolicy;
    return this;
  }
}