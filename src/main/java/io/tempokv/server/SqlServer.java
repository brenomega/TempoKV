package io.tempokv.server;

import io.tempokv.application.CommandDispatcher;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.protocol.sql.PlanExecutor;
import io.tempokv.protocol.sql.SqlCompiler;
import io.tempokv.protocol.sql.SqlResultEncoder;
import io.tempokv.security.AccessController;
import io.tempokv.security.Authenticator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes the bounded TempoKV SQL language over a dedicated non-blocking textual TCP endpoint.
 */
public final class SqlServer implements AutoCloseable {
    private final int port;
    private final MetricsRegistry metrics;
    private final CommandDispatcher dispatcher;
    private final Authenticator authenticator;
    private final AccessController accessController;
    private final AtomicLong activeConnections = new AtomicLong();
    private ServerSocketChannel socket;
    private NioEventLoop eventLoop;

    /** Creates an endpoint over the shared dispatcher using permissive E6 security. */
    public SqlServer(
            int port,
            MetricsRegistry metrics,
            CommandDispatcher dispatcher) {
        this(
                port,
                metrics,
                dispatcher,
                Authenticator.permissive(),
                AccessController.permissive());
    }

    /** Creates an endpoint with explicit application and security dependencies. */
    public SqlServer(
            int port,
            MetricsRegistry metrics,
            CommandDispatcher dispatcher,
            Authenticator authenticator,
            AccessController accessController) {
        this.port = port;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.authenticator =
                Objects.requireNonNull(authenticator, "authenticator");
        this.accessController =
                Objects.requireNonNull(accessController, "accessController");
    }

    /** Binds the configured socket and begins serving semicolon-terminated SQL statements. */
    public synchronized void start() throws IOException {
        if (eventLoop != null) {
            return;
        }
        socket = ServerSocketChannel.open();
        try {
            socket.bind(new InetSocketAddress(port));
            eventLoop = new NioEventLoop(
                    ignored -> metrics.incrementCounter(
                            "sql.event_loop_failures"));
            PlanExecutor executor =
                    new PlanExecutor(dispatcher, accessController);
            eventLoop.start(socket, channel -> {
                metrics.incrementCounter("sql.connections");
                metrics.setGauge(
                        "sql.connections_active",
                        activeConnections.incrementAndGet());
                return new ClientConnection(
                        channel,
                        new SqlConnectionHandler(
                                authenticator,
                                new SqlCompiler(),
                                executor,
                                new SqlResultEncoder(),
                                metrics),
                        () -> metrics.setGauge(
                                "sql.connections_active",
                                activeConnections.decrementAndGet()));
            });
        } catch (IOException | RuntimeException exception) {
            close();
            throw exception;
        }
    }

    /** Returns the TCP port actually bound by this endpoint. */
    public synchronized int port() throws IOException {
        if (socket == null) {
            throw new IOException("SQL server is not started");
        }
        return ((InetSocketAddress) socket.getLocalAddress()).getPort();
    }

    /** Returns whether the SQL endpoint is accepting and processing connections. */
    public synchronized boolean isRunning() {
        return eventLoop != null && eventLoop.isRunning();
    }

    /** Stops the SQL event loop and releases its listening socket. */
    @Override
    public synchronized void close() throws IOException {
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
        if (failure != null) {
            throw failure;
        }
    }
}
