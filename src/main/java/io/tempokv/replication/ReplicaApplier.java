package io.tempokv.replication;

import io.tempokv.persistence.SnapshotStore;
import io.tempokv.persistence.WriteAheadLog;
import io.tempokv.storage.StorageEngine;
import io.tempokv.storage.StorageSnapshot;
import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.VersionGenerator;
import java.io.IOException;
import java.util.Objects;

/**
 * Applies primary snapshots and commits durably on a replica without allocating local versions.
 */
public final class ReplicaApplier {
    private final StorageEngine storage;
    private final VersionGenerator versions;
    private final WriteAheadLog wal;
    private final SnapshotStore snapshots;
    private final ReplicaState state;

    /** Creates an applier over the replica's local durable state. */
    public ReplicaApplier(
            StorageEngine storage,
            VersionGenerator versions,
            WriteAheadLog wal,
            SnapshotStore snapshots,
            ReplicaState state) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.wal = Objects.requireNonNull(wal, "wal");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.state = Objects.requireNonNull(state, "state");
    }

    /** Installs a complete snapshot before making its cutoff observable to replica readers. */
    public synchronized void install(StorageSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.version() < state.appliedVersion()) {
            throw new IOException("Replica snapshot version moved backwards");
        }
        snapshots.save(snapshot);
        storage.restore(snapshot);
        wal.compactThrough(snapshot.version());
        versions.advanceTo(snapshot.version());
        state.markApplied(snapshot.version());
    }

    /**
     * Durably appends and applies one strictly newer primary record without calling nextVersion().
     */
    public synchronized void apply(CommitRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        if (record.version() != state.appliedVersion() + 1) {
            state.markFailed();
            throw new IOException("Replica commit is duplicate, gapped, or out of order");
        }
        wal.append(record);
        storage.apply(record);
        versions.advanceTo(record.version());
        state.markApplied(record.version());
    }
}
