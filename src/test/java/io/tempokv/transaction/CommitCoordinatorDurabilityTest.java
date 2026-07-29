package io.tempokv.transaction;

import io.tempokv.persistence.WriteAheadLog;
import io.tempokv.storage.MvccStore;
import io.tempokv.storage.StorageEngine;
import io.tempokv.storage.VersionedValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises crash boundaries around durable append and atomic storage publication. */
class CommitCoordinatorDurabilityTest {
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    /** A WAL failure prevents publication and produces a protocol-safe commit failure. */
    @Test
    void doesNotPublishWhenDurableAppendFails() {
        MvccStore store = new MvccStore();
        CommitCoordinator commits = new CommitCoordinator(
                new VersionGenerator(),
                store,
                CLOCK,
                ignored -> { throw new IOException("fsync failed"); });

        assertThrows(
                CommitFailedException.class,
                () -> commits.commit(List.of(Mutation.put("key", bytes("value")))));
        assertTrue(store.get("key", Instant.EPOCH).isEmpty());
    }

    /** A crash after append but before publication is recovered from the durable record. */
    @Test
    void durableRecordSurvivesFailureBeforePublication() throws Exception {
        InMemoryWal wal = new InMemoryWal();
        CommitCoordinator commits = new CommitCoordinator(
                new VersionGenerator(),
                new FailingStorage(),
                CLOCK,
                wal::append);

        assertThrows(
                IllegalStateException.class,
                () -> commits.commit(List.of(Mutation.put("key", bytes("value")))));
        MvccStore recovered = new MvccStore();
        wal.replay(recovered::apply);

        assertArrayEquals(
                bytes("value"),
                recovered.get("key", Instant.EPOCH).orElseThrow().value());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class FailingStorage implements StorageEngine {
        @Override public Optional<VersionedValue> get(String key, Instant now) { return Optional.empty(); }
        @Override public long ttl(String key, Instant now) { return -2; }
        @Override public HistoricalValue historical(String key, Long version, Instant timestamp) {
            return HistoricalValue.missingKey();
        }
        @Override public List<VersionedValue> history(String key, int offset, int limit) {
            return List.of();
        }
        @Override public void apply(CommitRecord record) {
            throw new IllegalStateException("simulated crash point");
        }
    }

    private static final class InMemoryWal implements WriteAheadLog {
        private final List<CommitRecord> records = new ArrayList<>();
        @Override public void append(CommitRecord record) { records.add(record); }
        @Override public void replay(Consumer<CommitRecord> consumer) { records.forEach(consumer); }
        @Override public void compactThrough(long version) {
            records.removeIf(record -> record.version() <= version);
        }
        @Override public void close() { }
    }
}
