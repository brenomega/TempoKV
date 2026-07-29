package io.tempokv.integration;

import io.tempokv.bootstrap.TempoKvApplication;
import io.tempokv.bootstrap.TempoKvServer;
import io.tempokv.protocol.resp.RespDecoder;
import io.tempokv.protocol.resp.RespFrame;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Exercises UC-13 across full sync, reconnect, read-only access, and node restarts. */
class Uc13ReplicationSmokeTest {
    @TempDir Path directory;

    /**
     * Keeps primary and replica versions invariant across full sync and both node restarts.
     */
    @Test
    void replicatesDurablyAndReconnectsWithoutAcceptingReplicaWrites() throws Exception {
        Ports ports = Ports.reserve();
        TempoKvServer primary = null;
        TempoKvServer replica = null;
        try {
            primary = startPrimary(ports);
            assertEquals("OK", simple(command(
                    primary.respPort(), "SET", "profile", "first")));

            replica = startReplica(ports);
            assertArrayEquals(bytes("first"), bulk(command(
                    replica.respPort(), "GET", "profile")));
            assertEquals(
                    "READONLY replica does not accept writes",
                    error(command(replica.respPort(), "SET", "profile", "forbidden")));
            try (SqlTestClient sql = SqlTestClient.connect(replica)) {
                sql.send("UPSERT INTO tempokv (key, value) VALUES ('profile', 'forbidden');");
                org.junit.jupiter.api.Assertions.assertTrue(
                        sql.readResponse().contains("READONLY replica does not accept writes"));
            }
            assertVersionsEqual(primary, replica);
            long idleVersion = replica.metrics().gauges()
                    .get("replication.applied_version");
            Thread.sleep(700);
            assertEquals(
                    idleVersion,
                    replica.metrics().gauges()
                            .get("replication.applied_version"));
            assertEquals(
                    0L,
                    replica.metrics().counters()
                            .getOrDefault("replication.reconnects", 0L));
            org.junit.jupiter.api.Assertions.assertTrue(
                    replica.metrics().counters()
                            .getOrDefault(
                                    "replication.heartbeats_received", 0L) > 0);

            replica.close();
            replica = null;
            assertEquals("OK", simple(command(
                    primary.respPort(), "SET", "profile", "while-offline")));

            replica = startReplica(ports);
            assertArrayEquals(bytes("while-offline"), bulk(command(
                    replica.respPort(), "GET", "profile")));
            assertVersionsEqual(primary, replica);

            primary.close();
            primary = null;
            primary = startPrimary(ports);
            TempoKvServer restartedPrimary = primary;
            await(
                    () -> restartedPrimary.metrics().gauges()
                            .getOrDefault("replication.replicas_connected", 0L) == 1L,
                    Duration.ofSeconds(10));
            assertEquals("OK", simple(command(
                    primary.respPort(), "SET", "profile", "after-primary-restart")));

            TempoKvServer activeReplica = replica;
            await(
                    () -> {
                        try {
                            return java.util.Arrays.equals(
                                    bytes("after-primary-restart"),
                                    bulk(command(activeReplica.respPort(), "GET", "profile")));
                        } catch (Exception ignored) {
                            return false;
                        }
                    },
                    Duration.ofSeconds(10));
            assertVersionsEqual(primary, replica);
        } finally {
            if (replica != null) replica.close();
            if (primary != null) primary.close();
        }
    }

    private TempoKvServer startPrimary(Ports ports) throws Exception {
        return TempoKvApplication.bootstrap(new String[] {
                "--data-dir=" + directory.resolve("primary"),
                "--resp-port=" + ports.primaryResp(),
                "--sql-port=" + ports.primarySql(),
                "--replication-port=" + ports.replication(),
                "--replication-enabled=true",
                "--node-role=PRIMARY",
                "--node-id=primary",
                "--replication-token=uc13-replication-secret",
                "--persistence-enabled=true",
                "--authentication-enabled=false",
                "--replication-heartbeat-interval=PT0.1S",
                "--replication-heartbeat-timeout=PT0.5S"
        }, Map.of());
    }

    private TempoKvServer startReplica(Ports ports) throws Exception {
        return TempoKvApplication.bootstrap(new String[] {
                "--data-dir=" + directory.resolve("replica"),
                "--resp-port=" + ports.replicaResp(),
                "--sql-port=" + ports.replicaSql(),
                "--replication-port=" + ports.replicaInternal(),
                "--replication-enabled=true",
                "--node-role=REPLICA",
                "--node-id=replica",
                "--primary-host=127.0.0.1",
                "--primary-replication-port=" + ports.replication(),
                "--replication-token=uc13-replication-secret",
                "--persistence-enabled=true",
                "--authentication-enabled=false",
                "--replication-heartbeat-interval=PT0.1S",
                "--replication-heartbeat-timeout=PT0.5S"
        }, Map.of());
    }

    private static void assertVersionsEqual(
            TempoKvServer primary, TempoKvServer replica) {
        assertEquals(
                primary.metrics().gauges().get("replication.applied_version"),
                replica.metrics().gauges().get("replication.applied_version"));
    }

    private static RespFrame command(int port, String... arguments) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(request(arguments).getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            RespDecoder decoder = new RespDecoder();
            while (true) {
                int next = socket.getInputStream().read();
                if (next < 0) throw new AssertionError("RESP connection closed");
                List<RespFrame> frames = decoder.feed(new byte[] {(byte) next});
                if (!frames.isEmpty()) return frames.getFirst();
            }
        }
    }

    private static String request(String... arguments) {
        StringBuilder request =
                new StringBuilder("*").append(arguments.length).append("\r\n");
        for (String argument : arguments) {
            request.append('$')
                    .append(argument.getBytes(StandardCharsets.UTF_8).length)
                    .append("\r\n")
                    .append(argument)
                    .append("\r\n");
        }
        return request.toString();
    }

    private static String simple(RespFrame frame) {
        return assertInstanceOf(RespFrame.SimpleString.class, frame).value();
    }

    private static byte[] bulk(RespFrame frame) {
        return assertInstanceOf(RespFrame.BulkString.class, frame).value();
    }

    private static String error(RespFrame frame) {
        return assertInstanceOf(RespFrame.Error.class, frame).message();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void await(BooleanSupplier condition, Duration timeout)
            throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Condition was not met within " + timeout);
    }

    private record Ports(
            int primaryResp,
            int primarySql,
            int replication,
            int replicaResp,
            int replicaSql,
            int replicaInternal) {
        static Ports reserve() throws Exception {
            List<ServerSocket> sockets = new ArrayList<>();
            try {
                for (int index = 0; index < 6; index++) {
                    sockets.add(new ServerSocket(0));
                }
                return new Ports(
                        sockets.get(0).getLocalPort(),
                        sockets.get(1).getLocalPort(),
                        sockets.get(2).getLocalPort(),
                        sockets.get(3).getLocalPort(),
                        sockets.get(4).getLocalPort(),
                        sockets.get(5).getLocalPort());
            } finally {
                for (ServerSocket socket : sockets) socket.close();
            }
        }
    }
}
