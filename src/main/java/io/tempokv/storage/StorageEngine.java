package io.tempokv.storage;

import io.tempokv.transaction.CommitRecord;
import java.time.Instant;
import java.util.Optional;

/** Defines protocol-independent access to current MVCC state and committed mutations. */
public interface StorageEngine {
    /** Returns the currently visible value for a key, applying passive expiration. */
    Optional<VersionedValue> get(String key, Instant now);

    /** Returns Redis-compatible remaining TTL seconds, or -1/-2 for persistent/missing keys. */
    long ttl(String key, Instant now);

    /** Applies every mutation in a previously validated commit as one publication. */
    void apply(CommitRecord record);
}
