package io.tempokv.server;

import io.tempokv.application.CommandDispatcher;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.security.AccessController;
import io.tempokv.security.Authenticator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Exposes RESP over TCP without constructing application or storage components. */
public final class RespServer implements AutoCloseable {
    private final int port;
    private final MetricsRegistry metrics;
    private final CommandDispatcher dispatcher;
    private final Authenticator authenticator;
    private final AccessController accessController;
    private final AtomicLong activeConnections = new AtomicLong();
    private ServerSocketChannel socket;
    private NioEventLoop eventLoop;

    /** Creates an endpoint over an already-composed dispatcher using permissive E2 security. */
    public RespServer(
            int port, MetricsRegistry metrics, CommandDispatcher dispatcher) {
        this(
                port,
                metrics,
                dispatcher,
                Authenticator.permissive(),
                AccessController.permissive());
    }

    /** Creates an endpoint over explicit application and security dependencies. */
    public RespServer(
            int port,
            MetricsRegistry metrics,
            CommandDispatcher dispatcher,
            Authenticator authenticator,
            AccessController accessController) {
        this.port = port;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.accessController = Objects.requireNonNull(accessController, "accessController");
    }

    /** Binds the socket and starts serving RESP clients. */
    public synchronized void start() throws IOException {
        if (eventLoop != null) return;
        socket = ServerSocketChannel.open();
        try {
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            eventLoop = new NioEventLoop(
                    ignored -> metrics.incrementCounter(
                            "resp.event_loop_failures"));
            eventLoop.start(socket, channel -> {
                metrics.incrementCounter("resp.connections");
                metrics.setGauge(
                        "resp.connections_active", activeConnections.incrementAndGet());
                return new ClientConnection(
                        channel,
                        new RespConnectionHandler(
                                authenticator,
                                accessController,
                                dispatcher,
                                metrics),
                        () -> metrics.setGauge(
                                "resp.connections_active",
                                activeConnections.decrementAndGet()));
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
