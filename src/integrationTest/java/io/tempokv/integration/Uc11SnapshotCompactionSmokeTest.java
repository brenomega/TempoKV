package io.tempokv.integration;

import io.tempokv.bootstrap.TempoKvApplication;
import io.tempokv.bootstrap.TempoKvServer;
import io.tempokv.persistence.FileSystemAdapter;
import io.tempokv.persistence.FileWriteAheadLog;
import io.tempokv.persistence.FsyncPolicy;
import io.tempokv.persistence.RecoveryManager;
import io.tempokv.persistence.SnapshotStore;
import io.tempokv.persistence.SnapshotWriter;
import io.tempokv.persistence.WalCompactor;
import io.tempokv.storage.MvccStore;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.Mutation;
import io.tempokv.transaction.VersionGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.net.Socket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises UC-11 using real files: snapshot first, then safe WAL compaction and recovery. */
class Uc11SnapshotCompactionSmokeTest {
    @TempDir Path directory;
    /** Preserves retained state when the WAL covered by the snapshot is discarded. */
    @Test void recoversSnapshotAfterCompactingCoveredWalRecords() throws Exception {
        FileSystemAdapter files = new FileSystemAdapter(); FileWriteAheadLog wal = new FileWriteAheadLog(directory, files, FsyncPolicy.ALWAYS); MvccStore store = new MvccStore();
        VersionGenerator versions = new VersionGenerator(); CommitCoordinator commits = new CommitCoordinator(versions, store, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), record -> { try { wal.append(record); } catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); } });
        commits.commit(List.of(Mutation.put("key", "first".getBytes(StandardCharsets.UTF_8)))); commits.commit(List.of(Mutation.put("key", "second".getBytes(StandardCharsets.UTF_8))));
        long cutoff = new SnapshotWriter(new SnapshotStore(directory, files)).write(store); new WalCompactor(wal).compactThrough(cutoff); assertTrue(wal.replay().isEmpty());
        MvccStore recovered = new MvccStore(); new RecoveryManager(new SnapshotStore(directory, files), wal).recover(recovered, new VersionGenerator());
        assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), recovered.get("key", Instant.EPOCH).orElseThrow().value());
    }

    /** Runs operator-triggered snapshots through the server and recovers history plus residual WAL. */
    @Test
    void serverPublishesAndCompactsRecoverableSnapshots() throws Exception {
        TempoKvServer server = start();
        try (Socket client = new Socket("127.0.0.1", server.respPort())) {
            send(client, "SET", "key", "first");
            assertEquals("+OK\r\n", read(client, 5));
            send(client, "SET", "key", "second");
            assertEquals("+OK\r\n", read(client, 5));
            assertEquals(2, server.snapshotAndCompact());
            send(client, "SET", "key", "third");
            assertEquals("+OK\r\n", read(client, 5));
            assertEquals(3, server.snapshotAndCompact());
            assertEquals(2L, server.metrics().counters().get("snapshot.successes"));
        } finally {
            server.close();
        }

        TempoKvServer recovered = start();
        try (Socket client = new Socket("127.0.0.1", recovered.respPort())) {
            send(client, "GET", "key");
            assertEquals("$5\r\nthird\r\n", read(client, 11));
            send(client, "GETAT", "key", "VERSION", "1");
            assertEquals("$5\r\nfirst\r\n", read(client, 11));
        } finally {
            recovered.close();
        }
    }

    private TempoKvServer start() throws Exception {
        return TempoKvApplication.bootstrap(new String[]{
                "--data-dir=" + directory.resolve("server-data"),
                "--resp-port=0",
                "--sql-port=0",
                "--persistence-enabled=true",
                "--authentication-enabled=false"
        }, java.util.Map.of());
    }

    private static void send(Socket socket, String... values) throws Exception {
        StringBuilder request = new StringBuilder("*").append(values.length).append("\r\n");
        for (String value : values) {
            request.append('$').append(value.getBytes(StandardCharsets.UTF_8).length)
                    .append("\r\n").append(value).append("\r\n");
        }
        socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static String read(Socket socket, int length) throws Exception {
        return new String(socket.getInputStream().readNBytes(length), StandardCharsets.UTF_8);
    }

}
