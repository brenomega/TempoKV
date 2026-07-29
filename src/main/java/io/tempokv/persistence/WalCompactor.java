package io.tempokv.persistence;

import java.io.IOException;
import java.util.Objects;

/** Compacts only WAL data proven durable by a successfully published snapshot. */
public final class WalCompactor {
    private final WriteAheadLog wal;
    /** Creates a compactor for a single node WAL. */
    public WalCompactor(WriteAheadLog wal) { this.wal = Objects.requireNonNull(wal, "wal"); }
    /** Removes records at or below the durable snapshot cutoff. */
    public void compactThrough(long snapshotVersion) throws IOException { wal.compactThrough(snapshotVersion); }
}
