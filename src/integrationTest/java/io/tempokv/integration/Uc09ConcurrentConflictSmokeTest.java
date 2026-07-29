package io.tempokv.integration;

import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Smoke-tests UC-09 through two independent RESP sessions and the public server pipeline. */
class Uc09ConcurrentConflictSmokeTest {
    @TempDir Path temporaryDirectory;

    /** Rejects the second same-key writer without publishing a second version. */
    @Test
    void abortsSecondConcurrentWriterBeforeWalAndStorage() throws Exception {
        try (Uc05HistoricalReadSmokeTest.ServerFixture fixture =
                        Uc05HistoricalReadSmokeTest.ServerFixture.start(
                                temporaryDirectory);
                Socket first = fixture.client();
                Socket second = fixture.client()) {
            send(first, request("BEGIN") + request("SET", "account", "first"));
            send(second, request("BEGIN") + request("SET", "account", "second"));
            assertEquals("+OK\r\n+OK\r\n", read(first, 10));
            assertEquals("+OK\r\n+OK\r\n", read(second, 10));

            send(first, request("COMMIT"));
            assertEquals("+OK\r\n", read(first, 5));

            String conflict =
                    "-ERR transaction conflict on keys account\r\n";
            send(second, request("COMMIT"));
            assertEquals(
                    conflict,
                    read(second, conflict.getBytes(StandardCharsets.UTF_8).length));

            send(first, request("GET", "account")
                    + request("HISTORY", "account"));
            String expected =
                    "$5\r\nfirst\r\n*1\r\n*3\r\n:1\r\n";
            assertEquals(expected, read(first, expected.length()));
            assertEquals(
                    1L,
                    fixture.server().metrics().counters()
                            .get("transactions.conflicts"));
        }
    }

    private static void send(Socket socket, String request) throws Exception {
        socket.getOutputStream().write(
                request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
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

    private static String read(Socket socket, int length) throws Exception {
        InputStream input = socket.getInputStream();
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }
}
