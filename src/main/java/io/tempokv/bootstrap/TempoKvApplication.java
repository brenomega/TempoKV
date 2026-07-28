package io.tempokv.bootstrap;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.observability.ServerHealthService;
import io.tempokv.persistence.DatabaseLock;
import io.tempokv.persistence.FileSystemAdapter;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * Provides the process entry point and composition root for the TempoKV node.
 */
public final class TempoKvApplication {
    private TempoKvApplication() {
    }

    /** Starts the process and keeps it alive until the JVM requests shutdown. */
    public static void main(String[] args) {
        int exitCode = run(args, System.getenv(), System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** Builds and starts a server using explicit configuration sources. */
    public static TempoKvServer bootstrap(String[] args, Map<String, String> environment) throws IOException {
        ServerConfiguration configuration = ServerConfiguration.load(args, environment);
        FileSystemAdapter fileSystem = new FileSystemAdapter();
        DatabaseLock databaseLock = new DatabaseLock(fileSystem, configuration.dataDirectory());
        MetricsRegistry metrics = new MetricsRegistry();
        ServerHealthService health = new ServerHealthService();
        TempoKvServer server = new TempoKvServer(configuration, databaseLock, metrics, health);
        server.start();
        return server;
    }

    /** Builds and starts a server using the current process environment. */
    public static TempoKvServer bootstrap(String[] args) throws IOException {
        return bootstrap(args, System.getenv());
    }

    /** Registers ordered server shutdown with the JVM. */
    public static void registerShutdownHook(TempoKvServer server) {
        Objects.requireNonNull(server, "server");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop();
            } catch (IOException exception) {
                System.err.println("TempoKV shutdown failed: " + exception.getMessage());
            }
        }, "tempokv-shutdown"));
    }

    /** Executes bootstrap and reports configuration or infrastructure failures to the caller. */
    static int run(String[] args, Map<String, String> environment, PrintStream error) {
        Objects.requireNonNull(error, "error");
        try {
            TempoKvServer server = bootstrap(args, environment);
            registerShutdownHook(server);
            awaitShutdown();
            return 0;
        } catch (ConfigurationException exception) {
            error.println("TempoKV configuration error: " + exception.getMessage());
            return 2;
        } catch (IOException exception) {
            error.println("TempoKV startup error: " + exception.getMessage());
            return 1;
        } catch (RuntimeException exception) {
            error.println("TempoKV startup error: " + exception.getClass().getSimpleName());
            return 1;
        }
    }

    /** Blocks the main thread until the JVM starts normal shutdown. */
    private static void awaitShutdown() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
