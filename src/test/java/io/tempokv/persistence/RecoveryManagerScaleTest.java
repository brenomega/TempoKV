package io.tempokv.persistence;

import io.tempokv.storage.StorageEngine;
import io.tempokv.storage.VersionedValue;
import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.Mutation;
import io.tempokv.transaction.VersionGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/** Guards the streaming recovery path against accidental full-WAL materialization. */
class RecoveryManagerScaleTest {
    private static final int RECORDS = 1_000_000;
    @TempDir Path directory;

    /** Replays one million synthetic commits with bounded live state. */
    @Test
    void streamsOneMillionRecords() {
        assertTimeout(Duration.ofSeconds(20), () -> {
            CountingStorage storage = new CountingStorage();
            VersionGenerator versions = new VersionGenerator();
            RecoveryManager recovery = new RecoveryManager(
                    new SnapshotStore(directory, new FileSystemAdapter()),
                    new SyntheticWal(RECORDS));

            assertEquals(RECORDS, recovery.recover(storage, versions));
            assertEquals(RECORDS, storage.applied);
            assertEquals(RECORDS, versions.currentVersion());
        });
    }

    private static final class SyntheticWal implements WriteAheadLog {
        private static final List<Mutation> MUTATIONS =
                List.of(Mutation.put("synthetic", "v".getBytes(StandardCharsets.UTF_8)));
        private final int records;
        private SyntheticWal(int records) { this.records = records; }
        @Override public void append(CommitRecord record) { throw new UnsupportedOperationException(); }
        @Override public void replay(Consumer<CommitRecord> consumer) {
            for (int version = 1; version <= records; version++) {
                consumer.accept(new CommitRecord(version, Instant.EPOCH, MUTATIONS));
            }
        }
        @Override public void compactThrough(long version) { }
        @Override public void close() { }
    }

    private static final class CountingStorage implements StorageEngine {
        private long applied;
        @Override public Optional<VersionedValue> get(String key, Instant now) {
            return Optional.empty();
        }
        @Override public long ttl(String key, Instant now) { return -2; }
        @Override public HistoricalValue historical(String key, Long version, Instant timestamp) {
            return HistoricalValue.missingKey();
        }
        @Override public List<VersionedValue> history(String key, int offset, int limit) {
            return List.of();
        }
        @Override public void apply(CommitRecord record) { applied++; }
    }
}
