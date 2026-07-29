package io.tempokv.persistence;

import io.tempokv.storage.MvccStore;
import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.Mutation;
import io.tempokv.transaction.VersionGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the two recovery guarantees that are independent from the RESP endpoint. */
class WalRecoveryTest {
    @TempDir Path directory;

    /** Recovers complete records while safely ignoring an interrupted final WAL append. */
    @Test void replaysValidPrefixAndIgnoresTornTail() throws Exception {
        FileSystemAdapter files = new FileSystemAdapter(); FileWriteAheadLog wal = new FileWriteAheadLog(directory, files, FsyncPolicy.ALWAYS);
        wal.append(new CommitRecord(1, Instant.EPOCH, List.of(Mutation.put("key", bytes("value")))));
        Path segment = segment();
        java.nio.file.Files.write(segment, new byte[] {0x54, 0x4b}, java.nio.file.StandardOpenOption.APPEND);
        MvccStore store = new MvccStore(); VersionGenerator versions = new VersionGenerator();
        new RecoveryManager(new SnapshotStore(directory, files), wal).recover(store, versions);
        assertArrayEquals(bytes("value"), store.get("key", Instant.EPOCH).orElseThrow().value()); assertEquals(1, versions.currentVersion());
    }

    /** Rejects corruption inside a complete record instead of silently losing durable history. */
    @Test void rejectsChecksumMismatchInCompleteRecord() throws Exception {
        FileSystemAdapter files = new FileSystemAdapter(); FileWriteAheadLog wal = new FileWriteAheadLog(directory, files, FsyncPolicy.ALWAYS);
        wal.append(new CommitRecord(1, Instant.EPOCH, List.of(Mutation.put("key", bytes("value")))));
        Path segment = segment(); byte[] bytes = java.nio.file.Files.readAllBytes(segment); bytes[bytes.length - 1] ^= 1; java.nio.file.Files.write(segment, bytes);
        assertThrows(java.io.IOException.class, wal::replay);
    }
    /** Rolls bounded segments while retaining strict global replay order. */
    @Test void rollsAndReplaysMultipleSegments() throws Exception {
        FileWriteAheadLog wal =
                new FileWriteAheadLog(directory, new FileSystemAdapter(), FsyncPolicy.ALWAYS, 256);
        for (int version = 1; version <= 20; version++) {
            wal.append(new CommitRecord(
                    version,
                    Instant.EPOCH.plusSeconds(version),
                    List.of(Mutation.put("key-" + version, new byte[32]))));
        }

        assertEquals(20, wal.replay().size());
        assertTrue(java.nio.file.Files.list(directory.resolve("wal")).count() > 1);
    }

    /** Rejects a complete header with an unsupported on-disk format version. */
    @Test void rejectsUnsupportedWalFormatVersion() throws Exception {
        FileWriteAheadLog wal =
                new FileWriteAheadLog(directory, new FileSystemAdapter(), FsyncPolicy.ALWAYS);
        wal.append(new CommitRecord(1, Instant.EPOCH, List.of(Mutation.put("key", bytes("value")))));
        Path segment = segment();
        byte[] bytes = java.nio.file.Files.readAllBytes(segment);
        bytes[5] = 2;
        java.nio.file.Files.write(segment, bytes);

        assertThrows(java.io.IOException.class, wal::replay);
    }

    /** Treats an incomplete record before a later segment as corruption, not a torn final tail. */
    @Test
    void rejectsTornRecordOutsideFinalSegment() throws Exception {
        FileWriteAheadLog wal =
                new FileWriteAheadLog(
                        directory,
                        new FileSystemAdapter(),
                        FsyncPolicy.ALWAYS,
                        256);
        wal.append(new CommitRecord(
                1,
                Instant.EPOCH,
                List.of(Mutation.put("first", new byte[200]))));
        wal.append(new CommitRecord(
                2,
                Instant.EPOCH.plusSeconds(1),
                List.of(Mutation.put("second", new byte[200]))));
        List<Path> segments;
        try (var paths = java.nio.file.Files.list(directory.resolve("wal"))) {
            segments = paths.filter(path -> path.toString().endsWith(".wal"))
                    .sorted()
                    .toList();
        }
        java.nio.file.Files.write(
                segments.getFirst(),
                new byte[] {0x54, 0x4b},
                java.nio.file.StandardOpenOption.APPEND);

        assertThrows(
                java.io.IOException.class,
                () -> new FileWriteAheadLog(
                        directory,
                        new FileSystemAdapter(),
                        FsyncPolicy.ALWAYS,
                        256));
    }

    private Path segment() throws Exception {
        try (var files = java.nio.file.Files.list(directory.resolve("wal"))) {
            return files.filter(path -> path.toString().endsWith(".wal")).findFirst().orElseThrow();
        }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
