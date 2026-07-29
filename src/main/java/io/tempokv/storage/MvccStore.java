package io.tempokv.storage;

import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.Mutation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** In-memory StorageEngine that atomically publishes immutable key-version snapshots. */
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

    /** Builds all affected chains first and publishes the complete commit with one atomic swap. */
    @Override public synchronized void apply(CommitRecord record) {
        Objects.requireNonNull(record, "record");
        KeyIndex.Snapshot snapshot = index.snapshot();
        Map<String, VersionChain> updated = new HashMap<>(snapshot.chains());
        Map<String, KeyIndex.HistoryBoundary> boundaries = new HashMap<>(snapshot.boundaries());
        List<TtlIndex.Entry> expirations = new ArrayList<>();
        for (Mutation mutation : record.mutations()) {
            applyMutation(updated, record, mutation, expirations);
            boundaries.putIfAbsent(mutation.key(),
                    new KeyIndex.HistoryBoundary(record.version(), record.committedAt(), false));
        }
        index.replaceAll(updated, boundaries);
        expirations.forEach(entry -> ttlIndex.add(entry.key(), entry.version(), entry.expiresAt()));
    }

    /** Exposes scheduled expirations to the E5 active-expiration worker. */
    public TtlIndex ttlIndex() { return ttlIndex; }

    /** Captures an atomic retained-state view suitable for durable persistence. */
    @Override public StorageSnapshot snapshot() {
        KeyIndex.Snapshot snapshot = index.snapshot();
        long version = snapshot.chains().values().stream().map(VersionChain::versions)
                .flatMap(List::stream).mapToLong(VersionedValue::version).max().orElse(0);
        Map<String, StorageSnapshot.HistoryBoundary> boundaries = new HashMap<>();
        snapshot.boundaries().forEach((key, boundary) -> boundaries.put(
                key,
                new StorageSnapshot.HistoryBoundary(
                        boundary.firstVersion(),
                        boundary.firstCommittedAt(),
                        boundary.truncated())));
        return new StorageSnapshot(version, snapshot.chains(), boundaries, ttlIndex.entries());
    }

    /** Restores chains and rebuilds the derived TTL index from their retained heads. */
    @Override public synchronized void restore(StorageSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<String, KeyIndex.HistoryBoundary> boundaries = new HashMap<>();
        snapshot.boundaries().forEach((key, boundary) -> boundaries.put(
                key,
                new KeyIndex.HistoryBoundary(
                        boundary.firstVersion(),
                        boundary.firstCommittedAt(),
                        boundary.truncated())));
        index.replaceAll(snapshot.chains(), boundaries);
        ttlIndex.clear();
        snapshot.expirations().forEach(
                entry -> ttlIndex.add(entry.key(), entry.version(), entry.expiresAt()));
    }

    /** Returns the highest version in the atomically published state. */
    @Override public long currentVersion() { return snapshot().version(); }

    /** Checks whether a scheduled expiration still refers to the current version. */
    public boolean isCurrentVersion(String key, long version, Instant now) {
        return index.get(requireKey(key)).map(VersionChain::versions)
                .filter(values -> !values.isEmpty())
                .map(values -> values.getFirst())
                .map(value -> !value.tombstone() && value.version() == version
                        && value.expiresAt() != null && !value.expiresAt().isAfter(now))
                .orElse(false);
    }

    /** Returns all retained versions for one key in newest-first order. */
    public List<VersionedValue> history(String key) {
        return index.get(requireKey(key)).map(VersionChain::versions).orElseGet(List::of);
    }

    /** Resolves a retained point while distinguishing pre-creation state from collected history. */
    @Override public StorageEngine.HistoricalValue historical(String key, Long version, Instant timestamp) {
        if ((version == null) == (timestamp == null)) {
            throw new IllegalArgumentException("Specify exactly one historical selector");
        }
        String normalizedKey = requireKey(key);
        KeyIndex.Snapshot snapshot = index.snapshot();
        VersionChain chain = snapshot.chains().get(normalizedKey);
        if (chain == null) return StorageEngine.HistoricalValue.missingKey();
        Optional<VersionedValue> selected =
                version == null ? chain.atTimestamp(timestamp) : chain.atVersion(version);
        if (selected.isPresent()) return StorageEngine.HistoricalValue.found(selected.orElseThrow());
        KeyIndex.HistoryBoundary boundary = snapshot.boundaries().get(normalizedKey);
        boolean beforeCreation = version != null
                ? version < boundary.firstVersion()
                : timestamp.isBefore(boundary.firstCommittedAt());
        if (beforeCreation) return StorageEngine.HistoricalValue.missingKey();
        return boundary.truncated()
                ? StorageEngine.HistoricalValue.unavailable()
                : StorageEngine.HistoricalValue.missingKey();
    }

    /** Returns a bounded retained-history page so protocol responses cannot grow without limit. */
    @Override public List<VersionedValue> history(String key, int offset, int limit) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("Invalid history page");
        List<VersionedValue> versions = history(key);
        if (offset >= versions.size()) return List.of();
        return versions.subList(offset, Math.min(versions.size(), offset + limit));
    }

    /** Atomically replaces chains with maintenance-approved retained prefixes. */
    synchronized int retainHistory(RetentionPolicy policy, Instant now, long oldestSnapshotVersion) {
        KeyIndex.Snapshot snapshot = index.snapshot();
        Map<String, VersionChain> updated = new HashMap<>(snapshot.chains());
        Map<String, KeyIndex.HistoryBoundary> boundaries = new HashMap<>(snapshot.boundaries());
        int removed = 0;
        for (Map.Entry<String, VersionChain> entry : snapshot.chains().entrySet()) {
            List<VersionedValue> versions = entry.getValue().versions();
            List<VersionedValue> retained =
                    policy.retain(entry.getKey(), versions, now, oldestSnapshotVersion);
            int removedFromKey = versions.size() - retained.size();
            if (removedFromKey == 0) continue;
            removed += removedFromKey;
            updated.put(entry.getKey(), VersionChain.fromNewestFirst(retained));
            boundaries.computeIfPresent(entry.getKey(), (ignored, boundary) -> boundary.truncatedCopy());
        }
        if (removed > 0) index.replaceAll(updated, boundaries);
        return removed;
    }

    private static void applyMutation(
            Map<String, VersionChain> updated,
            CommitRecord record,
            Mutation mutation,
            List<TtlIndex.Entry> expirations) {
        VersionChain existing = updated.getOrDefault(mutation.key(), new VersionChain());
        VersionedValue next = switch (mutation.type()) {
            case PUT -> new VersionedValue(
                    record.version(), mutation.value(), false, record.committedAt(), null, null);
            case TOMBSTONE -> new VersionedValue(
                    record.version(), null, true, record.committedAt(), null, null);
            case EXPIRED_TOMBSTONE -> new VersionedValue(
                    record.version(),
                    null,
                    true,
                    record.committedAt(),
                    null,
                    null,
                    VersionedValue.TombstoneReason.EXPIRED);
            case EXPIRE -> existing.current(record.committedAt())
                    .map(value -> new VersionedValue(
                            record.version(), value.value(), false, record.committedAt(),
                            mutation.expiresAt(), null))
                    .orElse(new VersionedValue(
                            record.version(), null, true, record.committedAt(), null, null));
            case RESTORE_PUT -> new VersionedValue(
                    record.version(), mutation.value(), false, record.committedAt(),
                    mutation.expiresAt(), mutation.restoredFromVersion());
            case RESTORE_TOMBSTONE -> new VersionedValue(
                    record.version(), null, true, record.committedAt(),
                    null, mutation.restoredFromVersion());
        };
        updated.put(mutation.key(), existing.append(next));
        if (next.expiresAt() != null && !next.tombstone()) {
            expirations.add(new TtlIndex.Entry(mutation.key(), next.version(), next.expiresAt()));
        }
    }

    private static String requireKey(String key) {
        String normalized = Objects.requireNonNull(key, "key");
        if (normalized.isEmpty()) throw new IllegalArgumentException("Key must not be empty");
        return normalized;
    }
}
