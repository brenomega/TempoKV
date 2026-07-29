package io.tempokv.persistence;

import io.tempokv.storage.HistoryGarbageCollector;
import io.tempokv.storage.MvccStore;
import io.tempokv.storage.RetentionPolicy;
import io.tempokv.storage.StorageEngine;
import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.Mutation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies checksummed snapshot publication, fallback, TTL, and retention metadata. */
class SnapshotStoreTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    @TempDir Path directory;

    /** Round-trips retained-history boundaries and scheduled TTL entries. */
    @Test
    void preservesRetentionAndTtlState() throws Exception {
        MvccStore source = new MvccStore();
        source.apply(record(1, Mutation.put("key", bytes("old"))));
        source.apply(record(2, Mutation.put("key", bytes("current"))));
        source.apply(record(3, Mutation.expire("key", NOW.plusSeconds(60))));
        new HistoryGarbageCollector(new RetentionPolicy(
                new RetentionPolicy.Rule(1, Duration.ofNanos(1)), Map.of()))
                .collect(source, NOW.plusSeconds(1), 0);
        SnapshotStore snapshots = new SnapshotStore(directory, new FileSystemAdapter());
        snapshots.save(source.snapshot());

        MvccStore restored = new MvccStore();
        restored.restore(snapshots.load().orElseThrow());

        assertEquals(60, restored.ttl("key", NOW));
        assertEquals(
                StorageEngine.HistoricalValue.Status.HISTORY_UNAVAILABLE,
                restored.historical("key", 2L, null).status());
        assertEquals(3, restored.ttlIndex().nextExpiration().version());
    }

    /** Ignores a corrupt newest snapshot and returns the preceding valid cut. */
    @Test
    void fallsBackToPreviousValidSnapshot() throws Exception {
        FileSystemAdapter files = new FileSystemAdapter();
        SnapshotStore snapshots = new SnapshotStore(directory, files);
        MvccStore store = new MvccStore();
        store.apply(record(1, Mutation.put("key", bytes("first"))));
        snapshots.save(store.snapshot());
        store.apply(record(2, Mutation.put("key", bytes("second"))));
        snapshots.save(store.snapshot());
        Path newest = files.listRegularFiles(directory.resolve("snapshots"), ".snapshot").getLast();
        byte[] bytes = files.readAllBytes(newest);
        bytes[bytes.length - 1] ^= 1;
        java.nio.file.Files.write(newest, bytes);

        assertEquals(1, snapshots.load().orElseThrow().version());
        assertEquals(0, snapshots.safeCompactionVersion());
    }

    /** A failure before rename leaves no published snapshot. */
    @Test
    void failureBeforeRenameDoesNotPublishPartialSnapshot() throws Exception {
        SnapshotStore snapshots = new SnapshotStore(directory, new FailingMoveFileSystem(false));
        MvccStore store = new MvccStore();
        store.apply(record(1, Mutation.put("key", bytes("value"))));

        assertThrows(IOException.class, () -> snapshots.save(store.snapshot()));
        assertTrue(snapshots.load().isEmpty());
    }

    /** A reported failure after rename still leaves the complete validated artifact recoverable. */
    @Test
    void failureAfterRenameLeavesCompleteSnapshot() throws Exception {
        SnapshotStore snapshots = new SnapshotStore(directory, new FailingMoveFileSystem(true));
        MvccStore store = new MvccStore();
        store.apply(record(1, Mutation.put("key", bytes("value"))));

        assertThrows(IOException.class, () -> snapshots.save(store.snapshot()));
        assertEquals(1, snapshots.load().orElseThrow().version());
    }

    private static CommitRecord record(long version, Mutation mutation) {
        return new CommitRecord(version, NOW, List.of(mutation));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class FailingMoveFileSystem extends FileSystemAdapter {
        private final boolean moveFirst;
        private FailingMoveFileSystem(boolean moveFirst) { this.moveFirst = moveFirst; }
        @Override
        public void moveAtomically(Path source, Path target) throws IOException {
            if (moveFirst) super.moveAtomically(source, target);
            throw new IOException(moveFirst ? "failure after rename" : "failure before rename");
        }
    }
}
