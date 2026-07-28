package io.tempokv.bootstrap;

import io.tempokv.observability.HealthStatus;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.observability.MetricsSnapshot;
import io.tempokv.observability.ServerHealth;
import io.tempokv.observability.ServerHealthService;
import io.tempokv.persistence.DatabaseLock;

import java.io.IOException;
import java.util.Objects;

/**
 * Orchestrates the E1 lifecycle, exclusive data-directory ownership, and observable health of one node.
 */
public final class TempoKvServer implements AutoCloseable {
    private final ServerConfiguration configuration;
    private final DatabaseLock databaseLock;
    private final MetricsRegistry metrics;
    private final ServerHealthService healthService;
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
    }

    /** Acquires the data-directory lock and publishes readiness for the E1 node. */
    public synchronized void start() throws IOException {
        if (started) {
            return;
        }
        healthService.markStarting();
        metrics.setGauge("server.ready", 0);
        try {
            databaseLock.acquire();
            started = true;
            metrics.incrementCounter("server.starts");
            metrics.setGauge("server.lock_held", 1);
            metrics.setGauge("server.ready", 1);
            healthService.markReady();
        } catch (IOException | RuntimeException exception) {
            metrics.setGauge("server.lock_held", 0);
            metrics.setGauge("server.ready", 0);
            healthService.markDegraded("Startup failed: " + exception.getClass().getSimpleName());
            try {
                databaseLock.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    /** Stops E1 infrastructure in reverse ownership order and releases the data lock. */
    public synchronized void stop() throws IOException {
        if (!started && !databaseLock.isHeld()) {
            return;
        }
        healthService.markStopping();
        metrics.setGauge("server.ready", 0);
        IOException failure = null;
        try {
            databaseLock.close();
        } catch (IOException exception) {
            failure = exception;
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
    public HealthStatus health() {
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
        return started && databaseLock.isHeld();
    }

    /** Delegates resource cleanup to the ordered shutdown operation. */
    @Override
    public void close() throws IOException {
        stop();
    }
}
