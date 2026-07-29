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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies active expiration creates an auditable tombstone rather than deleting history. */
class ExpirationWorkerTest {
    /** Turns a due TTL entry into exactly one new tombstone version. */
    @Test void expiresCurrentDueValueAsTombstone() {
        Instant now = Instant.parse("2026-01-01T00:00:10Z"); Clock clock = Clock.fixed(now, ZoneOffset.UTC); MvccStore store = new MvccStore();
        VersionGenerator versions = new VersionGenerator(); versions.advanceTo(2);
        CommitCoordinator commits = new CommitCoordinator(versions, store, clock);
        store.apply(new io.tempokv.transaction.CommitRecord(1, now.minusSeconds(20), List.of(Mutation.put("key", "value".getBytes(StandardCharsets.UTF_8)))));
        store.apply(new io.tempokv.transaction.CommitRecord(2, now.minusSeconds(10), List.of(Mutation.expire("key", now))));
        new ExpirationWorker(store, commits, clock).expireDue();
        assertTrue(store.get("key", now).isEmpty()); assertEquals(List.of(3L, 2L, 1L), store.history("key").stream().map(VersionedValue::version).toList());
        assertEquals(
                VersionedValue.TombstoneReason.EXPIRED,
                store.history("key").getFirst().tombstoneReason());
    }

    /** Discards a due entry superseded by a newer value without creating another version. */
    @Test void ignoresObsoleteExpirationEntry() {
        Instant now = Instant.parse("2026-01-01T00:00:10Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        MvccStore store = new MvccStore();
        VersionGenerator versions = new VersionGenerator();
        CommitCoordinator commits = new CommitCoordinator(versions, store, clock);
        commits.commit(List.of(Mutation.put("key", "old".getBytes(StandardCharsets.UTF_8))));
        commits.commit(List.of(Mutation.expire("key", now)));
        commits.commit(List.of(Mutation.put("key", "new".getBytes(StandardCharsets.UTF_8))));

        new ExpirationWorker(store, commits, clock).expireDue();

        assertEquals(3, store.history("key").size());
        assertEquals(3, store.history("key").getFirst().version());
    }
}
