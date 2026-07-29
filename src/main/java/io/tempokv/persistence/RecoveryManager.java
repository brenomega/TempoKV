package io.tempokv.persistence;

import io.tempokv.storage.StorageEngine;
import io.tempokv.storage.StorageSnapshot;
import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.VersionGenerator;
import java.io.IOException;
import java.util.Objects;

/** Restores the latest snapshot and replays newer WAL commits before endpoints are opened. */
public final class RecoveryManager {
    private final SnapshotStore snapshots;
    private final WriteAheadLog wal;

    /** Creates a recovery service bound to one snapshot store and WAL. */
    public RecoveryManager(SnapshotStore snapshots, WriteAheadLog wal) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots"); this.wal = Objects.requireNonNull(wal, "wal");
    }

    /** Rebuilds storage in commit order and advances version allocation beyond recovered data. */
    public long recover(StorageEngine storage, VersionGenerator versions) throws IOException {
        long recovered = 0;
        var snapshot = snapshots.load();
        if (snapshot.isPresent()) {
            StorageSnapshot state = snapshot.orElseThrow();
            storage.restore(state);
            recovered = state.version();
        }
        long[] replayedVersion = {recovered};
        wal.replay(record -> {
            if (record.version() > replayedVersion[0]) {
                storage.apply(record);
                replayedVersion[0] = record.version();
            }
        });
        versions.advanceTo(replayedVersion[0]);
        return replayedVersion[0];
    }
}
