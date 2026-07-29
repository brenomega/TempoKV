package io.tempokv.integration;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Smoke-tests UC-06 by inspecting retained history and comparing versions over RESP and SQL. */
class Uc06HistoryAndDiffSmokeTest {
    @TempDir Path temporaryDirectory;

    /** Returns a bounded history page and the values visible at both diff coordinates. */
    @Test
    void smokeTestInspectsHistoryAndDiffsVersions() throws Exception {
        try (Uc05HistoricalReadSmokeTest.ServerFixture fixture = Uc05HistoricalReadSmokeTest.ServerFixture.start(temporaryDirectory); Socket client = fixture.client()) {
            client.getOutputStream().write((request("SET", "profile", "first") + request("SET", "profile", "second") + request("HISTORY", "profile", "0", "2") + request("DIFF", "profile", "1", "2")).getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            RespReader input = new RespReader(client.getInputStream());
            assertEquals("OK", input.read());
            assertEquals("OK", input.read());
            List<?> history = (List<?>) input.read();
            assertEquals(List.of(2L, "second"), List.of(((List<?>) history.getFirst()).getFirst(), ((List<?>) history.getFirst()).get(2)));
            assertEquals(
                    List.of("VALUE", "VALUE", 0L, "first", "second"),
                    input.read());

            try (SqlTestClient sql = SqlTestClient.connect(fixture.server())) {
                sql.send(
                        "SELECT version, value FROM HISTORY('profile') "
                                + "ORDER BY version ASC LIMIT 2;"
                                + "DIFF 'profile' BETWEEN VERSION 1 AND VERSION 2;");
                assertEquals(
                        "version\tvalue\n1\tfirst\n2\tsecond\n\n",
                        sql.readResponse());
                assertEquals(
                        "before_state\tafter_state\tcommon_prefix\tbefore_suffix\tafter_suffix\n"
                                + "VALUE\tVALUE\t0\tfirst\tsecond\n\n",
                        sql.readResponse());
            }
        }
    }

    private static String request(String... values) { StringBuilder request = new StringBuilder("*").append(values.length).append("\r\n"); for (String value : values) request.append('$').append(value.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(value).append("\r\n"); return request.toString(); }

    /** Decodes only the RESP response shapes asserted by this smoke test. */
    private static final class RespReader {
        private final BufferedInputStream input;
        RespReader(InputStream input) { this.input = new BufferedInputStream(input); }
        Object read() throws Exception { return switch (input.read()) { case '+' -> line(); case ':' -> Long.parseLong(line()); case '$' -> bulk(); case '*' -> array(); default -> throw new AssertionError("Unexpected RESP response type"); }; }
        private Object bulk() throws Exception { int length = Integer.parseInt(line()); if (length < 0) return null; String value = new String(input.readNBytes(length), StandardCharsets.UTF_8); if (input.read() != '\r' || input.read() != '\n') throw new AssertionError("Invalid RESP bulk terminator"); return value; }
        private List<Object> array() throws Exception { int length = Integer.parseInt(line()); java.util.ArrayList<Object> values = new java.util.ArrayList<>(); for (int index = 0; index < length; index++) values.add(read()); return values; }
        private String line() throws Exception { StringBuilder value = new StringBuilder(); int next; while ((next = input.read()) != '\r') value.append((char) next); if (input.read() != '\n') throw new AssertionError("Invalid RESP line"); return value.toString(); }
    }
}
