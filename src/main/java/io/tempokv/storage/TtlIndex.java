package io.tempokv.storage;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Tracks upcoming expirations separately from the primary key index for future active cleanup. */
public final class TtlIndex {
    private static final Comparator<Entry> ORDER = Comparator
            .comparing(Entry::expiresAt)
            .thenComparing(Entry::key)
            .thenComparingLong(Entry::version);
    private final TreeSet<Entry> entries = new TreeSet<>(ORDER);
    private final Map<String, Entry> entriesByKey = new HashMap<>();

    /** Adds an expiration associated with one committed key version. */
    public synchronized void add(String key, long version, Instant expiresAt) {
        Entry entry = new Entry(key, version, expiresAt);
        Entry previous = entriesByKey.put(entry.key(), entry);
        if (previous != null) entries.remove(previous);
        entries.add(entry);
    }

    /** Removes any scheduled expiration superseded by a non-expiring head. */
    public synchronized void remove(String key) {
        Entry previous = entriesByKey.remove(Objects.requireNonNull(key, "key"));
        if (previous != null) entries.remove(previous);
    }

    /** Returns the next known expiration without affecting passive read semantics. */
    public synchronized Entry nextExpiration() {
        return entries.isEmpty() ? null : entries.getFirst();
    }

    /** Removes and returns the next entry only when it is due at the supplied instant. */
    public synchronized Entry pollExpired(Instant now) {
        Entry first = nextExpiration();
        if (first == null || first.expiresAt().isAfter(now)) return null;
        entries.remove(first);
        entriesByKey.remove(first.key(), first);
        return first;
    }

    /** Clears all derived entries before snapshot recovery rebuilds the index. */
    public synchronized void clear() {
        entries.clear();
        entriesByKey.clear();
    }

    /** Returns an immutable ordered copy for durable snapshots. */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
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
