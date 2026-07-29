package io.tempokv.application;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.server.Session;
import io.tempokv.storage.MvccStore;
import io.tempokv.storage.HistoryGarbageCollector;
import io.tempokv.storage.RetentionPolicy;
import io.tempokv.storage.VersionedValue;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.Mutation;
import io.tempokv.transaction.VersionGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Verifies temporal reads and append-only restoration semantics. */
class TemporalCommandHandlerTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** Resolves a prior version without changing the current head, then restores it as a new version. */
    @Test
    void readsAndRestoresRetainedVersionWithoutErasingNewerHistory() {
        MvccStore store = new MvccStore();
        CommitCoordinator commits = new CommitCoordinator(new VersionGenerator(), store, Clock.fixed(NOW, ZoneOffset.UTC));
        TemporalCommandHandler handler = new TemporalCommandHandler(store, commits, new MetricsRegistry());
        commits.commit(List.of(Mutation.put("profile", bytes("first"))));
        commits.commit(List.of(Mutation.put("profile", bytes("second"))));

        CommandResult.BulkString historical = assertInstanceOf(CommandResult.BulkString.class,
                handler.handle(TemporalCommand.getAtVersion("profile", 1), new Session()));
        assertArrayEquals(bytes("first"), historical.value());

        assertEquals(3, assertInstanceOf(CommandResult.IntegerValue.class,
                handler.handle(TemporalCommand.restoreAt("profile", 1), new Session())).value());
        assertArrayEquals(bytes("first"), store.get("profile", NOW).orElseThrow().value());
        assertEquals(List.of(3L, 2L, 1L), store.history("profile").stream().map(value -> value.version()).toList());
        assertArrayEquals(bytes("second"), assertInstanceOf(CommandResult.BulkString.class,
                handler.handle(TemporalCommand.getAtVersion("profile", 2), new Session())).value());
    }

    /** Treats tombstone, pre-creation state, and collected history as distinct outcomes. */
    @Test
    void distinguishesHistoricalTombstoneAndUnavailableHistory() {
        MvccStore store = new MvccStore();
        CommitCoordinator commits = new CommitCoordinator(new VersionGenerator(), store, Clock.fixed(NOW, ZoneOffset.UTC));
        TemporalCommandHandler handler = new TemporalCommandHandler(store, commits, new MetricsRegistry());
        commits.commit(List.of(Mutation.put("profile", bytes("first"))));
        commits.commit(List.of(Mutation.tombstone("profile")));
        commits.commit(List.of(Mutation.put("profile", bytes("recreated"))));

        assertEquals("ERR key was deleted at requested point", assertInstanceOf(CommandResult.Error.class,
                handler.handle(TemporalCommand.getAtVersion("profile", 2), new Session())).message());
        assertArrayEquals(bytes("recreated"), store.get("profile", NOW).orElseThrow().value());
        assertInstanceOf(CommandResult.NullValue.class, handler.handle(TemporalCommand.getAtVersion("unknown", 1), new Session()));
        assertInstanceOf(
                CommandResult.NullValue.class,
                handler.handle(
                        TemporalCommand.getAtTimestamp(
                                "profile", NOW.minusSeconds(1)),
                        new Session()));

        HistoryGarbageCollector collector = new HistoryGarbageCollector(
                new RetentionPolicy(
                        new RetentionPolicy.Rule(1, Duration.ofSeconds(1)),
                        Map.of()));
        collector.collect(store, NOW.plusSeconds(2), 0);
        assertEquals(
                "ERR historical value is no longer retained",
                assertInstanceOf(
                        CommandResult.Error.class,
                        handler.handle(
                                TemporalCommand.getAtVersion("profile", 1),
                                new Session()))
                        .message());
    }

    /** Calculates a binary diff instead of returning two unqualified point values. */
    @Test
    void describesChangedBinarySuffixes() {
        MvccStore store = new MvccStore();
        CommitCoordinator commits = new CommitCoordinator(
                new VersionGenerator(), store, Clock.fixed(NOW, ZoneOffset.UTC));
        TemporalCommandHandler handler =
                new TemporalCommandHandler(store, commits, new MetricsRegistry());
        commits.commit(List.of(Mutation.put("profile", bytes("prefix-old"))));
        commits.commit(List.of(Mutation.put("profile", bytes("prefix-new"))));

        CommandResult.Array diff = assertInstanceOf(
                CommandResult.Array.class,
                handler.handle(
                        TemporalCommand.diff(
                                "profile",
                                TemporalCommand.Selector.version(1),
                                TemporalCommand.Selector.version(2)),
                        new Session()));

        assertEquals("VALUE", ((CommandResult.SimpleString) diff.values().get(0)).value());
        assertEquals("VALUE", ((CommandResult.SimpleString) diff.values().get(1)).value());
        assertEquals(7, ((CommandResult.IntegerValue) diff.values().get(2)).value());
        assertArrayEquals(bytes("old"), ((CommandResult.BulkString) diff.values().get(3)).value());
        assertArrayEquals(bytes("new"), ((CommandResult.BulkString) diff.values().get(4)).value());
    }

    /** Preserves TTL and source provenance when restoring a historical expiration version. */
    @Test
    void restoresTtlAndHistoricalProvenance() {
        MvccStore store = new MvccStore();
        CommitCoordinator commits = new CommitCoordinator(
                new VersionGenerator(), store, Clock.fixed(NOW, ZoneOffset.UTC));
        TemporalCommandHandler handler =
                new TemporalCommandHandler(store, commits, new MetricsRegistry());
        commits.commit(List.of(Mutation.put("profile", bytes("value"))));
        commits.commit(List.of(
                Mutation.expire("profile", NOW.plusSeconds(60))));

        handler.handle(TemporalCommand.restoreAt("profile", 2), new Session());

        var restored = store.history("profile").getFirst();
        assertEquals(2L, restored.restoredFromVersion());
        assertEquals(NOW.plusSeconds(60), restored.expiresAt());
        assertArrayEquals(bytes("value"), restored.value());
    }

    /** Treats a value as deleted at a timestamp equal to its historical TTL deadline. */
    @Test
    void appliesExpirationToTimestampReads() {
        MvccStore store = new MvccStore();
        store.apply(new io.tempokv.transaction.CommitRecord(
                1, NOW, List.of(Mutation.put("profile", bytes("value")))));
        store.apply(new io.tempokv.transaction.CommitRecord(
                2,
                NOW.plusSeconds(5),
                List.of(Mutation.expire("profile", NOW.plusSeconds(10)))));
        TemporalCommandHandler handler = new TemporalCommandHandler(
                store,
                new CommitCoordinator(
                        new VersionGenerator(),
                        store,
                        Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC)),
                new MetricsRegistry());

        assertEquals(
                "ERR key was deleted at requested point",
                assertInstanceOf(
                        CommandResult.Error.class,
                        handler.handle(
                                TemporalCommand.getAtTimestamp(
                                        "profile", NOW.plusSeconds(10)),
                                new Session()))
                        .message());
    }

    /** Restoring any retained source appends a new head and leaves every source unchanged. */
    @Test
    void preservesAppendOnlyRestorationPropertyAcrossSources() {
        MvccStore store = new MvccStore();
        CommitCoordinator commits = new CommitCoordinator(
                new VersionGenerator(), store, Clock.fixed(NOW, ZoneOffset.UTC));
        TemporalCommandHandler handler =
                new TemporalCommandHandler(store, commits, new MetricsRegistry());
        for (int version = 1; version <= 20; version++) {
            commits.commit(List.of(
                    Mutation.put("profile", bytes("v" + version))));
        }
        List<byte[]> sources = store.history("profile").stream()
                .map(VersionedValue::value)
                .toList();

        for (int source = 1; source <= 20; source++) {
            handler.handle(
                    TemporalCommand.restoreAt("profile", source),
                    new Session());
        }

        assertEquals(40, store.history("profile").size());
        for (int index = 0; index < sources.size(); index++) {
            assertArrayEquals(
                    sources.get(index),
                    store.history("profile").get(index + 20).value());
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
