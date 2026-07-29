package io.tempokv.storage;

import java.time.Instant;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.List;
import java.util.Objects;

/** Tracks upcoming expirations separately from the primary key index for future active cleanup. */
public final class TtlIndex {
    private final PriorityQueue<Entry> entries = new PriorityQueue<>(Comparator.comparing(Entry::expiresAt));

    /** Adds an expiration associated with one committed key version. */
    public synchronized void add(String key, long version, Instant expiresAt) { entries.add(new Entry(key, version, expiresAt)); }

    /** Returns the next known expiration without affecting passive read semantics. */
    public synchronized Entry nextExpiration() { return entries.peek(); }

    /** Removes and returns the next entry only when it is due at the supplied instant. */
    public synchronized Entry pollExpired(Instant now) {
        if (entries.peek() == null || entries.peek().expiresAt().isAfter(now)) return null;
        return entries.poll();
    }

    /** Clears all derived entries before snapshot recovery rebuilds the index. */
    public synchronized void clear() { entries.clear(); }

    /** Returns an immutable ordered copy for durable snapshots. */
    public synchronized List<Entry> entries() {
        return entries.stream().sorted(Comparator.comparing(Entry::expiresAt)).toList();
    }

    /** Describes one scheduled expiry entry. */
    public record Entry(String key, long version, Instant expiresAt) {
        /** Validates a complete association between key version and deadline. */
        public Entry {
            key = Objects.requireNonNull(key, "key");
            if (key.isEmpty()) throw new IllegalArgumentException("TTL key must not be empty");
            if (version < 1) throw new IllegalArgumentException("TTL version must be positive");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
