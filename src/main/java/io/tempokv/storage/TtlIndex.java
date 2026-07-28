package io.tempokv.storage;

import java.time.Instant;
import java.util.Comparator;
import java.util.PriorityQueue;

/** Tracks upcoming expirations separately from the primary key index for future active cleanup. */
public final class TtlIndex {
    private final PriorityQueue<Entry> entries = new PriorityQueue<>(Comparator.comparing(Entry::expiresAt));

    /** Adds an expiration associated with one committed key version. */
    public synchronized void add(String key, long version, Instant expiresAt) { entries.add(new Entry(key, version, expiresAt)); }

    /** Returns the next known expiration without affecting passive read semantics. */
    public synchronized Entry nextExpiration() { return entries.peek(); }

    /** Describes one scheduled expiry entry. */
    public record Entry(String key, long version, Instant expiresAt) { }
}
