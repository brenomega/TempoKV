package io.tempokv.transaction;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.server.Session;
import io.tempokv.storage.MvccStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies stable snapshots, atomic write sets, rollback, and deterministic write conflicts. */
class TransactionManagerTest {
    private static final Instant NOW =
            Instant.parse("2026-07-29T12:00:00Z");

    /** Keeps a transaction read stable while later commits become visible outside its session. */
    @Test
    void readsStableSnapshotAndOwnStagedValue() {
        Fixture fixture = new Fixture();
        fixture.commits.commit(List.of(Mutation.put("key", bytes("before"))));
        Session session = new Session();
        fixture.transactions.begin(session);

        fixture.commits.commit(List.of(Mutation.put("key", bytes("outside"))));
        assertArrayEquals(
                bytes("before"),
                fixture.transactions.get(session, "key", NOW)
                        .orElseThrow().value());

        fixture.transactions.stage(session, Mutation.put("other", bytes("staged")));
        assertArrayEquals(
                bytes("staged"),
                fixture.transactions.get(session, "other", NOW)
                        .orElseThrow().value());
        assertFalse(fixture.storage.get("other", NOW).isPresent());

        assertTrue(fixture.transactions.commit(session).committed());
        assertEquals(3L, fixture.storage.latestVersion("other"));
    }

    /** Uses a barrier so simultaneous writers deterministically yield one commit and one abort. */
    @Test
    void abortsExactlyOneConcurrentWriterBeforePublication() throws Exception {
        Fixture fixture = new Fixture();
        Session first = new Session();
        Session second = new Session();
        fixture.transactions.begin(first);
        fixture.transactions.begin(second);
        fixture.transactions.stage(first, Mutation.put("shared", bytes("first")));
        fixture.transactions.stage(second, Mutation.put("shared", bytes("second")));
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstCommit = executor.submit(() -> {
                barrier.await();
                return fixture.transactions.commit(first);
            });
            var secondCommit = executor.submit(() -> {
                barrier.await();
                return fixture.transactions.commit(second);
            });
            List<TransactionManager.CommitOutcome> outcomes =
                    List.of(firstCommit.get(), secondCommit.get());

            assertEquals(1, outcomes.stream()
                    .filter(TransactionManager.CommitOutcome::committed)
                    .count());
            assertEquals(1, outcomes.stream()
                    .filter(TransactionManager.CommitOutcome::conflicted)
                    .count());
            assertEquals(List.of("shared"), outcomes.stream()
                    .filter(TransactionManager.CommitOutcome::conflicted)
                    .findFirst().orElseThrow().conflictingKeys());
            assertEquals(1, fixture.storage.history("shared").size());
            assertEquals(1L, fixture.versions.currentVersion());
        }
    }

    /** Discards staged bytes and releases the snapshot without allocating a version. */
    @Test
    void rollbackDoesNotAllocateVersion() {
        Fixture fixture = new Fixture();
        Session session = new Session();
        fixture.transactions.begin(session);
        fixture.transactions.stage(session, Mutation.put("key", bytes("discarded")));

        fixture.transactions.rollback(session);

        assertEquals(0L, fixture.versions.currentVersion());
        assertEquals(0, fixture.snapshots.activeCount());
        assertFalse(fixture.storage.get("key", NOW).isPresent());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class Fixture {
        private final VersionGenerator versions = new VersionGenerator();
        private final MvccStore storage = new MvccStore();
        private final CommitCoordinator commits = new CommitCoordinator(
                versions,
                storage,
                Clock.fixed(NOW, ZoneOffset.UTC));
        private final SnapshotManager snapshots =
                new SnapshotManager(commits::currentVersion);
        private final TransactionManager transactions = new TransactionManager(
                storage,
                commits,
                snapshots,
                new ConflictDetector(storage),
                new MetricsRegistry());
    }
}
