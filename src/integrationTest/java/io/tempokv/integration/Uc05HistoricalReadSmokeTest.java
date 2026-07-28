package io.tempokv.integration;

import io.tempokv.bootstrap.TempoKvApplication;
import io.tempokv.bootstrap.TempoKvServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Smoke-tests UC-05 by reading a retained value through the public RESP endpoint. */
class Uc05HistoricalReadSmokeTest {
    @TempDir Path temporaryDirectory;

    /** Returns the historical value selected by version without changing the current value. */
    @Test
    void smokeTestReadsValueAtRetainedVersion() throws Exception {
        try (ServerFixture fixture = ServerFixture.start(temporaryDirectory); Socket client = fixture.client()) {
            client.getOutputStream().write((request("SET", "profile", "first") + request("SET", "profile", "second")
                    + request("GETAT", "profile", "VERSION", "1") + request("GET", "profile")).getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            InputStream input = client.getInputStream();
            assertEquals("+OK\r\n+OK\r\n$5\r\nfirst\r\n$6\r\nsecond\r\n", readExactly(input, 33));
        }
    }

    /** Applies configured retention and exposes the number of collected versions. */
    @Test
    void appliesConfiguredRetentionToHistoricalReads() throws Exception {
        try (ServerFixture fixture =
                        ServerFixture.start(
                                temporaryDirectory.resolve("retention"),
                                "PT0.000000001S");
                Socket client = fixture.client()) {
            String expected =
                    "+OK\r\n+OK\r\n-ERR historical value is no longer retained\r\n";
            client.getOutputStream().write((
                    request("SET", "profile", "first")
                            + request("SET", "profile", "second")
                            + request("GETAT", "profile", "VERSION", "1"))
                    .getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();

            assertEquals(
                    expected,
                    readExactly(
                            client.getInputStream(),
                            expected.getBytes(StandardCharsets.UTF_8).length));
            assertEquals(
                    1L,
                    fixture.server.metrics().counters()
                            .get("history.versions_collected"));
        }
    }

    private static String request(String... values) { StringBuilder request = new StringBuilder("*").append(values.length).append("\r\n"); for (String value : values) request.append('$').append(value.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(value).append("\r\n"); return request.toString(); }
    private static String readExactly(InputStream input, int length) throws Exception { return new String(input.readNBytes(length), StandardCharsets.UTF_8); }

    /** Owns one isolated server and client setup for temporal RESP smoke tests. */
    static final class ServerFixture implements AutoCloseable {
        private final TempoKvServer server;
        private ServerFixture(TempoKvServer server) { this.server = server; }
        static ServerFixture start(Path directory) throws Exception {
            return start(directory, "PT720H");
        }
        static ServerFixture start(Path directory, String retention) throws Exception {
            Ports ports = availablePorts();
            return new ServerFixture(TempoKvApplication.bootstrap(new String[]{
                    "--data-dir=" + directory.resolve("data"),
                    "--resp-port=" + ports.resp(),
                    "--sql-port=" + ports.sql(),
                    "--history-retention=" + retention
            }, Map.of()));
        }
        private static Ports availablePorts() throws Exception {
            try (ServerSocket resp = new ServerSocket(0); ServerSocket sql = new ServerSocket(0)) {
                return new Ports(resp.getLocalPort(), sql.getLocalPort());
            }
        }
        Socket client() throws Exception { Socket client = new Socket("127.0.0.1", server.respPort()); client.setSoTimeout(5_000); return client; }
        @Override public void close() throws Exception { server.close(); }
        private record Ports(int resp, int sql) { }
    }
}
