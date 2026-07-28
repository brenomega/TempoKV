package io.tempokv.bootstrap;

import io.tempokv.application.AdminCommandHandler;
import io.tempokv.application.CommandDispatcher;
import io.tempokv.application.CommandHandler;
import io.tempokv.application.CommandValidator;
import io.tempokv.application.KeyValueCommandHandler;
import io.tempokv.application.TemporalCommandHandler;
import io.tempokv.observability.HealthStatus;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.observability.MetricsSnapshot;
import io.tempokv.observability.ServerHealth;
import io.tempokv.observability.ServerHealthService;
import io.tempokv.persistence.DatabaseLock;
import io.tempokv.server.RespServer;
import io.tempokv.storage.HistoryGarbageCollector;
import io.tempokv.storage.MvccStore;
import io.tempokv.storage.RetentionPolicy;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.VersionGenerator;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates the E1 lifecycle, exclusive data-directory ownership, and observable health of one node.
 */
public final class TempoKvServer implements AutoCloseable {
    private static final int DEFAULT_MAX_RETAINED_VERSIONS = 1_000;
    private final ServerConfiguration configuration;
    private final DatabaseLock databaseLock;
    private final MetricsRegistry metrics;
    private final ServerHealthService healthService;
    private final CommandDispatcher dispatcher;
    private RespServer respServer;
    private boolean started;

    /** Creates a server from already-constructed infrastructure dependencies. */
    public TempoKvServer(
            ServerConfiguration configuration,
            DatabaseLock databaseLock,
            MetricsRegistry metrics,
            ServerHealthService healthService) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.databaseLock = Objects.requireNonNull(databaseLock, "databaseLock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.healthService = Objects.requireNonNull(healthService, "healthService");
        Clock clock = Clock.systemUTC();
        MvccStore storage = new MvccStore();
        CommitCoordinator commits =
                new CommitCoordinator(new VersionGenerator(), storage, clock);
        RetentionPolicy retention = new RetentionPolicy(
                new RetentionPolicy.Rule(
                        DEFAULT_MAX_RETAINED_VERSIONS,
                        configuration.historyRetention()),
                Map.of());
        HistoryGarbageCollector garbageCollector =
                new HistoryGarbageCollector(retention);
        this.dispatcher = new CommandDispatcher(
                new CommandValidator(),
                List.<CommandHandler<? extends io.tempokv.application.Command>>of(
                        new AdminCommandHandler(metrics),
                        new KeyValueCommandHandler(
                                storage, commits, clock, metrics),
                        new TemporalCommandHandler(
                                storage,
                                commits,
                                metrics,
                                clock,
                                garbageCollector)));
    }

    /** Acquires infrastructure resources, starts the RESP endpoint, and publishes readiness. */
    public synchronized void start() throws IOException {
        if (started) {
            return;
        }
        healthService.markStarting();
        metrics.setGauge("server.ready", 0);
        try {
            databaseLock.acquire();
            respServer = new RespServer(
                    configuration.respPort(), metrics, dispatcher);
            respServer.start();
            started = true;
            metrics.incrementCounter("server.starts");
            metrics.setGauge("server.lock_held", 1);
            metrics.setGauge("server.ready", 1);
            healthService.markReady();
        } catch (IOException | RuntimeException exception) {
            metrics.setGauge("server.lock_held", 0);
            metrics.setGauge("server.ready", 0);
            healthService.markDegraded("Startup failed: " + exception.getClass().getSimpleName());
            closeResources(exception);
            throw exception;
        }
    }

    /** Stops network processing before releasing the data-directory lock. */
    public synchronized void stop() throws IOException {
        if (!started && !databaseLock.isHeld()) {
            return;
        }
        healthService.markStopping();
        metrics.setGauge("server.ready", 0);
        IOException failure = null;
        if (respServer != null) {
            try {
                respServer.close();
            } catch (IOException exception) {
                failure = exception;
            } finally {
                respServer = null;
            }
        }
        try {
            databaseLock.close();
        } catch (IOException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        } finally {
            started = false;
            metrics.setGauge("server.lock_held", 0);
            metrics.incrementCounter("server.stops");
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Returns the currently observable node health. */
    public synchronized HealthStatus health() {
        if (started
                && (respServer == null || !respServer.isRunning())
                && healthService.currentHealth().state() != ServerHealth.STOPPING) {
            healthService.markDegraded("RESP event loop is not running");
            metrics.setGauge("server.ready", 0);
        }
        return healthService.currentHealth();
    }

    /** Returns the lifecycle state used by bootstrap callers. */
    public ServerHealth state() {
        return health().state();
    }

    /** Returns an immutable snapshot of the node bootstrap metrics. */
    public MetricsSnapshot metrics() {
        return metrics.snapshot();
    }

    /** Returns whether the E1 lifecycle currently owns its data-directory lock. */
    public synchronized boolean isRunning() {
        return started && databaseLock.isHeld() && respServer != null && respServer.isRunning();
    }

    /** Returns the bound RESP port after the server becomes ready. */
    public synchronized int respPort() throws IOException {
        if (respServer == null) {
            throw new IOException("RESP server is not started");
        }
        return respServer.port();
    }

    /** Delegates resource cleanup to the ordered shutdown operation. */
    @Override
    public void close() throws IOException {
        stop();
    }

    /** Attempts every startup cleanup step while preserving the original failure. */
    private void closeResources(Throwable failure) {
        if (respServer != null) {
            try {
                respServer.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            } finally {
                respServer = null;
            }
        }
        try {
            databaseLock.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
