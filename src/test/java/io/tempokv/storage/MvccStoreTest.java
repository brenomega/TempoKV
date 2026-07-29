package io.tempokv.storage;

import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.Mutation;
import io.tempokv.transaction.VersionGenerator;
import io.tempokv.transaction.CommitRecord;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies the E3 MVCC visibility and history invariants with a deterministic clock. */
class MvccStoreTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Retains each write and deletion as an ordered immutable version chain. */
    @Test
    void keepsVersionedHistoryWhileMakingOnlyLatestHeadVisible() {
        MvccStore store = new MvccStore();
        CommitCoordinator commits = new CommitCoordinator(new VersionGenerator(), store, clock);

        commits.commit(List.of(Mutation.put("key", bytes("v1"))));
        commits.commit(List.of(Mutation.put("key", bytes("v2"))));
        commits.commit(List.of(Mutation.tombstone("key")));

        assertFalse(store.get("key", NOW).isPresent());
        assertEquals(List.of(3L, 2L, 1L), store.history("key").stream().map(VersionedValue::version).toList());
        assertTrue(store.history("key").getFirst().tombstone());
        assertArrayEquals(bytes("v2"), store.history("key").get(1).value());
    }

    /** Makes expiration passive: the value becomes absent but its version remains in history. */
    @Test
    void treatsExpiredValueAsAbsentWithoutRemovingItsVersion() {
        MvccStore store = new MvccStore();
        CommitCoordinator commits = new CommitCoordinator(new VersionGenerator(), store, clock);
        commits.commit(List.of(Mutation.put("key", bytes("value"))));
        commits.commit(List.of(Mutation.expire("key", NOW.plusSeconds(10))));

        assertEquals(10, store.ttl("key", NOW));
        assertTrue(store.get("key", NOW.plusSeconds(10)).isEmpty());
        assertEquals(-2, store.ttl("key", NOW.plusSeconds(10)));
        assertEquals(2, store.history("key").size());
    }

    /** Keeps only the current expiry per key so repeated TTL changes cannot grow the index forever. */
    @Test
    void replacesObsoleteTtlIndexEntries() {
        MvccStore store = new MvccStore();
        store.apply(new CommitRecord(
                1, NOW, List.of(Mutation.put("key", bytes("value")))));
        store.apply(new CommitRecord(
                2,
                NOW.plusSeconds(1),
                List.of(Mutation.expire("key", NOW.plusSeconds(100)))));
        store.apply(new CommitRecord(
                3,
                NOW.plusSeconds(2),
                List.of(Mutation.expire("key", NOW.plusSeconds(200)))));

        assertEquals(List.of(3L), store.ttlIndex().entries().stream()
                .map(TtlIndex.Entry::version)
                .toList());

        store.apply(new CommitRecord(
                4,
                NOW.plusSeconds(3),
                List.of(Mutation.put("key", bytes("replacement")))));

        assertTrue(store.ttlIndex().entries().isEmpty());
    }

    /** Selects the newest commit when timestamps are equal and respects historical TTL boundaries. */
    @Test
    void resolvesEqualTimestampsAndHistoricalExpiration() {
        MvccStore store = new MvccStore();
        store.apply(new CommitRecord(
                1, NOW, List.of(Mutation.put("key", bytes("v1")))));
        store.apply(new CommitRecord(
                2, NOW, List.of(Mutation.put("key", bytes("v2")))));
        store.apply(new CommitRecord(
                3,
                NOW.plusSeconds(5),
                List.of(Mutation.expire("key", NOW.plusSeconds(10)))));

        assertArrayEquals(
                bytes("v2"),
                store.historical("key", null, NOW).value().value());
        assertTrue(store.historical(
                "key", null, NOW.plusSeconds(10)).value().expiresAt()
                .equals(NOW.plusSeconds(10)));
        assertFalse(store.historical(
                        "key", null, NOW.plusSeconds(10))
                .value()
                .isVisibleAt(NOW.plusSeconds(10)));
    }

    /** Rejects an invalid repeated-key commit without publishing its first mutation. */
    @Test
    void doesNotPartiallyPublishInvalidCommit() {
        MvccStore store = new MvccStore();
        CommitRecord invalid = new CommitRecord(
                1,
                NOW,
                List.of(
                        Mutation.put("key", bytes("v1")),
                        Mutation.put("key", bytes("v2"))));

        assertThrows(IllegalArgumentException.class, () -> store.apply(invalid));
        assertTrue(store.get("key", NOW).isEmpty());
    }

    /** Preserves monotonic lookup invariants across a deep immutable version chain. */
    @Test
    void preservesVersionLookupPropertyAcrossManyWrites() {
        MvccStore store = new MvccStore();
        for (long version = 1; version <= 200; version++) {
            store.apply(new CommitRecord(
                    version,
                    NOW.plusSeconds(version),
                    List.of(Mutation.put("key", bytes("v" + version)))));
        }

        for (long version = 1; version <= 200; version++) {
            assertArrayEquals(
                    bytes("v" + version),
                    store.historical("key", version, null).value().value());
        }
        assertEquals(
                200L,
                store.history("key").getFirst().version());
    }

    /** Uses sparse checkpoints for deep non-contiguous version and timestamp lookups. */
    @Test
    void resolvesSparseDeepHistoryAcrossKeysAndMissingPoints() {
        MvccStore store = new MvccStore();
        for (long version = 1; version <= 512; version++) {
            String key = version % 2 == 0 ? "even" : "odd";
            store.apply(new CommitRecord(
                    version,
                    NOW.plusMillis(version),
                    List.of(Mutation.put(key, bytes("v" + version)))));
        }

        assertEquals(
                512L,
                store.historical("even", 512L, null).value().version());
        assertEquals(
                256L,
                store.historical("even", 257L, null).value().version());
        assertEquals(
                2L,
                store.historical("even", 2L, null).value().version());
        assertEquals(
                256L,
                store.historical(
                        "even", null, NOW.plusMillis(257))
                        .value().version());
        assertEquals(
                2L,
                store.historical(
                        "even", null, NOW.plusMillis(2))
                        .value().version());
        assertEquals(
                StorageEngine.HistoricalValue.Status.KEY_NOT_FOUND,
                store.historical("even", 1L, null).status());

        VersionChain chain = store.snapshot().chains().get("even");
        assertTrue(chain.checkpointCount() > 0);
        assertTrue(chain.checkpointCount() <= 256 / 64);
    }

    /** Falls back to exact traversal if restored timestamps are not monotonic. */
    @Test
    void preservesTimestampSemanticsForNonMonotonicClockHistory() {
        VersionChain chain = VersionChain.fromNewestFirst(List.of(
                value(3, NOW.plusSeconds(1)),
                value(2, NOW.plusSeconds(3)),
                value(1, NOW)));

        assertEquals(
                3L,
                chain.atTimestamp(NOW.plusSeconds(2))
                        .orElseThrow().version());
        assertEquals(
                1L,
                chain.atTimestamp(NOW).orElseThrow().version());
    }

    /** Keeps current reads valid while another thread repeatedly publishes a new immutable head. */
    @Test
    void readsRemainConsistentDuringConcurrentHeadPublication() throws Exception {
        MvccStore store = new MvccStore();
        store.apply(new CommitRecord(
                1, NOW, List.of(Mutation.put("key", bytes("v1")))));
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> failures =
                new ConcurrentLinkedQueue<>();
        Thread writer = Thread.ofVirtual().start(() -> {
            try {
                start.await();
                for (long version = 2; version <= 500; version++) {
                    store.apply(new CommitRecord(
                            version,
                            NOW.plusSeconds(version),
                            List.of(Mutation.put(
                                    "key", bytes("v" + version)))));
                }
            } catch (Throwable failure) {
                failures.add(failure);
            }
        });
        List<Thread> readers = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        for (int read = 0; read < 2_000; read++) {
                            String value = new String(
                                    store.get(
                                                    "key",
                                                    NOW.plusSeconds(2_000))
                                            .orElseThrow()
                                            .value(),
                                    StandardCharsets.UTF_8);
                            if (!value.startsWith("v")) {
                                throw new AssertionError(
                                        "Observed malformed head: " + value);
                            }
                            if ((read & 63) == 0) Thread.yield();
                        }
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                }))
                .toList();
        start.countDown();
        writer.join();
        for (Thread reader : readers) reader.join();

        assertTrue(failures.isEmpty(), () -> "Concurrent failures: " + failures);
        assertArrayEquals(
                bytes("v500"),
                store.get("key", NOW.plusSeconds(2_000))
                        .orElseThrow()
                        .value());
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static VersionedValue value(long version, Instant committedAt) {
        return new VersionedValue(
                version, bytes("v" + version), false, committedAt, null, null);
    }
}
