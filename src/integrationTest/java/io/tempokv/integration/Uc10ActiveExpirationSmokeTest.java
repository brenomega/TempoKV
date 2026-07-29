package io.tempokv.integration;

import io.tempokv.bootstrap.TempoKvApplication;
import io.tempokv.bootstrap.TempoKvServer;
import io.tempokv.persistence.FileSystemAdapter;
import io.tempokv.persistence.FileWriteAheadLog;
import io.tempokv.persistence.FsyncPolicy;
import io.tempokv.persistence.RecoveryManager;
import io.tempokv.persistence.SnapshotStore;
import io.tempokv.storage.ExpirationWorker;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises UC-10: expiration is a durable tombstone, including after recovery. */
class Uc10ActiveExpirationSmokeTest {
    @TempDir Path directory;
    /** Retains the value and records the expiry as a newer recoverable tombstone. */
    @Test void expiresAndRecoversAnAuditableTombstone() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:10Z"); FileSystemAdapter files = new FileSystemAdapter(); FileWriteAheadLog wal = new FileWriteAheadLog(directory, files, FsyncPolicy.ALWAYS);
        MvccStore store = new MvccStore(); VersionGenerator versions = new VersionGenerator(); CommitCoordinator commits = coordinator(versions, store, wal, now);
        commits.commit(List.of(Mutation.put("key", "value".getBytes(StandardCharsets.UTF_8)))); commits.commit(List.of(Mutation.expire("key", now)));
        new ExpirationWorker(store, commits, Clock.fixed(now, ZoneOffset.UTC)).expireDue(); assertTrue(store.get("key", now).isEmpty()); assertEquals(3, store.history("key").size());
        MvccStore recovered = new MvccStore(); new RecoveryManager(new SnapshotStore(directory, files), wal).recover(recovered, new VersionGenerator()); assertTrue(recovered.get("key", now).isEmpty()); assertTrue(recovered.history("key").getFirst().tombstone());
        assertEquals(
                io.tempokv.storage.VersionedValue.TombstoneReason.EXPIRED,
                recovered.history("key").getFirst().tombstoneReason());
    }

    /** Runs the scheduled worker through RESP and recovers its tombstone after server restart. */
    @Test
    void serverSchedulesAndRecoversExpiration() throws Exception {
        TempoKvServer server = start();
        try (Socket client = new Socket("127.0.0.1", server.respPort())) {
            client.setSoTimeout(5_000);
            send(client, "SET", "scheduled", "value");
            assertEquals("+OK\r\n", read(client, 5));
            send(client, "EXPIRE", "scheduled", "0");
            assertEquals(":1\r\n", read(client, 4));
            awaitExpiredVersion(client);
        } finally {
            server.close();
        }

        TempoKvServer recovered = start();
        try (Socket client = new Socket("127.0.0.1", recovered.respPort())) {
            send(client, "GETAT", "scheduled", "VERSION", "3");
            String expected = "-ERR key was deleted at requested point\r\n";
            assertEquals(
                    expected,
                    read(client, expected.getBytes(StandardCharsets.UTF_8).length));
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

    private static void awaitExpiredVersion(Socket client) throws Exception {
        String expected = "-ERR key was deleted at requested point\r\n";
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            send(client, "GETAT", "scheduled", "VERSION", "3");
            String response = readResp(client);
            if (expected.equals(response)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Active expiration did not publish version 3");
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

    private static String readResp(Socket socket) throws Exception {
        int prefix = socket.getInputStream().read();
        if (prefix == -1) throw new AssertionError("RESP connection closed");
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = socket.getInputStream().read()) != '\r') {
            if (value == -1) throw new AssertionError("Incomplete RESP line");
            line.append((char) value);
        }
        if (socket.getInputStream().read() != '\n') throw new AssertionError("Invalid RESP line");
        String header = (char) prefix + line.toString() + "\r\n";
        if (prefix != '$') return header;
        int length = Integer.parseInt(line.toString());
        return length < 0
                ? header
                : header + new String(
                        socket.getInputStream().readNBytes(length + 2),
                        StandardCharsets.UTF_8);
    }

    private static CommitCoordinator coordinator(VersionGenerator versions, MvccStore store, FileWriteAheadLog wal, Instant now) { return new CommitCoordinator(versions, store, Clock.fixed(now, ZoneOffset.UTC), record -> { try { wal.append(record); } catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); } }); }
}
