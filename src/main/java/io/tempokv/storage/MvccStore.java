package io.tempokv.storage;

import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.Mutation;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** In-memory StorageEngine that publishes immutable key-version chains under a commit lock. */
public final class MvccStore implements StorageEngine {
    private final KeyIndex index = new KeyIndex();
    private final TtlIndex ttlIndex = new TtlIndex();

    /** Resolves only the current visible head; expired values remain in historical chains. */
    @Override public Optional<VersionedValue> get(String key, Instant now) {
        return index.get(requireKey(key)).flatMap(chain -> chain.current(Objects.requireNonNull(now, "now")));
    }

    /** Calculates the current TTL without eagerly deleting expired history. */
    @Override public long ttl(String key, Instant now) {
        Optional<VersionedValue> value = get(key, now);
        if (value.isEmpty()) return -2;
        Instant expiresAt = value.get().expiresAt();
        if (expiresAt == null) return -1;
        long seconds = Duration.between(now, expiresAt).toSeconds();
        return Math.max(0, seconds);
    }

    /** Publishes every mutation under the same commit version while retaining old chains. */
    @Override public synchronized void apply(CommitRecord record) {
        Objects.requireNonNull(record, "record");
        for (Mutation mutation : record.mutations()) applyMutation(record, mutation);
    }

    /** Exposes scheduled expirations to the E5 active-expiration worker. */
    public TtlIndex ttlIndex() { return ttlIndex; }

    /** Returns retained history for one key, primarily for future temporal reads. */
    public java.util.List<VersionedValue> history(String key) {
        return index.get(requireKey(key)).map(VersionChain::versions).orElseGet(java.util.List::of);
    }

    private void applyMutation(CommitRecord record, Mutation mutation) {
        VersionChain existing = index.get(mutation.key()).orElseGet(VersionChain::new);
        VersionedValue next = switch (mutation.type()) {
            case PUT -> new VersionedValue(record.version(), mutation.value(), false, record.committedAt(), null);
            case TOMBSTONE -> new VersionedValue(record.version(), null, true, record.committedAt(), null);
            case EXPIRE -> existing.current(record.committedAt())
                    .map(value -> new VersionedValue(record.version(), value.value(), false, record.committedAt(), mutation.expiresAt()))
                    .orElse(new VersionedValue(record.version(), null, true, record.committedAt(), null));
        };
        index.put(mutation.key(), existing.append(next));
        if (next.expiresAt() != null && !next.tombstone()) ttlIndex.add(mutation.key(), next.version(), next.expiresAt());
    }

    private static String requireKey(String key) {
        String normalized = Objects.requireNonNull(key, "key");
        if (normalized.isEmpty()) throw new IllegalArgumentException("Key must not be empty");
        return normalized;
    }
}
