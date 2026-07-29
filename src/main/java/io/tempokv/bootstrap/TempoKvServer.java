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
import io.tempokv.persistence.FileSystemAdapter;
import io.tempokv.persistence.FileWriteAheadLog;
import io.tempokv.persistence.FsyncPolicy;
import io.tempokv.persistence.RecoveryManager;
import io.tempokv.persistence.SnapshotStore;
import io.tempokv.persistence.SnapshotWriter;
import io.tempokv.persistence.WalCompactor;
import io.tempokv.persistence.WriteAheadLog;
import io.tempokv.server.RespServer;
import io.tempokv.server.SqlServer;
import io.tempokv.storage.HistoryGarbageCollector;
import io.tempokv.storage.MvccStore;
import io.tempokv.storage.ExpirationWorker;
import io.tempokv.storage.RetentionPolicy;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.VersionGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final MvccStore storage;
    private final VersionGenerator versions;
    private final WriteAheadLog writeAheadLog;
    private final RecoveryManager recoveryManager;
    private final ExpirationWorker expirationWorker;
    private final SnapshotStore snapshotStore;
    private final SnapshotWriter snapshotWriter;
    private final WalCompactor walCompactor;
    private final HistoryGarbageCollector garbageCollector;
    private final Clock clock;
    private RespServer respServer;
    private SqlServer sqlServer;
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
        this.clock = Clock.systemUTC();
        this.storage = new MvccStore();
        this.versions = new VersionGenerator();
        if (configuration.persistenceEnabled()) {
            try {
                FileSystemAdapter fileSystem = new FileSystemAdapter();
                this.writeAheadLog = new FileWriteAheadLog(configuration.dataDirectory(), fileSystem, FsyncPolicy.ALWAYS);
                this.snapshotStore = new SnapshotStore(configuration.dataDirectory(), fileSystem);
                this.recoveryManager = new RecoveryManager(snapshotStore, writeAheadLog);
                this.snapshotWriter = new SnapshotWriter(snapshotStore);
                this.walCompactor = new WalCompactor(writeAheadLog);
            } catch (IOException exception) { throw new UncheckedIOException("Could not configure persistence", exception); }
        } else {
            this.writeAheadLog = null;
            this.recoveryManager = null;
            this.snapshotStore = null;
            this.snapshotWriter = null;
            this.walCompactor = null;
        }
        CommitCoordinator commits = new CommitCoordinator(
                versions,
                storage,
                clock,
                record -> {
                    if (writeAheadLog != null) writeAheadLog.append(record);
                });
        this.expirationWorker = new ExpirationWorker(storage, commits, clock, failure -> {
            metrics.incrementCounter("expiration.failures");
            healthService.markDegraded("Active expiration failed");
        });
        RetentionPolicy retention = new RetentionPolicy(
                new RetentionPolicy.Rule(
                        DEFAULT_MAX_RETAINED_VERSIONS,
                        configuration.historyRetention()),
                Map.of());
        this.garbageCollector = new HistoryGarbageCollector(retention);
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

    /** Acquires infrastructure resources, starts both protocol endpoints, and publishes readiness. */
    public synchronized void start() throws IOException {
        if (started) {
            return;
        }
        healthService.markStarting();
        metrics.setGauge("server.ready", 0);
        try {
            databaseLock.acquire();
            if (recoveryManager != null) {
                healthService.markRecovering();
                Instant recoveryStarted = Instant.now(clock);
                recoveryManager.recover(storage, versions);
                metrics.recordLatency(
                        "recovery.duration",
                        Duration.between(recoveryStarted, Instant.now(clock)));
                metrics.setGauge("recovery.version", versions.currentVersion());
            }
            respServer = new RespServer(
                    configuration.respPort(), metrics, dispatcher);
            respServer.start();
            sqlServer = new SqlServer(
                    configuration.sqlPort(), metrics, dispatcher);
            sqlServer.start();
            expirationWorker.start();
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
        if (sqlServer != null) {
            try {
                sqlServer.close();
            } catch (IOException exception) {
                failure = exception;
            } finally {
                sqlServer = null;
            }
        }
        if (respServer != null) {
            try {
                respServer.close();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            } finally {
                respServer = null;
            }
        }
        expirationWorker.close();
        if (snapshotWriter != null) {
            try {
                snapshotAndCompact();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (writeAheadLog != null) {
            try { writeAheadLog.close(); } catch (IOException exception) { if (failure == null) failure = exception; else failure.addSuppressed(exception); }
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
                && (respServer == null || !respServer.isRunning()
                    || sqlServer == null || !sqlServer.isRunning())
                && healthService.currentHealth().state() != ServerHealth.STOPPING) {
            healthService.markDegraded("A protocol event loop is not running");
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
        return started
                && databaseLock.isHeld()
                && respServer != null
                && respServer.isRunning()
                && sqlServer != null
                && sqlServer.isRunning();
    }

    /** Returns the bound RESP port after the server becomes ready. */
    public synchronized int respPort() throws IOException {
        if (respServer == null) {
            throw new IOException("RESP server is not started");
        }
        return respServer.port();
    }

    /** Returns the bound SQL port after the server becomes ready. */
    public synchronized int sqlPort() throws IOException {
        if (sqlServer == null) {
            throw new IOException("SQL server is not started");
        }
        return sqlServer.port();
    }

    /**
     * Applies retention, publishes a validated snapshot, and compacts only through the oldest
     * retained valid snapshot so a corruption fallback remains recoverable.
     */
    public synchronized long snapshotAndCompact() throws IOException {
        if (snapshotWriter == null || snapshotStore == null || walCompactor == null) return 0;
        Instant startedAt = Instant.now(clock);
        try {
            int collected = garbageCollector.collect(storage, startedAt, 0);
            metrics.addCounter("history.versions_collected", collected);
            long publishedVersion = snapshotWriter.write(storage);
            long safeVersion = snapshotStore.safeCompactionVersion();
            walCompactor.compactThrough(safeVersion);
            metrics.incrementCounter("snapshot.successes");
            metrics.setGauge("snapshot.version", publishedVersion);
            metrics.setGauge("wal.compacted_through", safeVersion);
            if (writeAheadLog instanceof FileWriteAheadLog fileWal) {
                metrics.setGauge("wal.bytes", fileWal.sizeBytes());
            }
            metrics.recordLatency(
                    "snapshot.duration", Duration.between(startedAt, Instant.now(clock)));
            return publishedVersion;
        } catch (IOException | RuntimeException failure) {
            metrics.incrementCounter("snapshot.failures");
            healthService.markDegraded("Snapshot or WAL compaction failed");
            if (failure instanceof IOException io) throw io;
            throw new IOException("Snapshot or WAL compaction failed", failure);
        }
    }

    /** Delegates resource cleanup to the ordered shutdown operation. */
    @Override
    public void close() throws IOException {
        stop();
    }

    /** Attempts every startup cleanup step while preserving the original failure. */
    private void closeResources(Throwable failure) {
        if (sqlServer != null) {
            try {
                sqlServer.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            } finally {
                sqlServer = null;
            }
        }
        if (respServer != null) {
            try {
                respServer.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            } finally {
                respServer = null;
            }
        }
        expirationWorker.close();
        if (writeAheadLog != null) {
            try { writeAheadLog.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
        }
        try {
            databaseLock.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
