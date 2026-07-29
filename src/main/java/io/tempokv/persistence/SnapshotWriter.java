package io.tempokv.persistence;

import io.tempokv.storage.StorageEngine;
import io.tempokv.storage.StorageSnapshot;
import java.io.IOException;
import java.util.Objects;

/** Publishes a consistent storage cut and returns the durable cutoff version. */
public final class SnapshotWriter {
    private final SnapshotStore snapshots;

    /** Creates a writer using the supplied atomic snapshot store. */
    public SnapshotWriter(SnapshotStore snapshots) { this.snapshots = Objects.requireNonNull(snapshots, "snapshots"); }

    /** Captures state once and publishes it atomically before any WAL compaction. */
    public long write(StorageEngine storage) throws IOException {
        StorageSnapshot snapshot = Objects.requireNonNull(storage, "storage").snapshot(); snapshots.save(snapshot); return snapshot.version();
    }
}
