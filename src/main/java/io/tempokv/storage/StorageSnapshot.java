package io.tempokv.storage;

import java.util.Map;
import java.util.Objects;
import java.time.Instant;
import java.util.List;

/**
 * Immutable, serializable cut of retained MVCC chains, retention boundaries, and TTL entries.
 */
public record StorageSnapshot(
        long version,
        Map<String, VersionChain> chains,
        Map<String, HistoryBoundary> boundaries,
        List<TtlIndex.Entry> expirations) {
    /** Validates the cut version and defensively copies every snapshot component. */
    public StorageSnapshot {
        if (version < 0) throw new IllegalArgumentException("Snapshot version must not be negative");
        chains = Map.copyOf(Objects.requireNonNull(chains, "chains"));
        boundaries = Map.copyOf(Objects.requireNonNull(boundaries, "boundaries"));
        expirations = List.copyOf(Objects.requireNonNull(expirations, "expirations"));
        if (!chains.keySet().equals(boundaries.keySet())) {
            throw new IllegalArgumentException("Every snapshot chain requires a history boundary");
        }
    }

    /** Preserves whether retained history was truncated before the oldest stored version. */
    public record HistoryBoundary(long firstVersion, Instant firstCommittedAt, boolean truncated) {
        /** Validates immutable historical-boundary metadata. */
        public HistoryBoundary {
            if (firstVersion < 1) throw new IllegalArgumentException("First version must be positive");
            firstCommittedAt = Objects.requireNonNull(firstCommittedAt, "firstCommittedAt");
        }
    }
}
