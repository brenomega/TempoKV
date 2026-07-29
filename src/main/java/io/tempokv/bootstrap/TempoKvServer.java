package io.tempokv.bootstrap;

import io.tempokv.application.AdminCommandHandler;
import io.tempokv.application.CommandDispatcher;
import io.tempokv.application.CommandHandler;
import io.tempokv.application.CommandValidator;
import io.tempokv.application.KeyValueCommandHandler;
import io.tempokv.application.TemporalCommandHandler;
import io.tempokv.application.TransactionCommandHandler;
import io.tempokv.observability.CommandTracer;
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
import io.tempokv.replication.ReplicationManager;
import io.tempokv.server.RespServer;
import io.tempokv.server.SqlServer;
import io.tempokv.security.AccessController;
import io.tempokv.security.Authenticator;
import io.tempokv.storage.HistoryGarbageCollector;
import io.tempokv.storage.MvccStore;
import io.tempokv.storage.ExpirationWorker;
import io.tempokv.storage.RetentionPolicy;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.VersionGenerator;
import io.tempokv.transaction.ConflictDetector;
import io.tempokv.transaction.SnapshotManager;
import io.tempokv.transaction.TransactionManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates node lifecycle, exclusive data-directory ownership, and observable health.
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
    private final CommitCoordinator commits;
    private final WriteAheadLog writeAheadLog;
    private final RecoveryManager recoveryManager;
    private final ExpirationWorker expirationWorker;
    private final SnapshotStore snapshotStore;
    private final SnapshotWriter snapshotWriter;
    private final WalCompactor walCompactor;
    private final HistoryGarbageCollector garbageCollector;
    private final SnapshotManager snapshotManager;
    private final TransactionManager transactionManager;
    private final Authenticator authenticator;
    private final AccessController accessController;
    private final ReplicationManager replicationManager;
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
        this(
                configuration,
                databaseLock,
                metrics,
                healthService,
                configuration.authenticationEnabled()
                        ? Authenticator.users(Map.of(
                                configuration.authenticationUsername(),
                                configuration.authenticationPassword()))
                        : Authenticator.permissive(),
                defaultAccessController(configuration));
    }

    /**
     * Creates a server with explicit identity resolution and ACL dependencies.
     *
     * <p>This constructor is the composition point for deployments that enable credential
     * authentication or provide transport-derived identities.</p>
     */
    public TempoKvServer(
            ServerConfiguration configuration,
            DatabaseLock databaseLock,
            MetricsRegistry metrics,
            ServerHealthService healthService,
            Authenticator authenticator,
            AccessController accessController) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.databaseLock = Objects.requireNonNull(databaseLock, "databaseLock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.healthService = Objects.requireNonNull(healthService, "healthService");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.accessController = Objects.requireNonNull(accessController, "accessController");
        this.clock = Clock.systemUTC();
        this.storage = new MvccStore();
        this.versions = new VersionGenerator();
        if (configuration.persistenceEnabled()) {
            try {
                FileSystemAdapter fileSystem = new FileSystemAdapter();
                this.writeAheadLog = new FileWriteAheadLog(configuration.dataDirectory(), fileSystem, FsyncPolicy.ALWAYS);
                this.snapshotStore = new SnapshotStore(
                        configuration.dataDirectory(),
                        fileSystem,
                        configuration.limits().maxSnapshotBytes());
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
        this.commits = new CommitCoordinator(
                versions,
                storage,
                clock,
                record -> {
                    if (writeAheadLog != null) writeAheadLog.append(record);
                },
                failure -> {
                    metrics.incrementCounter("commit.failures");
                    healthService.markDegraded("Commit pipeline failed");
                });
        this.replicationManager = new ReplicationManager(
                configuration,
                storage,
                versions,
                writeAheadLog,
                snapshotStore,
                commits,
                metrics,
                healthService);
        commits.setCommitPublisher(replicationManager::publish);
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
        this.snapshotManager = new SnapshotManager(commits::currentVersion);
        this.transactionManager = new TransactionManager(
                storage,
                commits,
                snapshotManager,
                new ConflictDetector(storage),
                metrics,
                configuration.limits().maxTransactionMutations(),
                configuration.limits().maxTransactionWriteBytes());
        this.dispatcher = new CommandDispatcher(
                new CommandValidator(),
                List.<CommandHandler<? extends io.tempokv.application.Command>>of(
                        new AdminCommandHandler(
                                metrics,
                                healthService,
                                configuration.nodeRole().name(),
                                versions::currentVersion,
                                replicationManager::operationalInfo),
                        new TransactionCommandHandler(
                                transactionManager, metrics),
                        new KeyValueCommandHandler(
                                storage,
                                commits,
                                clock,
                                metrics,
                                transactionManager),
                        new TemporalCommandHandler(
                                storage,
                                commits,
                                metrics,
                                clock,
                                garbageCollector,
                                transactionManager,
                                snapshotManager)),
                new CommandTracer(metrics));
    }

    private static AccessController defaultAccessController(
            ServerConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        java.util.Set<String> commands = configuration.nodeRole()
                == ServerConfiguration.NodeRole.PRIMARY
                        ? java.util.Set.of(
                                "PING", "HEALTH", "INFO",
                                "BEGIN", "COMMIT", "ROLLBACK",
                                "GET", "SET", "DEL", "EXPIRE", "TTL",
                                "GETAT", "HISTORY", "DIFF", "RESTOREAT")
                        : java.util.Set.of(
                                "PING", "HEALTH", "INFO",
                                "GET", "TTL", "GETAT", "HISTORY", "DIFF");
        String denial = configuration.nodeRole() == ServerConfiguration.NodeRole.REPLICA
                ? "READONLY replica does not accept writes"
                : "ERR command is not permitted";
        String identity = configuration.authenticationEnabled()
                ? configuration.authenticationUsername()
                : "default";
        return AccessController.rules(Map.of(
                identity,
                new AccessController.Rule(commands, java.util.Set.of(""))),
                denial);
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
            replicationManager.initialize(versions.currentVersion());
            replicationManager.start();
            respServer = new RespServer(
                    configuration.bindAddress(),
                    configuration.respPort(),
                    metrics,
                    dispatcher,
                    authenticator,
                    accessController,
                    configuration.limits().maxConnectionsPerProtocol(),
                    configuration.limits().maxRespArrayElements(),
                    configuration.limits().maxCommandBytes(),
                    configuration.limits().maxUsernameBytes(),
                    configuration.limits().maxCredentialBytes());
            respServer.start();
            sqlServer = new SqlServer(
                    configuration.bindAddress(),
                    configuration.sqlPort(),
                    metrics,
                    dispatcher,
                    authenticator,
                    accessController,
                    configuration.limits().maxConnectionsPerProtocol(),
                    configuration.limits().maxCommandBytes(),
                    configuration.limits().maxUsernameBytes(),
                    configuration.limits().maxCredentialBytes());
            sqlServer.start();
            if (configuration.nodeRole() == ServerConfiguration.NodeRole.PRIMARY) {
                expirationWorker.start();
            }
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
        if (configuration.nodeRole() == ServerConfiguration.NodeRole.REPLICA) {
            try {
                replicationManager.close();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (snapshotWriter != null) {
            try {
                snapshotAndCompact();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (configuration.nodeRole() == ServerConfiguration.NodeRole.PRIMARY) {
            try {
                replicationManager.close();
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
                    || sqlServer == null || !sqlServer.isRunning()
                    || !replicationManager.isRunning())
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

    /** Returns whether the running lifecycle currently owns its data-directory lock. */
    public synchronized boolean isRunning() {
        return started
                && databaseLock.isHeld()
                && respServer != null
                && respServer.isRunning()
                && sqlServer != null
                && sqlServer.isRunning()
                && replicationManager.isRunning();
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

    /** Returns the bound primary replication port after startup. */
    public synchronized int replicationPort() throws IOException {
        return replicationManager.port();
    }

    /**
     * Applies retention, publishes a validated snapshot, and compacts only through the oldest
     * retained valid snapshot so a corruption fallback remains recoverable.
     */
    public synchronized long snapshotAndCompact() throws IOException {
        if (snapshotWriter == null || snapshotStore == null || walCompactor == null) return 0;
        Instant startedAt = Instant.now(clock);
        try {
            int collected = garbageCollector.collect(
                    storage,
                    startedAt,
                    snapshotManager.oldestActiveVersion());
            metrics.addCounter("history.versions_collected", collected);
            long publishedVersion = snapshotWriter.write(storage);
            long safeVersion = replicationManager.safeCompactionVersion(
                    snapshotStore.safeCompactionVersion());
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
        try {
            replicationManager.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
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
