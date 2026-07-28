package io.tempokv.storage;

import io.tempokv.transaction.CommitRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Defines protocol-independent access to current MVCC state and committed mutations. */
public interface StorageEngine {
    /** Returns the currently visible value for a key, applying passive expiration. */
    Optional<VersionedValue> get(String key, Instant now);

    /** Returns Redis-compatible remaining TTL seconds, or -1/-2 for persistent/missing keys. */
    long ttl(String key, Instant now);

    /** Resolves the version visible at a historical version or timestamp without changing storage state. */
    HistoricalValue historical(String key, Long version, Instant timestamp);

    /** Returns a bounded newest-first page of retained versions for a key. */
    List<VersionedValue> history(String key, int offset, int limit);

    /** Applies every mutation in a previously validated commit as one publication. */
    void apply(CommitRecord record);

    /** Distinguishes an absent key from history that retention has already removed. */
    record HistoricalValue(Status status, VersionedValue value) {
        /** Identifies the result of a historical lookup. */
        public enum Status { FOUND, KEY_NOT_FOUND, HISTORY_UNAVAILABLE }
        /** Validates that only successful lookups expose a version. */
        public HistoricalValue {
            if ((status == Status.FOUND) != (value != null)) throw new IllegalArgumentException("Historical result status and value disagree");
        }
        /** Creates a successful historical lookup result. */
        public static HistoricalValue found(VersionedValue value) { return new HistoricalValue(Status.FOUND, value); }
        /** Creates a result for a key that has never been written. */
        public static HistoricalValue missingKey() { return new HistoricalValue(Status.KEY_NOT_FOUND, null); }
        /** Creates a result when the requested point predates retained history. */
        public static HistoricalValue unavailable() { return new HistoricalValue(Status.HISTORY_UNAVAILABLE, null); }
    }
}
