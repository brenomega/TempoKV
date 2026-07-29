package io.tempokv.integration;

import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Smoke-tests UC-07 by restoring through SQL and observing the result through RESP. */
class Uc07RestoreHistoricalVersionSmokeTest {
    @TempDir Path temporaryDirectory;

    /** Makes an old value current through SQL while preserving it durably as an append-only commit. */
    @Test
    void smokeTestRestoresHistoricalValueWithoutRemovingNewerVersion() throws Exception {
        try (Uc05HistoricalReadSmokeTest.ServerFixture fixture = Uc05HistoricalReadSmokeTest.ServerFixture.start(temporaryDirectory); Socket client = fixture.client()) {
            client.getOutputStream().write((request("SET", "profile", "first") + request("SET", "profile", "second")
                    ).getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            String writes = "+OK\r\n+OK\r\n";
            assertEquals(writes, readExactly(client.getInputStream(), writes.getBytes(StandardCharsets.UTF_8).length));

            try (SqlTestClient sql = SqlTestClient.connect(fixture.server())) {
                sql.send("RESTORE 'profile' TO VERSION 1;");
                assertEquals("version\n3\n\n", sql.readResponse());
            }

            client.getOutputStream().write((request("GET", "profile")
                    + request("GETAT", "profile", "VERSION", "2")).getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            String expected = "$5\r\nfirst\r\n$6\r\nsecond\r\n";
            assertEquals(expected, readExactly(client.getInputStream(), expected.getBytes(StandardCharsets.UTF_8).length));
        }
        try (Uc05HistoricalReadSmokeTest.ServerFixture fixture =
                        Uc05HistoricalReadSmokeTest.ServerFixture.start(temporaryDirectory);
                Socket client = fixture.client()) {
            client.getOutputStream().write((
                    request("GET", "profile")
                            + request("GETAT", "profile", "VERSION", "2"))
                    .getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            String expected = "$5\r\nfirst\r\n$6\r\nsecond\r\n";
            assertEquals(
                    expected,
                    readExactly(
                            client.getInputStream(),
                            expected.getBytes(StandardCharsets.UTF_8).length));
        }
    }

    private static String request(String... values) { StringBuilder request = new StringBuilder("*").append(values.length).append("\r\n"); for (String value : values) request.append('$').append(value.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(value).append("\r\n"); return request.toString(); }
    private static String readExactly(InputStream input, int length) throws Exception { return new String(input.readNBytes(length), StandardCharsets.UTF_8); }
}
