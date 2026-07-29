package io.tempokv.integration;

import io.tempokv.protocol.resp.RespDecoder;
import io.tempokv.protocol.resp.RespFrame;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke-tests UC-12 through RESP and SQL while checking administrative data isolation. */
class Uc12OperationalInfoSmokeTest {
    @TempDir Path temporaryDirectory;

    /** Exposes health and percentile metrics without returning user keys or values. */
    @Test
    void reportsHealthAndSanitizedInfoThroughBothProtocols() throws Exception {
        try (Uc05HistoricalReadSmokeTest.ServerFixture fixture =
                        Uc05HistoricalReadSmokeTest.ServerFixture.start(
                                temporaryDirectory);
                Socket resp = fixture.client();
                SqlTestClient sql = SqlTestClient.connect(fixture.server())) {
            send(resp, request("SET", "secret:key", "secret-value"));
            assertEquals(
                    "OK",
                    assertInstanceOf(
                            RespFrame.SimpleString.class,
                            readFrame(resp)).value());
            send(resp, request("PING"));
            assertEquals(
                    "PONG",
                    assertInstanceOf(
                            RespFrame.SimpleString.class,
                            readFrame(resp)).value());

            send(resp, request("HEALTH"));
            RespFrame.Array health = assertInstanceOf(
                    RespFrame.Array.class, readFrame(resp));
            assertEquals("READY", pairValue(health, "status"));

            sql.send("INFO;HEALTH;");
            String info = sql.readResponse();
            assertTrue(info.startsWith("name\tvalue\n"));
            assertTrue(info.contains("server.role\tPRIMARY\n"));
            assertTrue(info.contains("storage.version\t1\n"));
            assertTrue(info.contains("command.ping.latency.p50_nanos\t"));
            assertFalse(info.contains("secret:key"));
            assertFalse(info.contains("secret-value"));

            String sqlHealth = sql.readResponse();
            assertTrue(sqlHealth.contains("status\tREADY\n"));
        }
    }

    private static String pairValue(RespFrame.Array rows, String name) {
        for (RespFrame row : rows.values()) {
            RespFrame.Array pair = assertInstanceOf(RespFrame.Array.class, row);
            List<RespFrame> values = pair.values();
            if (values.getFirst() instanceof RespFrame.SimpleString key
                    && key.value().equals(name)) {
                return assertInstanceOf(
                        RespFrame.SimpleString.class,
                        values.get(1)).value();
            }
        }
        throw new AssertionError("Missing administrative field " + name);
    }

    private static void send(Socket socket, String request) throws Exception {
        socket.getOutputStream().write(
                request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static RespFrame readFrame(Socket socket) throws Exception {
        RespDecoder decoder = new RespDecoder();
        while (true) {
            int next = socket.getInputStream().read();
            if (next < 0) throw new AssertionError("RESP connection closed");
            List<RespFrame> frames = decoder.feed(new byte[]{(byte) next});
            if (!frames.isEmpty()) return frames.getFirst();
        }
    }

    private static String request(String... values) {
        StringBuilder request =
                new StringBuilder("*").append(values.length).append("\r\n");
        for (String value : values) {
            request.append('$')
                    .append(value.getBytes(StandardCharsets.UTF_8).length)
                    .append("\r\n")
                    .append(value)
                    .append("\r\n");
        }
        return request.toString();
    }
}
