package io.tempokv.transaction;

import java.util.Objects;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Registers active MVCC snapshots and exposes the oldest version protected from collection.
 */
public final class SnapshotManager {
    private final LongSupplier currentVersion;
    private final TreeMap<Long, Integer> active = new TreeMap<>();

    /** Creates a manager whose snapshot cut is supplied by the node version generator. */
    public SnapshotManager(LongSupplier currentVersion) {
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
    }

    /** Opens and registers a snapshot at the latest completely allocated commit version. */
    public synchronized long openSnapshot() {
        long version = currentVersion.getAsLong();
        active.merge(version, 1, Integer::sum);
        return version;
    }

    /** Releases exactly one registration for a previously opened snapshot. */
    public synchronized void releaseSnapshot(long version) {
        Integer count = active.get(version);
        if (count == null) {
            throw new IllegalStateException("Snapshot is not active");
        }
        if (count == 1) active.remove(version);
        else active.put(version, count - 1);
    }

    /** Returns the oldest protected version, or zero when no transaction snapshot is active. */
    public synchronized long oldestActiveVersion() {
        return active.isEmpty() ? 0L : active.firstKey();
    }

    /** Returns the number of sessions currently protecting a snapshot. */
    public synchronized int activeCount() {
        return active.values().stream().mapToInt(Integer::intValue).sum();
    }
}
