package io.tempokv.storage;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Atomically publishes the complete mapping from logical keys to immutable MVCC chains. */
public final class KeyIndex {
    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(Map.of(), Map.of()));

    /** Returns the current chain for a key, if it has ever been written. */
    public Optional<VersionChain> get(String key) {
        return Optional.ofNullable(current.get().chains().get(key));
    }

    /** Publishes one chain while preserving atomic snapshot replacement semantics. */
    public void put(String key, VersionChain chain) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(chain, "chain");
        current.updateAndGet(snapshot -> {
            Map<String, VersionChain> chains = new HashMap<>(snapshot.chains());
            chains.put(key, chain);
            return new Snapshot(chains, snapshot.boundaries());
        });
    }

    /** Returns one immutable point-in-time view used by reads and maintenance. */
    Snapshot snapshot() {
        return current.get();
    }

    /** Atomically replaces all chains and their historical boundaries. */
    void replaceAll(Map<String, VersionChain> chains, Map<String, HistoryBoundary> boundaries) {
        current.set(new Snapshot(chains, boundaries));
    }

    /** Describes whether retention truncated a key and when that key first existed. */
    record HistoryBoundary(long firstVersion, Instant firstCommittedAt, boolean truncated) {
        HistoryBoundary {
            if (firstVersion < 1) throw new IllegalArgumentException("First version must be positive");
            Objects.requireNonNull(firstCommittedAt, "firstCommittedAt");
        }

        /** Marks that at least one historical version was removed. */
        HistoryBoundary truncatedCopy() {
            return truncated ? this : new HistoryBoundary(firstVersion, firstCommittedAt, true);
        }
    }

    /** Couples chains and retention metadata in one atomic publication. */
    record Snapshot(Map<String, VersionChain> chains, Map<String, HistoryBoundary> boundaries) {
        Snapshot {
            chains = Map.copyOf(Objects.requireNonNull(chains, "chains"));
            boundaries = Map.copyOf(Objects.requireNonNull(boundaries, "boundaries"));
        }
    }
}
