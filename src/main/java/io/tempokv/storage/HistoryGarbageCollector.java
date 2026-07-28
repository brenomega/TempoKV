package io.tempokv.storage;

import java.time.Instant;
import java.util.Objects;

/** Removes history outside retention while preserving versions required by active snapshots. */
public final class HistoryGarbageCollector {
    private final RetentionPolicy policy;

    /** Creates a collector with the policy it must apply consistently to every key. */
    public HistoryGarbageCollector(RetentionPolicy policy) { this.policy = Objects.requireNonNull(policy, "policy"); }

    /** Collects obsolete versions; zero means no chain was shortened. */
    public int collect(MvccStore store, Instant now, long oldestSnapshotVersion) {
        return Objects.requireNonNull(store, "store").retainHistory(policy, Objects.requireNonNull(now, "now"), oldestSnapshotVersion);
    }
}
