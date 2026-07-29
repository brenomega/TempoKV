package io.tempokv.server;

import io.tempokv.application.CommandDispatcher;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.security.AccessController;
import io.tempokv.security.Authenticator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.Objects;

/** Exposes RESP over TCP without constructing application or storage components. */
public final class RespServer implements AutoCloseable {
    private final String bindAddress;
    private final int port;
    private final MetricsRegistry metrics;
    private final CommandDispatcher dispatcher;
    private final Authenticator authenticator;
    private final AccessController accessController;
    private final ConnectionLimiter connections;
    private final int maxArrayElements;
    private final int maxCommandBytes;
    private final int maxUsernameBytes;
    private final int maxCredentialBytes;
    private ServerSocketChannel socket;
    private NioEventLoop eventLoop;

    /** Creates an endpoint over an already-composed dispatcher using explicit open security. */
    public RespServer(
            int port, MetricsRegistry metrics, CommandDispatcher dispatcher) {
        this(
                "127.0.0.1",
                port,
                metrics,
                dispatcher,
                Authenticator.permissive(),
                AccessController.permissive(),
                4_096,
                1_024,
                16 * 1_048_576,
                128,
                4_096);
    }

    /** Creates an endpoint over explicit application and security dependencies. */
    public RespServer(
            int port,
            MetricsRegistry metrics,
            CommandDispatcher dispatcher,
            Authenticator authenticator,
            AccessController accessController) {
        this(
                "127.0.0.1",
                port,
                metrics,
                dispatcher,
                authenticator,
                accessController,
                4_096,
                1_024,
                16 * 1_048_576,
                128,
                4_096);
    }

    /** Creates an endpoint with explicit bind and defensive limits. */
    public RespServer(
            String bindAddress,
            int port,
            MetricsRegistry metrics,
            CommandDispatcher dispatcher,
            Authenticator authenticator,
            AccessController accessController,
            int maxConnections,
            int maxArrayElements,
            int maxCommandBytes,
            int maxUsernameBytes,
            int maxCredentialBytes) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.port = port;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.accessController = Objects.requireNonNull(accessController, "accessController");
        this.connections = new ConnectionLimiter(maxConnections);
        this.maxArrayElements = maxArrayElements;
        this.maxCommandBytes = maxCommandBytes;
        this.maxUsernameBytes = maxUsernameBytes;
        this.maxCredentialBytes = maxCredentialBytes;
    }

    /** Binds the socket and starts serving RESP clients. */
    public synchronized void start() throws IOException {
        if (eventLoop != null) return;
        socket = ServerSocketChannel.open();
        try {
            socket.bind(new InetSocketAddress(bindAddress, port));
            eventLoop = new NioEventLoop(
                    ignored -> metrics.incrementCounter(
                            "resp.event_loop_failures"));
            eventLoop.start(socket, channel -> {
                if (!connections.tryAcquire()) {
                    metrics.incrementCounter("resp.connection_rejections");
                    throw new IOException("RESP connection limit reached");
                }
                metrics.incrementCounter("resp.connections");
                metrics.setGauge(
                        "resp.connections_active", connections.active());
                try {
                    return new ClientConnection(
                            channel,
                            new RespConnectionHandler(
                                    authenticator,
                                    accessController,
                                    dispatcher,
                                    metrics,
                                    maxArrayElements,
                                    maxCommandBytes,
                                    maxUsernameBytes,
                                    maxCredentialBytes),
                            () -> metrics.setGauge(
                                    "resp.connections_active",
                                    connections.release()));
                } catch (RuntimeException failure) {
                    metrics.setGauge(
                            "resp.connections_active", connections.release());
                    throw failure;
                }
            });
        } catch (IOException | RuntimeException exception) {
            close();
            throw exception;
        }
    }

    /** Returns the TCP port actually bound by this endpoint. */
    public synchronized int port() throws IOException {
        if (socket == null) throw new IOException("RESP server is not started");
        return ((InetSocketAddress) socket.getLocalAddress()).getPort();
    }

    /** Returns whether the endpoint is accepting connections. */
    public synchronized boolean isRunning() {
        return eventLoop != null && eventLoop.isRunning();
    }

    /** Stops the selector and releases the listening socket. */
    @Override public synchronized void close() throws IOException {
        IOException failure = null;
        if (eventLoop != null) {
            try {
                eventLoop.close();
            } catch (IOException exception) {
                failure = exception;
            }
        } else if (socket != null) {
            try {
                socket.close();
            } catch (IOException exception) {
                failure = exception;
            }
        }
        eventLoop = null;
        socket = null;
        if (failure != null) throw failure;
    }
}
