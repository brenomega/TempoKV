package io.tempokv.replication;

import io.tempokv.bootstrap.ServerConfiguration;
import java.util.Objects;

/**
 * Maintains the role, applied version, acknowledged version, and synchronization state of a node.
 */
public final class ReplicaState {
    /** Describes whether a node is disconnected, synchronizing, caught up, or failed. */
    public enum SyncStatus { DISCONNECTED, SYNCHRONIZING, CAUGHT_UP, FAILED }

    private final ServerConfiguration.NodeRole role;
    private long appliedVersion;
    private long acknowledgedVersion;
    private long primaryVersion;
    private SyncStatus status = SyncStatus.DISCONNECTED;

    /** Creates state for the configured immutable node role. */
    public ReplicaState(ServerConfiguration.NodeRole role) {
        this.role = Objects.requireNonNull(role, "role");
    }

    /** Initializes the applied version restored from local durable state. */
    public synchronized void initialize(long recoveredVersion) {
        if (recoveredVersion < 0) {
            throw new IllegalArgumentException("Recovered version must not be negative");
        }
        appliedVersion = recoveredVersion;
        acknowledgedVersion = recoveredVersion;
        primaryVersion = Math.max(primaryVersion, recoveredVersion);
    }

    /** Marks a replication connection as actively synchronizing. */
    public synchronized void markSynchronizing() {
        status = SyncStatus.SYNCHRONIZING;
    }

    /** Advances local applied state without allowing version regression. */
    public synchronized void markApplied(long version) {
        if (version < appliedVersion) {
            throw new IllegalStateException("Replication version moved backwards");
        }
        appliedVersion = version;
    }

    /** Records the version durably confirmed to the primary. */
    public synchronized void markAcknowledged(long version) {
        if (version < acknowledgedVersion || version > appliedVersion) {
            throw new IllegalStateException("Invalid replication acknowledgement");
        }
        acknowledgedVersion = version;
    }

    /** Marks the replica caught up to the supplied primary version. */
    public synchronized void markCaughtUp(long version) {
        if (version < appliedVersion) {
            throw new IllegalStateException("Primary version is behind the replica");
        }
        primaryVersion = version;
        status = SyncStatus.CAUGHT_UP;
    }

    /** Marks transport loss while preserving the last durable applied version. */
    public synchronized void markDisconnected() {
        status = SyncStatus.DISCONNECTED;
    }

    /** Marks an invalid replication stream. */
    public synchronized void markFailed() {
        status = SyncStatus.FAILED;
    }

    /** Returns the immutable configured role. */
    public ServerConfiguration.NodeRole role() { return role; }
    /** Returns the latest locally applied version. */
    public synchronized long appliedVersion() { return appliedVersion; }
    /** Returns the latest version acknowledged to the primary. */
    public synchronized long acknowledgedVersion() { return acknowledgedVersion; }
    /** Returns the primary version observed at the last synchronization boundary. */
    public synchronized long primaryVersion() { return primaryVersion; }
    /** Returns the current synchronization state. */
    public synchronized SyncStatus status() { return status; }
    /** Returns the currently observable replication lag in versions. */
    public synchronized long lag() {
        return Math.max(0L, primaryVersion - appliedVersion);
    }
}
