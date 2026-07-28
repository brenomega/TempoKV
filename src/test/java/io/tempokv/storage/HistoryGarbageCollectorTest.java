package io.tempokv.storage;

import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.Mutation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies retention by age, count, prefix override, and active snapshot protection. */
class HistoryGarbageCollectorTest {
    private static final Instant NOW = Instant.parse("2026-01-10T00:00:00Z");

    /** Applies the longest matching prefix while never collecting a version required by a snapshot. */
    @Test
    void appliesPrefixRuleAndPreservesSnapshotReferencedHistory() {
        MvccStore store = new MvccStore();
        store.apply(record(1, "users:1", NOW.minus(Duration.ofDays(3))));
        store.apply(record(2, "users:1", NOW.minus(Duration.ofDays(2))));
        store.apply(record(3, "users:1", NOW.minus(Duration.ofDays(1))));
        store.apply(record(4, "cache:1", NOW.minus(Duration.ofDays(3))));
        store.apply(record(5, "cache:1", NOW.minus(Duration.ofDays(2))));
        store.apply(record(6, "cache:1", NOW.minus(Duration.ofDays(1))));
        RetentionPolicy policy = new RetentionPolicy(new RetentionPolicy.Rule(1, Duration.ofDays(1)),
                Map.of("users:", new RetentionPolicy.Rule(2, Duration.ofDays(10))));

        assertEquals(2, new HistoryGarbageCollector(policy).collect(store, NOW, 5));
        assertEquals(List.of(3L, 2L), store.history("users:1").stream().map(VersionedValue::version).toList());
        assertEquals(List.of(6L, 5L), store.history("cache:1").stream().map(VersionedValue::version).toList());
    }

    /** Keeps the contiguous version range required by every snapshot above the oldest watermark. */
    @Test
    void preservesContiguousHistorySinceOldestActiveSnapshot() {
        MvccStore store = new MvccStore();
        for (long version = 1; version <= 6; version++) {
            store.apply(record(
                    version,
                    "key",
                    NOW.minus(Duration.ofDays(7 - version))));
        }
        RetentionPolicy policy = new RetentionPolicy(
                new RetentionPolicy.Rule(1, Duration.ofHours(1)),
                Map.of());

        assertEquals(
                2,
                new HistoryGarbageCollector(policy).collect(store, NOW, 3));
        assertEquals(
                List.of(6L, 5L, 4L, 3L),
                store.history("key").stream()
                        .map(VersionedValue::version)
                        .toList());
    }

    /** Chooses the most specific prefix rule deterministically. */
    @Test
    void selectsLongestMatchingPrefix() {
        RetentionPolicy.Rule fallback =
                new RetentionPolicy.Rule(1, Duration.ofDays(1));
        RetentionPolicy.Rule users =
                new RetentionPolicy.Rule(2, Duration.ofDays(2));
        RetentionPolicy.Rule admins =
                new RetentionPolicy.Rule(3, Duration.ofDays(3));
        RetentionPolicy policy = new RetentionPolicy(
                fallback,
                Map.of("users:", users, "users:admin:", admins));

        assertEquals(admins, policy.ruleFor("users:admin:1"));
        assertEquals(users, policy.ruleFor("users:2"));
        assertEquals(fallback, policy.ruleFor("cache:1"));
    }

    private static CommitRecord record(long version, String key, Instant at) {
        return new CommitRecord(version, at, List.of(Mutation.put(key, ("v" + version).getBytes(StandardCharsets.UTF_8))));
    }
}
