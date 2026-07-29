package io.tempokv.storage;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Atomically publishes the complete mapping from logical keys to immutable MVCC chains. */
public final class KeyIndex {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, VersionChain> chains = new HashMap<>();
    private final Map<String, HistoryBoundary> boundaries = new HashMap<>();

    /** Returns the current chain for a key, if it has ever been written. */
    public Optional<VersionChain> get(String key) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(chains.get(key));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns one key's chain and retention boundary from the same read-side critical section. */
    KeyState state(String key) {
        lock.readLock().lock();
        try {
            return new KeyState(chains.get(key), boundaries.get(key));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Publishes one chain while preserving atomic snapshot replacement semantics. */
    public void put(String key, VersionChain chain) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(chain, "chain");
        lock.writeLock().lock();
        try {
            chains.put(key, chain);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Returns one immutable point-in-time view used by reads and maintenance. */
    Snapshot snapshot() {
        lock.readLock().lock();
        try {
            return new Snapshot(chains, boundaries);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Atomically replaces all chains and their historical boundaries. */
    void replaceAll(Map<String, VersionChain> chains, Map<String, HistoryBoundary> boundaries) {
        lock.writeLock().lock();
        try {
            this.chains.clear();
            this.chains.putAll(Objects.requireNonNull(chains, "chains"));
            this.boundaries.clear();
            this.boundaries.putAll(
                    Objects.requireNonNull(boundaries, "boundaries"));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Applies a fully validated set of changed entries as one reader-invisible publication. */
    void replaceEntries(
            Map<String, VersionChain> chains,
            Map<String, HistoryBoundary> boundaries) {
        lock.writeLock().lock();
        try {
            this.chains.putAll(Objects.requireNonNull(chains, "chains"));
            this.boundaries.putAll(
                    Objects.requireNonNull(boundaries, "boundaries"));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Runs a package-local atomic update while all current-state readers are excluded. */
    void update(Updater updater) {
        lock.writeLock().lock();
        try {
            Objects.requireNonNull(updater, "updater").update(chains, boundaries);
        } finally {
            lock.writeLock().unlock();
        }
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

    /** Couples one key's immutable chain and historical boundary. */
    record KeyState(VersionChain chain, HistoryBoundary boundary) {}

    @FunctionalInterface
    interface Updater {
        void update(
                Map<String, VersionChain> chains,
                Map<String, HistoryBoundary> boundaries);
    }
}
