/*
 * Copyright 2015-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.cluster.messaging.impl;

import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.MoreExecutors;
import io.atomix.cluster.messaging.ManagedMessagingService;
import io.atomix.cluster.messaging.MessagingConfig;
import io.atomix.cluster.messaging.MessagingException;
import io.atomix.cluster.messaging.MessagingService;
import io.atomix.utils.AtomixRuntimeException;
import io.atomix.utils.net.Address;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.SynchronizedDescriptiveStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.atomix.utils.concurrent.Threads.namedThreads;

/**
 * Netty based MessagingService.
 */
public class NettyMessagingService implements ManagedMessagingService {
  private static final long HISTORY_EXPIRE_MILLIS = Duration.ofMinutes(1).toMillis();
  private static final long MIN_TIMEOUT_MILLIS = 100;
  private static final long MAX_TIMEOUT_MILLIS = 5000;
  private static final long TIMEOUT_INTERVAL = 50;
  private static final int WINDOW_SIZE = 10;
  private static final int WINDOW_UPDATE_SAMPLE_SIZE = 100;
  private static final long WINDOW_UPDATE_MILLIS = 60000;
  private static final int MIN_SAMPLES = 25;
  private static final double PHI_FACTOR = 1.0 / Math.log(10.0);
  private static final int PHI_FAILURE_THRESHOLD = 12;
  private static final int CHANNEL_POOL_SIZE = 8;

  private static final byte[] EMPTY_PAYLOAD = new byte[0];

  private final Logger log = LoggerFactory.getLogger(getClass());

  private final LocalClientConnection localClientConnection = new LocalClientConnection();
  private final LocalServerConnection localServerConnection = new LocalServerConnection(null);

  private final Address returnAddress;
  private final int preamble;
  private final MessagingConfig config;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final Map<String, BiConsumer<ProtocolRequest, ServerConnection>> handlers = new ConcurrentHashMap<>();
  private final Map<Channel, RemoteClientConnection> clientConnections = Maps.newConcurrentMap();
  private final Map<Channel, RemoteServerConnection> serverConnections = Maps.newConcurrentMap();
  private final AtomicLong messageIdGenerator = new AtomicLong(0);

  private ScheduledFuture<?> timeoutFuture;

  private final Map<Address, List<CompletableFuture<Channel>>> channels = Maps.newConcurrentMap();

  private EventLoopGroup serverGroup;
  private EventLoopGroup clientGroup;
  private Class<? extends ServerChannel> serverChannelClass;
  private Class<? extends Channel> clientChannelClass;
  private ScheduledExecutorService timeoutExecutor;
  private Channel serverChannel;

  protected boolean enableNettyTls;

  protected TrustManagerFactory trustManager;
  protected KeyManagerFactory keyManager;

  public NettyMessagingService(String cluster, Address address, MessagingConfig config) {
    this.preamble = cluster.hashCode();
    this.returnAddress = address;
    this.config = config;
  }

  @Override
  public Address address() {
    return returnAddress;
  }

  @Override
  public CompletableFuture<MessagingService> start() {
    if (started.get()) {
      log.warn("Already running at local address: {}", returnAddress);
      return CompletableFuture.completedFuture(this);
    }

    enableNettyTls = loadKeyStores();
    initEventLoopGroup();
    return bootstrapServer().thenRun(() -> {
      timeoutExecutor = Executors.newSingleThreadScheduledExecutor(
          namedThreads("netty-messaging-timeout-%d", log));
      timeoutFuture = timeoutExecutor.scheduleAtFixedRate(
          this::timeoutAllCallbacks, TIMEOUT_INTERVAL, TIMEOUT_INTERVAL, TimeUnit.MILLISECONDS);
      started.set(true);
      log.info("Started");
    }).thenApply(v -> this);
  }

  @Override
  public boolean isRunning() {
    return started.get();
  }

  private boolean loadKeyStores() {
    if (!config.getTlsConfig().isEnabled()) {
      return false;
    }

    // Maintain a local copy of the trust and key managers in case anything goes wrong
    TrustManagerFactory tmf;
    KeyManagerFactory kmf;
    try {
      String ksLocation = config.getTlsConfig().getKeyStore();
      String tsLocation = config.getTlsConfig().getTrustStore();
      char[] ksPwd = config.getTlsConfig().getKeyStorePassword().toCharArray();
      char[] tsPwd = config.getTlsConfig().getTrustStorePassword().toCharArray();

      tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
      try (FileInputStream fileInputStream = new FileInputStream(tsLocation)) {
        ts.load(fileInputStream, tsPwd);
      }
      tmf.init(ts);

      kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
      try (FileInputStream fileInputStream = new FileInputStream(ksLocation)) {
        ks.load(fileInputStream, ksPwd);
      }
      kmf.init(ks, ksPwd);
      if (log.isInfoEnabled()) {
        logKeyStore(ks, ksLocation, ksPwd);
      }
    } catch (FileNotFoundException e) {
      throw new AtomixRuntimeException("Could not load cluster keystore: {}", e.getMessage());
    } catch (Exception e) {
      throw new AtomixRuntimeException("Error loading cluster keystore", e);
    }
    this.trustManager = tmf;
    this.keyManager = kmf;
    return true;
  }

  private void logKeyStore(KeyStore ks, String ksLocation, char[] ksPwd) {
    if (log.isInfoEnabled()) {
      log.info("Loaded cluster key store from: {}", ksLocation);
      try {
        for (Enumeration<String> e = ks.aliases(); e.hasMoreElements(); ) {
          String alias = e.nextElement();
          Key key = ks.getKey(alias, ksPwd);
          Certificate[] certs = ks.getCertificateChain(alias);
          log.debug("{} -> {}", alias, certs);
          final byte[] encodedKey;
          if (certs != null && certs.length > 0) {
            encodedKey = certs[0].getEncoded();
          } else {
            log.info("Could not find cert chain for {}, using fingerprint of key instead...", alias);
            encodedKey = key.getEncoded();
          }
          // Compute the certificate's fingerprint (use the key if certificate cannot be found)
          MessageDigest digest = MessageDigest.getInstance("SHA1");
          digest.update(encodedKey);
          StringJoiner fingerprint = new StringJoiner(":");
          for (byte b : digest.digest()) {
            fingerprint.add(String.format("%02X", b));
          }
          log.info("{} -> {}", alias, fingerprint);
        }
      } catch (Exception e) {
        log.warn("Unable to print contents of key store: {}", ksLocation, e);
      }
    }
  }

  private void initEventLoopGroup() {
    // try Epoll first and if that does work, use nio.
    try {
      clientGroup = new EpollEventLoopGroup(0, namedThreads("netty-messaging-event-epoll-client-%d", log));
      serverGroup = new EpollEventLoopGroup(0, namedThreads("netty-messaging-event-epoll-server-%d", log));
      serverChannelClass = EpollServerSocketChannel.class;
      clientChannelClass = EpollSocketChannel.class;
      return;
    } catch (Throwable e) {
      log.debug("Failed to initialize native (epoll) transport. "
          + "Reason: {}. Proceeding with nio.", e.getMessage());
    }
    clientGroup = new NioEventLoopGroup(0, namedThreads("netty-messaging-event-nio-client-%d", log));
    serverGroup = new NioEventLoopGroup(0, namedThreads("netty-messaging-event-nio-server-%d", log));
    serverChannelClass = NioServerSocketChannel.class;
    clientChannelClass = NioSocketChannel.class;
  }

  /**
   * Times out response callbacks.
   */
  private void timeoutAllCallbacks() {
    // Iterate through all connections and time out callbacks.
    localClientConnection.timeoutCallbacks();
    for (RemoteClientConnection connection : clientConnections.values()) {
      connection.timeoutCallbacks();
    }
  }

  @Override
  public CompletableFuture<Void> sendAsync(Address address, String type, byte[] payload) {
    long messageId = messageIdGenerator.incrementAndGet();
    ProtocolRequest message = new ProtocolRequest(
        messageId,
        returnAddress,
        type,
        payload);
    return executeOnPooledConnection(address, type, c -> c.sendAsync(message), MoreExecutors.directExecutor());
  }

  @Override
  public CompletableFuture<byte[]> sendAndReceive(Address address, String type, byte[] payload) {
    return sendAndReceive(address, type, payload, null, MoreExecutors.directExecutor());
  }

  @Override
  public CompletableFuture<byte[]> sendAndReceive(Address address, String type, byte[] payload, Executor executor) {
    return sendAndReceive(address, type, payload, null, executor);
  }

  @Override
  public CompletableFuture<byte[]> sendAndReceive(Address address, String type, byte[] payload, Duration timeout) {
    return sendAndReceive(address, type, payload, timeout, MoreExecutors.directExecutor());
  }

  @Override
  public CompletableFuture<byte[]> sendAndReceive(Address address, String type, byte[] payload, Duration timeout, Executor executor) {
    long messageId = messageIdGenerator.incrementAndGet();
    ProtocolRequest message = new ProtocolRequest(
        messageId,
        returnAddress,
        type,
        payload);
    return executeOnPooledConnection(address, type, c -> c.sendAndReceive(message, timeout), executor);
  }

  private List<CompletableFuture<Channel>> getChannelPool(Address address) {
    List<CompletableFuture<Channel>> channelPool = channels.get(address);
    if (channelPool != null) {
      return channelPool;
    }
    return channels.computeIfAbsent(address, e -> {
      List<CompletableFuture<Channel>> defaultList = new ArrayList<>(CHANNEL_POOL_SIZE);
      for (int i = 0; i < CHANNEL_POOL_SIZE; i++) {
        defaultList.add(null);
      }
      return Lists.newCopyOnWriteArrayList(defaultList);
    });
  }

  private int getChannelOffset(String messageType) {
    return Math.abs(messageType.hashCode() % CHANNEL_POOL_SIZE);
  }

  private CompletableFuture<Channel> getChannel(Address address, String messageType) {
    List<CompletableFuture<Channel>> channelPool = getChannelPool(address);
    int offset = getChannelOffset(messageType);

    CompletableFuture<Channel> channelFuture = channelPool.get(offset);
    if (channelFuture == null || channelFuture.isCompletedExceptionally()) {
      synchronized (channelPool) {
        channelFuture = channelPool.get(offset);
        if (channelFuture == null || channelFuture.isCompletedExceptionally()) {
          log.debug("Connecting to {}", address);
          channelFuture = openChannel(address);
          channelFuture.whenComplete((channel, error) -> {
            if (error == null) {
              log.debug("Connected to {}", channel.remoteAddress());
            } else {
              log.debug("Failed to connect to {}", channel.remoteAddress(), error);
            }
          });
          channelPool.set(offset, channelFuture);
        }
      }
    }

    final CompletableFuture<Channel> future = new CompletableFuture<>();
    final CompletableFuture<Channel> finalFuture = channelFuture;
    finalFuture.whenComplete((channel, error) -> {
      if (error == null) {
        if (!channel.isActive()) {
          CompletableFuture<Channel> currentFuture;
          synchronized (channelPool) {
            currentFuture = channelPool.get(offset);
            if (currentFuture == finalFuture) {
              channelPool.set(offset, null);
            } else if (currentFuture == null) {
              currentFuture = openChannel(address);
              currentFuture.whenComplete((c, e) -> {
                if (e == null) {
                  log.debug("Connected to {}", channel.remoteAddress());
                } else {
                  log.debug("Failed to connect to {}", channel.remoteAddress(), e);
                }
              });
              channelPool.set(offset, currentFuture);
            }
          }

          final RemoteClientConnection connection = clientConnections.remove(channel);
          if (connection != null) {
            log.debug("Closing connection to {}", connection.channel.remoteAddress());
            connection.close();
          }

          if (currentFuture == finalFuture) {
            getChannel(address, messageType).whenComplete((recursiveResult, recursiveError) -> {
              if (recursiveError == null) {
                future.complete(recursiveResult);
              } else {
                future.completeExceptionally(recursiveError);
              }
            });
          } else {
            currentFuture.whenComplete((recursiveResult, recursiveError) -> {
              if (recursiveError == null) {
                future.complete(recursiveResult);
              } else {
                future.completeExceptionally(recursiveError);
              }
            });
          }
        } else {
          future.complete(channel);
        }
      } else {
        future.completeExceptionally(error);
      }
    });
    return future;
  }

  private CompletableFuture<Channel> openChannel(Address address) {
    return bootstrapClient(address);
  }

  private <T> CompletableFuture<T> executeOnPooledConnection(
      Address address,
      String type,
      Function<ClientConnection, CompletableFuture<T>> callback,
      Executor executor) {
    CompletableFuture<T> future = new CompletableFuture<T>();
    executeOnPooledConnection(address, type, callback, executor, future);
    return future;
  }

  private <T> void executeOnPooledConnection(
      Address address,
      String type,
      Function<ClientConnection, CompletableFuture<T>> callback,
      Executor executor,
      CompletableFuture<T> future) {
    if (address.equals(returnAddress)) {
      callback.apply(localClientConnection).whenComplete((result, error) -> {
        if (error == null) {
          executor.execute(() -> future.complete(result));
        } else {
          executor.execute(() -> future.completeExceptionally(error));
        }
      });
      return;
    }

    getChannel(address, type).whenComplete((channel, channelError) -> {
      if (channelError == null) {
        final ClientConnection connection = getOrCreateRemoteClientConnection(channel);
        callback.apply(connection).whenComplete((result, sendError) -> {
          if (sendError == null) {
            executor.execute(() -> future.complete(result));
          } else {
            final Throwable cause = Throwables.getRootCause(sendError);
            if (!(cause instanceof TimeoutException) && !(cause instanceof MessagingException)) {
              channel.close().addListener(f -> {
                log.debug("Closing connection to {}", channel.remoteAddress());
                connection.close();
                clientConnections.remove(channel);
              });
            }
            executor.execute(() -> future.completeExceptionally(sendError));
          }
        });
      } else {
        executor.execute(() -> future.completeExceptionally(channelError));
      }
    });
  }

  private RemoteClientConnection getOrCreateRemoteClientConnection(Channel channel) {
    RemoteClientConnection connection = clientConnections.get(channel);
    if (connection == null) {
      connection = clientConnections.computeIfAbsent(channel, RemoteClientConnection::new);
    }
    return connection;
  }

  @Override
  public void registerHandler(String type, BiConsumer<Address, byte[]> handler, Executor executor) {
    handlers.put(type, (message, connection) -> executor.execute(() ->
        handler.accept(message.sender(), message.payload())));
  }

  @Override
  public void registerHandler(String type, BiFunction<Address, byte[], byte[]> handler, Executor executor) {
    handlers.put(type, (message, connection) -> executor.execute(() -> {
      byte[] responsePayload = null;
      ProtocolReply.Status status = ProtocolReply.Status.OK;
      try {
        responsePayload = handler.apply(message.sender(), message.payload());
      } catch (Exception e) {
        log.warn("An error occurred in a message handler: {}", e);
        status = ProtocolReply.Status.ERROR_HANDLER_EXCEPTION;
      }
      connection.reply(message, status, Optional.ofNullable(responsePayload));
    }));
  }

  @Override
  public void registerHandler(String type, BiFunction<Address, byte[], CompletableFuture<byte[]>> handler) {
    handlers.put(type, (message, connection) -> {
      handler.apply(message.sender(), message.payload()).whenComplete((result, error) -> {
        ProtocolReply.Status status;
        if (error == null) {
          status = ProtocolReply.Status.OK;
        } else {
          log.warn("An error occurred in a message handler: {}", error);
          status = ProtocolReply.Status.ERROR_HANDLER_EXCEPTION;
        }
        connection.reply(message, status, Optional.ofNullable(result));
      });
    });
  }

  @Override
  public void unregisterHandler(String type) {
    handlers.remove(type);
  }

  private CompletableFuture<Channel> bootstrapClient(Address address) {
    CompletableFuture<Channel> future = new CompletableFuture<>();
    Bootstrap bootstrap = new Bootstrap();
    bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    bootstrap.option(ChannelOption.WRITE_BUFFER_WATER_MARK,
        new WriteBufferWaterMark(10 * 32 * 1024, 10 * 64 * 1024));
    bootstrap.option(ChannelOption.SO_RCVBUF, 1024 * 1024);
    bootstrap.option(ChannelOption.SO_SNDBUF, 1024 * 1024);
    bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
    bootstrap.option(ChannelOption.TCP_NODELAY, true);
    bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000);
    bootstrap.group(clientGroup);
    // TODO: Make this faster:
    // http://normanmaurer.me/presentations/2014-facebook-eng-netty/slides.html#37.0
    bootstrap.channel(clientChannelClass);
    bootstrap.remoteAddress(address.address(true), address.port());
    if (enableNettyTls) {
      bootstrap.handler(new SslClientCommunicationChannelInitializer(future));
    } else {
      bootstrap.handler(new BasicClientChannelInitializer(future));
    }
    bootstrap.connect().addListener(f -> {
      if (!f.isSuccess()) {
        future.completeExceptionally(f.cause());
      }
    });
    return future;
  }

  private CompletableFuture<Void> bootstrapServer() {
    ServerBootstrap b = new ServerBootstrap();
    b.option(ChannelOption.SO_REUSEADDR, true);
    b.option(ChannelOption.SO_BACKLOG, 128);
    b.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
        new WriteBufferWaterMark(8 * 1024, 32 * 1024));
    b.childOption(ChannelOption.SO_RCVBUF, 1024 * 1024);
    b.childOption(ChannelOption.SO_SNDBUF, 1024 * 1024);
    b.childOption(ChannelOption.SO_KEEPALIVE, true);
    b.childOption(ChannelOption.TCP_NODELAY, true);
    b.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    b.group(serverGroup, clientGroup);
    b.channel(serverChannelClass);
    if (enableNettyTls) {
      b.childHandler(new SslServerCommunicationChannelInitializer());
    } else {
      b.childHandler(new BasicServerChannelInitializer());
    }
    return bind(b);
  }

  private CompletableFuture<Void> bind(ServerBootstrap bootstrap) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    int port = config.getPort() != null ? config.getPort() : returnAddress.port();
    if (config.getInterfaces().isEmpty()) {
      bind(bootstrap, Lists.newArrayList("0.0.0.0").iterator(), port, future);
    } else {
      bind(bootstrap, config.getInterfaces().iterator(), port, future);
    }
    return future;
  }

  private void bind(ServerBootstrap bootstrap, Iterator<String> ifaces, int port, CompletableFuture<Void> future) {
    if (ifaces.hasNext()) {
      String iface = ifaces.next();
      bootstrap.bind(iface, port).addListener((ChannelFutureListener) f -> {
        if (f.isSuccess()) {
          log.info("TCP server listening for connections on {}:{}", iface, port);
          serverChannel = f.channel();
          bind(bootstrap, ifaces, port, future);
        } else {
          log.warn("Failed to bind TCP server to port {}:{} due to {}", iface, port, f.cause());
          future.completeExceptionally(f.cause());
        }
      });
    } else {
      future.complete(null);
    }
  }

  @Override
  public CompletableFuture<Void> stop() {
    if (started.compareAndSet(true, false)) {
      return CompletableFuture.supplyAsync(() -> {
        boolean interrupted = false;
        try {
          try {
            serverChannel.close().sync();
          } catch (InterruptedException e) {
            interrupted = true;
          }
          Future<?> serverShutdownFuture = serverGroup.shutdownGracefully();
          Future<?> clientShutdownFuture = clientGroup.shutdownGracefully();
          try {
            serverShutdownFuture.sync();
          } catch (InterruptedException e) {
            interrupted = true;
          }
          try {
            clientShutdownFuture.sync();
          } catch (InterruptedException e) {
            interrupted = true;
          }
          timeoutFuture.cancel(false);
          timeoutExecutor.shutdown();
        } finally {
          log.info("Stopped");
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }
        return null;
      });
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Channel initializer for TLS servers.
   */
  private class SslServerCommunicationChannelInitializer extends ChannelInitializer<SocketChannel> {
    @Override
    protected void initChannel(SocketChannel channel) throws Exception {
      SSLContext serverContext = SSLContext.getInstance("TLS");
      serverContext.init(keyManager.getKeyManagers(), trustManager.getTrustManagers(), null);

      SSLEngine serverSslEngine = serverContext.createSSLEngine();

      serverSslEngine.setNeedClientAuth(true);
      serverSslEngine.setUseClientMode(false);
      serverSslEngine.setEnabledProtocols(serverSslEngine.getSupportedProtocols());
      serverSslEngine.setEnabledCipherSuites(serverSslEngine.getSupportedCipherSuites());
      serverSslEngine.setEnableSessionCreation(true);

      channel.pipeline()
          .addLast("ssl", new io.netty.handler.ssl.SslHandler(serverSslEngine))
          .addLast("handshake", new ServerHandshakeHandlerAdapter());
    }
  }

  /**
   * Channel initializer for TLS clients.
   */
  private class SslClientCommunicationChannelInitializer extends ChannelInitializer<SocketChannel> {
    private final CompletableFuture<Channel> future;

    SslClientCommunicationChannelInitializer(CompletableFuture<Channel> future) {
      this.future = future;
    }

    @Override
    protected void initChannel(SocketChannel channel) throws Exception {
      SSLContext clientContext = SSLContext.getInstance("TLS");
      clientContext.init(keyManager.getKeyManagers(), trustManager.getTrustManagers(), null);

      SSLEngine clientSslEngine = clientContext.createSSLEngine();

      clientSslEngine.setUseClientMode(true);
      clientSslEngine.setEnabledProtocols(clientSslEngine.getSupportedProtocols());
      clientSslEngine.setEnabledCipherSuites(clientSslEngine.getSupportedCipherSuites());
      clientSslEngine.setEnableSessionCreation(true);

      channel.pipeline()
          .addLast("ssl", new io.netty.handler.ssl.SslHandler(clientSslEngine))
          .addLast("handshake", new ClientHandshakeHandlerAdapter(future));
    }
  }

  /**
   * Channel initializer for basic connections.
   */
  private class BasicClientChannelInitializer extends ChannelInitializer<SocketChannel> {
    private final CompletableFuture<Channel> future;

    BasicClientChannelInitializer(CompletableFuture<Channel> future) {
      this.future = future;
    }

    @Override
    protected void initChannel(SocketChannel channel) throws Exception {
      channel.pipeline().addLast("handshake", new ClientHandshakeHandlerAdapter(future));
    }
  }

  /**
   * Channel initializer for basic connections.
   */
  private class BasicServerChannelInitializer extends ChannelInitializer<SocketChannel> {
    @Override
    protected void initChannel(SocketChannel channel) throws Exception {
      channel.pipeline().addLast("handshake", new ServerHandshakeHandlerAdapter());
    }
  }

  /**
   * Base class for handshake handlers.
   */
  private abstract class HandshakeHandlerAdapter extends ChannelInboundHandlerAdapter {
    void writeProtocolVersion(ChannelHandlerContext context, ProtocolVersion version) {
      ByteBuf buffer = context.alloc().buffer(6);
      buffer.writeInt(preamble);
      buffer.writeShort(version.version());
      context.writeAndFlush(buffer);
    }

    Optional<ProtocolVersion> readProtocolVersion(ChannelHandlerContext context, ByteBuf buffer) {
      int preamble = buffer.readInt();
      if (preamble != NettyMessagingService.this.preamble) {
        log.warn("Received invalid handshake, closing connection");
        context.close();
        return Optional.empty();
      }

      int version = buffer.readShort();
      ProtocolVersion protocolVersion = ProtocolVersion.valueOf(version);
      if (protocolVersion == null) {
        context.close();
      }
      return Optional.ofNullable(protocolVersion);
    }

    void activateProtocolVersion(ChannelHandlerContext context, ProtocolVersion protocolVersion) {
      MessagingProtocol protocol = protocolVersion.createProtocol(returnAddress);
      context.pipeline().remove(this);
      context.pipeline().addLast("encoder", protocol.newEncoder());
      context.pipeline().addLast("decoder", protocol.newDecoder());
      context.pipeline().addLast("handler", new InboundMessageDispatcher());
    }
  }

  /**
   * Client-side handshake handler adapter.
   */
  private class ClientHandshakeHandlerAdapter extends HandshakeHandlerAdapter {
    private final CompletableFuture<Channel> future;

    ClientHandshakeHandlerAdapter(CompletableFuture<Channel> future) {
      this.future = future;
    }

    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
      writeProtocolVersion(context, ProtocolVersion.latest());
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
      readProtocolVersion(context, (ByteBuf) message)
          .ifPresent(version -> activateProtocolVersion(context, version));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      future.completeExceptionally(cause);
    }

    @Override
    void activateProtocolVersion(ChannelHandlerContext context, ProtocolVersion protocolVersion) {
      super.activateProtocolVersion(context, protocolVersion);
      future.complete(context.channel());
    }
  }

  /**
   * Server-side handshake handler adapter.
   */
  private class ServerHandshakeHandlerAdapter extends HandshakeHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
      readProtocolVersion(context, (ByteBuf) message)
          .ifPresent(version -> {
            Optional<ProtocolVersion> negotiatedVersion = Stream.of(ProtocolVersion.values())
                .filter(v -> v.version() <= version.version())
                .max(Comparator.comparing(ProtocolVersion::version));
            if (!negotiatedVersion.isPresent()) {
              log.warn("Failed to negotiate version, closing connection");
              context.close();
              return;
            }

            ProtocolVersion protocolVersion = negotiatedVersion.get();
            writeProtocolVersion(context, protocolVersion);
            activateProtocolVersion(context, protocolVersion);
          });
    }
  }

  /**
   * Channel inbound handler that dispatches messages to the appropriate handler.
   */
  private class InboundMessageDispatcher extends SimpleChannelInboundHandler<Object> {
    // Effectively SimpleChannelInboundHandler<InternalMessage>,
    // had to specify <Object> to avoid Class Loader not being able to find some classes.

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object rawMessage) throws Exception {
      ProtocolMessage message = (ProtocolMessage) rawMessage;
      try {
        if (message.isRequest()) {
          RemoteServerConnection connection = serverConnections.get(ctx.channel());
          if (connection == null) {
            connection = serverConnections.computeIfAbsent(ctx.channel(), RemoteServerConnection::new);
          }
          connection.dispatch((ProtocolRequest) message);
        } else {
          RemoteClientConnection connection = getOrCreateRemoteClientConnection(ctx.channel());
          connection.dispatch((ProtocolReply) message);
        }
      } catch (RejectedExecutionException e) {
        log.warn("Unable to dispatch message due to {}", e.getMessage());
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
      log.error("Exception inside channel handling pipeline.", cause);

      RemoteClientConnection clientConnection = clientConnections.remove(context.channel());
      if (clientConnection != null) {
        clientConnection.close();
      }

      RemoteServerConnection serverConnection = serverConnections.remove(context.channel());
      if (serverConnection != null) {
        serverConnection.close();
      }
      context.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
      RemoteClientConnection clientConnection = clientConnections.remove(context.channel());
      if (clientConnection != null) {
        clientConnection.close();
      }

      RemoteServerConnection serverConnection = serverConnections.remove(context.channel());
      if (serverConnection != null) {
        serverConnection.close();
      }
      context.close();
    }

    /**
     * Returns true if the given message should be handled.
     *
     * @param msg inbound message
     * @return true if {@code msg} is {@link ProtocolMessage} instance.
     * @see SimpleChannelInboundHandler#acceptInboundMessage(Object)
     */
    @Override
    public final boolean acceptInboundMessage(Object msg) {
      return msg instanceof ProtocolMessage;
    }
  }

  /**
   * Wraps a {@link CompletableFuture} and tracks its type and creation time.
   */
  private static final class Callback {
    private final String type;
    private final long timeout;
    private final CompletableFuture<byte[]> future;
    private final long time = System.currentTimeMillis();

    Callback(String type, Duration timeout, CompletableFuture<byte[]> future) {
      this.type = type;
      this.timeout = timeout != null ? timeout.toMillis() : 0;
      this.future = future;
    }

    public void complete(byte[] value) {
      future.complete(value);
    }

    public void completeExceptionally(Throwable error) {
      future.completeExceptionally(error);
    }
  }

  /**
   * Represents the client side of a connection to a local or remote server.
   */
  private interface ClientConnection {

    /**
     * Sends a message to the other side of the connection.
     *
     * @param message the message to send
     * @return a completable future to be completed once the message has been sent
     */
    CompletableFuture<Void> sendAsync(ProtocolRequest message);

    /**
     * Sends a message to the other side of the connection, awaiting a reply.
     *
     * @param message the message to send
     * @param timeout the response timeout
     * @return a completable future to be completed once a reply is received or the request times out
     */
    CompletableFuture<byte[]> sendAndReceive(ProtocolRequest message, Duration timeout);

    /**
     * Closes the connection.
     */
    default void close() {
    }
  }

  /**
   * Represents the server side of a connection.
   */
  private interface ServerConnection {

    /**
     * Sends a reply to the other side of the connection.
     *
     * @param message the message to which to reply
     * @param status the reply status
     * @param payload the response payload
     */
    void reply(ProtocolRequest message, ProtocolReply.Status status, Optional<byte[]> payload);

    /**
     * Closes the connection.
     */
    default void close() {
    }
  }

  /**
   * Remote connection implementation.
   */
  private abstract class AbstractClientConnection implements ClientConnection {
    private final Cache<String, RequestMonitor> requestMonitors = CacheBuilder.newBuilder()
        .expireAfterAccess(HISTORY_EXPIRE_MILLIS, TimeUnit.MILLISECONDS)
        .build();
    final Map<Long, Callback> futures = Maps.newConcurrentMap();
    final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Times out callbacks for this connection.
     */
    void timeoutCallbacks() {
      // Store the current time.
      long currentTime = System.currentTimeMillis();

      // Iterate through future callbacks and time out callbacks that have been alive
      // longer than the current timeout according to the message type.
      Iterator<Map.Entry<Long, Callback>> iterator = futures.entrySet().iterator();
      while (iterator.hasNext()) {
        Callback callback = iterator.next().getValue();
        try {
          long elapsedTime = currentTime - callback.time;

          // If a timeout for the callback was provided and the timeout elapsed, timeout the future but don't
          // record the response time.
          if (callback.timeout > 0 && elapsedTime > callback.timeout) {
            iterator.remove();
            callback.completeExceptionally(
                new TimeoutException("Request timed out in " + elapsedTime + " milliseconds"));
          } else {
            // If no timeout was provided, use the RequestMonitor to calculate the dynamic timeout and determine
            // whether to timeout the response future.
            RequestMonitor requestMonitor = requestMonitors.get(callback.type, RequestMonitor::new);
            if (callback.timeout == 0 && (elapsedTime > MAX_TIMEOUT_MILLIS || (elapsedTime > MIN_TIMEOUT_MILLIS && requestMonitor.isTimedOut(elapsedTime)))) {
              iterator.remove();
              requestMonitor.addReplyTime(elapsedTime);
              callback.completeExceptionally(
                  new TimeoutException("Request timed out in " + elapsedTime + " milliseconds"));
            }
          }
        } catch (ExecutionException e) {
          throw new AssertionError();
        }
      }
    }

    protected void registerCallback(long id, String subject, Duration timeout, CompletableFuture<byte[]> future) {
      futures.put(id, new Callback(subject, timeout, future));
    }

    protected Callback completeCallback(long id) {
      Callback callback = futures.remove(id);
      if (callback != null) {
        try {
          RequestMonitor requestMonitor = requestMonitors.get(callback.type, RequestMonitor::new);
          requestMonitor.addReplyTime(System.currentTimeMillis() - callback.time);
        } catch (ExecutionException e) {
          throw new AssertionError();
        }
      }
      return callback;
    }

    protected Callback failCallback(long id) {
      return futures.remove(id);
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        for (Callback callback : futures.values()) {
          callback.completeExceptionally(new ConnectException());
        }
      }
    }
  }

  /**
   * Local connection implementation.
   */
  private final class LocalClientConnection extends AbstractClientConnection {
    @Override
    public CompletableFuture<Void> sendAsync(ProtocolRequest message) {
      BiConsumer<ProtocolRequest, ServerConnection> handler = handlers.get(message.subject());
      if (handler != null) {
        log.trace("{} - Received message type {} from {}", returnAddress, message.subject(), message.sender());
        handler.accept(message, localServerConnection);
      } else {
        log.debug("{} - No handler for message type {} from {}", returnAddress, message.subject(), message.sender());
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<byte[]> sendAndReceive(ProtocolRequest message, Duration timeout) {
      CompletableFuture<byte[]> future = new CompletableFuture<>();
      future.whenComplete((r, e) -> completeCallback(message.id()));
      registerCallback(message.id(), message.subject(), timeout, future);
      BiConsumer<ProtocolRequest, ServerConnection> handler = handlers.get(message.subject());
      if (handler != null) {
        log.trace("{} - Received message type {} from {}", returnAddress, message.subject(), message.sender());
        handler.accept(message, new LocalServerConnection(future));
      } else {
        log.debug("{} - No handler for message type {} from {}", returnAddress, message.subject(), message.sender());
        new LocalServerConnection(future)
            .reply(message, ProtocolReply.Status.ERROR_NO_HANDLER, Optional.empty());
      }
      return future;
    }
  }

  /**
   * Local server connection.
   */
  private static final class LocalServerConnection implements ServerConnection {
    private final CompletableFuture<byte[]> future;

    LocalServerConnection(CompletableFuture<byte[]> future) {
      this.future = future;
    }

    @Override
    public void reply(ProtocolRequest message, ProtocolReply.Status status, Optional<byte[]> payload) {
      if (future != null) {
        if (status == ProtocolReply.Status.OK) {
          future.complete(payload.orElse(EMPTY_PAYLOAD));
        } else if (status == ProtocolReply.Status.ERROR_NO_HANDLER) {
          future.completeExceptionally(new MessagingException.NoRemoteHandler());
        } else if (status == ProtocolReply.Status.ERROR_HANDLER_EXCEPTION) {
          future.completeExceptionally(new MessagingException.RemoteHandlerFailure());
        } else if (status == ProtocolReply.Status.PROTOCOL_EXCEPTION) {
          future.completeExceptionally(new MessagingException.ProtocolException());
        }
      }
    }
  }

  /**
   * Remote connection implementation.
   */
  private final class RemoteClientConnection extends AbstractClientConnection {
    private final Channel channel;

    RemoteClientConnection(Channel channel) {
      this.channel = channel;
    }

    @Override
    public CompletableFuture<Void> sendAsync(ProtocolRequest message) {
      CompletableFuture<Void> future = new CompletableFuture<>();
      channel.writeAndFlush(message).addListener(channelFuture -> {
        if (!channelFuture.isSuccess()) {
          future.completeExceptionally(channelFuture.cause());
        } else {
          future.complete(null);
        }
      });
      return future;
    }

    @Override
    public CompletableFuture<byte[]> sendAndReceive(ProtocolRequest message, Duration timeout) {
      CompletableFuture<byte[]> future = new CompletableFuture<>();
      registerCallback(message.id(), message.subject(), timeout, future);
      channel.writeAndFlush(message).addListener(channelFuture -> {
        if (!channelFuture.isSuccess()) {
          Callback callback = failCallback(message.id());
          if (callback != null) {
            callback.completeExceptionally(channelFuture.cause());
          }
        }
      });
      return future;
    }

    /**
     * Dispatches a message to a local handler.
     *
     * @param message the message to dispatch
     */
    private void dispatch(ProtocolReply message) {
      Callback callback = completeCallback(message.id());
      if (callback != null) {
        if (message.status() == ProtocolReply.Status.OK) {
          callback.complete(message.payload());
        } else if (message.status() == ProtocolReply.Status.ERROR_NO_HANDLER) {
          callback.completeExceptionally(new MessagingException.NoRemoteHandler());
        } else if (message.status() == ProtocolReply.Status.ERROR_HANDLER_EXCEPTION) {
          callback.completeExceptionally(new MessagingException.RemoteHandlerFailure());
        } else if (message.status() == ProtocolReply.Status.PROTOCOL_EXCEPTION) {
          callback.completeExceptionally(new MessagingException.ProtocolException());
        }
      } else {
        log.debug("Received a reply for message id:[{}] "
            + "but was unable to locate the"
            + " request handle", message.id());
      }
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        for (Callback callback : futures.values()) {
          callback.completeExceptionally(new ConnectException());
        }
      }
    }
  }

  /**
   * Remote server connection.
   */
  private final class RemoteServerConnection implements ServerConnection {
    private final Channel channel;

    RemoteServerConnection(Channel channel) {
      this.channel = channel;
    }

    /**
     * Dispatches a message to a local handler.
     *
     * @param message the message to dispatch
     */
    private void dispatch(ProtocolRequest message) {
      BiConsumer<ProtocolRequest, ServerConnection> handler = handlers.get(message.subject());
      if (handler != null) {
        log.trace("{} - Received message type {} from {}", returnAddress, message.subject(), message.sender());
        handler.accept(message, this);
      } else {
        log.debug("{} - No handler for message type {} from {}", returnAddress, message.subject(), message.sender());
        reply(message, ProtocolReply.Status.ERROR_NO_HANDLER, Optional.empty());
      }
    }

    @Override
    public void reply(ProtocolRequest message, ProtocolReply.Status status, Optional<byte[]> payload) {
      ProtocolReply response = new ProtocolReply(
          message.id(),
          payload.orElse(EMPTY_PAYLOAD),
          status);
      channel.writeAndFlush(response, channel.voidPromise());
    }
  }

  /**
   * Request-reply timeout history tracker.
   */
  private static final class RequestMonitor {
    private final DescriptiveStatistics samples = new SynchronizedDescriptiveStatistics(WINDOW_SIZE);
    private final AtomicLong max = new AtomicLong();
    private volatile int replyCount;
    private volatile long lastUpdate = System.currentTimeMillis();

    /**
     * Adds a reply time to the history.
     *
     * @param replyTime the reply time to add to the history
     */
    void addReplyTime(long replyTime) {
      max.accumulateAndGet(replyTime, Math::max);
      replyCount++;

      // If at least WINDOW_UPDATE_SAMPLE_SIZE response times have been recorded, and at least WINDOW_UPDATE_MILLIS
      // have passed since the last update, record the maximum response time in the samples.
      if (replyCount >= WINDOW_UPDATE_SAMPLE_SIZE && System.currentTimeMillis() - lastUpdate > WINDOW_UPDATE_MILLIS) {
        synchronized (this) {
          if (replyCount >= WINDOW_UPDATE_SAMPLE_SIZE && System.currentTimeMillis() - lastUpdate > WINDOW_UPDATE_MILLIS) {
            long lastMax = max.get();
            if (lastMax > 0) {
              samples.addValue(lastMax);
              lastUpdate = System.currentTimeMillis();
              replyCount = 0;
              max.set(0);
            }
          }
        }
      }
    }

    /**
     * Returns a boolean indicating whether the given request should be timed out according to the elapsed time.
     *
     * @param elapsedTime the elapsed request time
     * @return indicates whether the request should be timed out
     */
    boolean isTimedOut(long elapsedTime) {
      return samples.getN() == WINDOW_SIZE && phi(elapsedTime) >= PHI_FAILURE_THRESHOLD;
    }

    /**
     * Compute phi for the specified node id.
     *
     * @param elapsedTime the duration since the request was sent
     * @return phi value
     */
    private double phi(long elapsedTime) {
      if (samples.getN() < MIN_SAMPLES) {
        return 0.0;
      }
      return computePhi(samples, elapsedTime);
    }

    /**
     * Computes the phi value from the given samples.
     *
     * @param samples the samples from which to compute phi
     * @param elapsedTime the duration since the request was sent
     * @return phi
     */
    private double computePhi(DescriptiveStatistics samples, long elapsedTime) {
      return (samples.getN() > 0) ? PHI_FACTOR * elapsedTime / samples.getMean() : 100;
    }
  }
}
