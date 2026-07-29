package io.tempokv.replication;

import io.tempokv.persistence.WriteAheadLog;
import io.tempokv.storage.StorageEngine;
import io.tempokv.storage.StorageSnapshot;
import io.tempokv.transaction.CommitRecord;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Chooses full snapshot synchronization or ordered incremental WAL catch-up for a replica.
 */
public final class SyncCoordinator {
    /** Identifies the initial synchronization representation. */
    public enum Mode { FULL, INCREMENTAL }

    private final StorageEngine storage;
    private final WriteAheadLog wal;

    /** Creates a coordinator over the primary's stable storage and WAL. */
    public SyncCoordinator(StorageEngine storage, WriteAheadLog wal) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.wal = Objects.requireNonNull(wal, "wal");
    }

    /**
     * Builds a synchronization plan while the caller holds the commit coordinator stable-state
     * monitor.
     */
    public Plan plan(long replicaVersion) throws IOException {
        if (replicaVersion < 0) {
            throw new IllegalArgumentException("Replica version must not be negative");
        }
        long primaryVersion = storage.currentVersion();
        if (replicaVersion > primaryVersion) {
            throw new IOException("Replica version is ahead of primary");
        }
        List<CommitRecord> records = wal.replay();
        if (replicaVersion == primaryVersion) {
            return Plan.incremental(primaryVersion, List.of());
        }
        if (replicaVersion == 0 || !walCovers(records, replicaVersion)) {
            return Plan.full(storage.snapshot());
        }
        return Plan.incremental(
                primaryVersion,
                records.stream()
                        .filter(record -> record.version() > replicaVersion)
                        .toList());
    }

    private static boolean walCovers(
            List<CommitRecord> records, long replicaVersion) {
        if (records.isEmpty()) return false;
        long firstNewer = records.stream()
                .mapToLong(CommitRecord::version)
                .filter(version -> version > replicaVersion)
                .findFirst()
                .orElse(Long.MAX_VALUE);
        return firstNewer == Long.MAX_VALUE
                || firstNewer == replicaVersion + 1;
    }

    /** Holds one complete snapshot or an ordered list of newer commit records. */
    public record Plan(
            Mode mode,
            long primaryVersion,
            StorageSnapshot snapshot,
            List<CommitRecord> commits) {
        /** Validates the mutually exclusive full and incremental plan representations. */
        public Plan {
            mode = Objects.requireNonNull(mode, "mode");
            commits = List.copyOf(Objects.requireNonNull(commits, "commits"));
            if (primaryVersion < 0) {
                throw new IllegalArgumentException("Primary version must not be negative");
            }
            if ((mode == Mode.FULL) != (snapshot != null) || mode == Mode.FULL && !commits.isEmpty()) {
                throw new IllegalArgumentException("Invalid synchronization plan");
            }
        }

        /** Creates a full snapshot plan. */
        static Plan full(StorageSnapshot snapshot) {
            StorageSnapshot state = Objects.requireNonNull(snapshot, "snapshot");
            return new Plan(Mode.FULL, state.version(), state, List.of());
        }

        /** Creates an incremental WAL plan. */
        static Plan incremental(long primaryVersion, List<CommitRecord> records) {
            return new Plan(Mode.INCREMENTAL, primaryVersion, null, records);
        }
    }
}
