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

/**
 * Exposes the bounded TempoKV SQL language over a dedicated non-blocking textual TCP endpoint.
 */
public final class SqlServer implements AutoCloseable {
    private final String bindAddress;
    private final int port;
    private final MetricsRegistry metrics;
    private final CommandDispatcher dispatcher;
    private final Authenticator authenticator;
    private final AccessController accessController;
    private final ConnectionLimiter connections;
    private final int maxCommandBytes;
    private final int maxUsernameBytes;
    private final int maxCredentialBytes;
    private ServerSocketChannel socket;
    private NioEventLoop eventLoop;

    /** Creates an endpoint over the shared dispatcher using explicit open security. */
    public SqlServer(
            int port,
            MetricsRegistry metrics,
            CommandDispatcher dispatcher) {
        this(
                "127.0.0.1",
                port,
                metrics,
                dispatcher,
                Authenticator.permissive(),
                AccessController.permissive(),
                4_096,
                16 * 1_048_576,
                128,
                4_096);
    }

    /** Creates an endpoint with explicit application and security dependencies. */
    public SqlServer(
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
                16 * 1_048_576,
                128,
                4_096);
    }

    /** Creates an endpoint with explicit bind and defensive limits. */
    public SqlServer(
            String bindAddress,
            int port,
            MetricsRegistry metrics,
            CommandDispatcher dispatcher,
            Authenticator authenticator,
            AccessController accessController,
            int maxConnections,
            int maxCommandBytes,
            int maxUsernameBytes,
            int maxCredentialBytes) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.port = port;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.authenticator =
                Objects.requireNonNull(authenticator, "authenticator");
        this.accessController =
                Objects.requireNonNull(accessController, "accessController");
        this.connections = new ConnectionLimiter(maxConnections);
        this.maxCommandBytes = maxCommandBytes;
        this.maxUsernameBytes = maxUsernameBytes;
        this.maxCredentialBytes = maxCredentialBytes;
    }

    /** Binds the configured socket and begins serving semicolon-terminated SQL statements. */
    public synchronized void start() throws IOException {
        if (eventLoop != null) {
            return;
        }
        socket = ServerSocketChannel.open();
        try {
            socket.bind(new InetSocketAddress(bindAddress, port));
            eventLoop = new NioEventLoop(
                    ignored -> metrics.incrementCounter(
                            "sql.event_loop_failures"));
            PlanExecutor executor =
                    new PlanExecutor(dispatcher, accessController);
            eventLoop.start(socket, channel -> {
                if (!connections.tryAcquire()) {
                    metrics.incrementCounter("sql.connection_rejections");
                    throw new IOException("SQL connection limit reached");
                }
                metrics.incrementCounter("sql.connections");
                metrics.setGauge(
                        "sql.connections_active", connections.active());
                try {
                    return new ClientConnection(
                            channel,
                            new SqlConnectionHandler(
                                    authenticator,
                                    new SqlCompiler(),
                                    executor,
                                    new SqlResultEncoder(),
                                    metrics,
                                    maxCommandBytes,
                                    maxUsernameBytes,
                                    maxCredentialBytes),
                            () -> metrics.setGauge(
                                    "sql.connections_active",
                                    connections.release()));
                } catch (RuntimeException failure) {
                    metrics.setGauge(
                            "sql.connections_active", connections.release());
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
