package io.tempokv.storage;

import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.Mutation;
import io.tempokv.transaction.VersionGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
