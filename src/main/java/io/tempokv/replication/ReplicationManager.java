package io.tempokv.replication;

import io.tempokv.bootstrap.ConfigurationException;
import io.tempokv.bootstrap.ServerConfiguration;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.observability.ServerHealthService;
import io.tempokv.persistence.SnapshotStore;
import io.tempokv.persistence.WriteAheadLog;
import io.tempokv.storage.StorageEngine;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.VersionGenerator;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Composes role-specific replication services and exposes their lifecycle and operational state.
 */
public final class ReplicationManager implements AutoCloseable {
    private final ServerConfiguration configuration;
    private final ReplicaState state;
    private final AckTracker acknowledgements;
    private final PrimaryReplicationEndpoint primaryEndpoint;
    private final ReplicaClient replicaClient;
    private final MetricsRegistry metrics;

    /**
     * Builds the primary endpoint or replica client from the immutable node configuration.
     */
    public ReplicationManager(
            ServerConfiguration configuration,
            StorageEngine storage,
            VersionGenerator versions,
            WriteAheadLog wal,
            SnapshotStore snapshots,
            CommitCoordinator commits,
            MetricsRegistry metrics,
            ServerHealthService health) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.state = new ReplicaState(configuration.nodeRole());
        this.acknowledgements = new AckTracker();
        if (!configuration.replicationEnabled()) {
            if (configuration.nodeRole() == ServerConfiguration.NodeRole.REPLICA) {
                throw new ConfigurationException(
                        "Replica nodes require replication-enabled=true");
            }
            primaryEndpoint = null;
            replicaClient = null;
            return;
        }
        Objects.requireNonNull(wal, "wal");
        Objects.requireNonNull(snapshots, "snapshots");
        if (configuration.nodeRole() == ServerConfiguration.NodeRole.PRIMARY) {
            primaryEndpoint = new PrimaryReplicationEndpoint(
                    configuration.replicationPort(),
                    configuration.bindAddress(),
                    configuration.replicationToken(),
                    commits,
                    new SyncCoordinator(storage, wal),
                    snapshots,
                    acknowledgements,
                    metrics,
                    configuration.limits().maxReplicationPeers(),
                    configuration.limits().maxPendingReplicaCommits(),
                    configuration.limits().maxPendingReplicaBytes(),
                    configuration.limits().maxSnapshotBytes(),
                    configuration.limits().replicationSyncTimeout(),
                    configuration.limits().replicationHeartbeatInterval(),
                    configuration.limits().replicationHeartbeatTimeout());
            replicaClient = null;
        } else {
            primaryEndpoint = null;
            ReplicaApplier applier =
                    new ReplicaApplier(storage, versions, wal, snapshots, state);
            replicaClient = new ReplicaClient(
                    configuration.primaryHost(),
                    configuration.primaryReplicationPort(),
                    configuration.replicationToken(),
                    configuration.nodeId(),
                    applier,
                    state,
                    snapshots,
                    metrics,
                    Objects.requireNonNull(health, "health"),
                    configuration.limits().maxSnapshotBytes(),
                    configuration.limits().replicationSyncTimeout(),
                    configuration.limits().replicationHeartbeatTimeout());
        }
    }

    /** Initializes replication version state after local recovery. */
    public void initialize(long recoveredVersion) {
        state.initialize(recoveredVersion);
        metrics.setGauge("replication.applied_version", recoveredVersion);
        metrics.setGauge("replication.acknowledged_version", recoveredVersion);
    }

    /** Starts the configured role service, waiting for initial replica catch-up when necessary. */
    public void start() throws IOException {
        if (primaryEndpoint != null) {
            primaryEndpoint.start();
            state.markCaughtUp(state.appliedVersion());
        } else if (replicaClient != null) {
            replicaClient.startAndAwait(
                    configuration.limits().replicationSyncTimeout());
        }
    }

    /** Publishes one primary commit to live replicas; it is a no-op on other configurations. */
    public void publish(CommitRecord record) {
        if (primaryEndpoint != null) {
            state.markApplied(record.version());
            state.markAcknowledged(record.version());
            state.markCaughtUp(record.version());
            metrics.setGauge("replication.applied_version", record.version());
            metrics.setGauge("replication.acknowledged_version", record.version());
            metrics.setGauge("replication.primary_version", record.version());
            primaryEndpoint.publish(record);
        }
    }

    /**
     * Restricts WAL compaction to the oldest connected durable replica acknowledgement.
     */
    public long safeCompactionVersion(long snapshotSafeVersion) {
        if (primaryEndpoint == null) return snapshotSafeVersion;
        return acknowledgements.minimumAcknowledgedVersion()
                .stream()
                .map(value -> Math.min(value, snapshotSafeVersion))
                .findFirst()
                .orElse(snapshotSafeVersion);
    }

    /** Returns stable role, state, version, lag, and connection diagnostics for INFO. */
    public Map<String, String> operationalInfo() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("replication.role", configuration.nodeRole().name());
        values.put("replication.state",
                primaryEndpoint == null && replicaClient == null
                        ? "DISABLED"
                        : state.status().name());
        values.put("replication.applied_version", Long.toString(state.appliedVersion()));
        values.put("replication.acknowledged_version",
                Long.toString(state.acknowledgedVersion()));
        values.put("replication.primary_version", Long.toString(state.primaryVersion()));
        values.put("replication.lag", Long.toString(state.lag()));
        values.put("replication.replicas_connected",
                Integer.toString(acknowledgements.snapshot().size()));
        return Map.copyOf(values);
    }

    /** Returns whether the configured replication service is alive. */
    public boolean isRunning() {
        if (primaryEndpoint != null) return primaryEndpoint.isRunning();
        if (replicaClient != null) return replicaClient.isRunning();
        return true;
    }

    /** Returns the primary's bound internal port. */
    public int port() throws IOException {
        if (primaryEndpoint == null) {
            throw new IOException("Primary replication endpoint is not enabled");
        }
        return primaryEndpoint.port();
    }

    /** Closes the role-specific transport. */
    @Override
    public void close() throws IOException {
        if (primaryEndpoint != null) primaryEndpoint.close();
        if (replicaClient != null) replicaClient.close();
    }
}
