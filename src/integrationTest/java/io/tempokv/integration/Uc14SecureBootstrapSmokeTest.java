package io.tempokv.integration;

import io.tempokv.bootstrap.TempoKvApplication;
import io.tempokv.bootstrap.TempoKvServer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the production bootstrap composes credentials for both public protocols. */
class Uc14SecureBootstrapSmokeTest {
    @TempDir Path directory;

    /** Denies anonymous clients and accepts the same explicit identity over RESP and SQL. */
    @Test
    void authenticatesConfiguredIdentityAcrossProtocols() throws Exception {
        TempoKvServer server = TempoKvApplication.bootstrap(
                new String[]{
                        "--data-dir=" + directory.resolve("data"),
                        "--resp-port=0",
                        "--sql-port=0",
                        "--authentication-enabled=true",
                        "--authentication-username=operator",
                        "--authentication-password=runtime-test-password"
                },
                Map.of());
        try {
            try (Socket resp = new Socket("127.0.0.1", server.respPort())) {
                send(resp, "PING");
                assertEquals(
                        "-ERR command is not permitted\r\n",
                        readLine(resp));
                send(resp, "AUTH", "operator", "runtime-test-password");
                assertEquals("+OK\r\n", readLine(resp));
                send(resp, "PING");
                assertEquals("+PONG\r\n", readLine(resp));
            }
            try (SqlTestClient sql = SqlTestClient.connect(server)) {
                sql.send("AUTH operator runtime-test-password;");
                assertEquals("status\nOK\n\n", sql.readResponse());
                sql.send("SELECT value FROM tempokv WHERE key = 'missing';");
                assertEquals("value\n\n", sql.readResponse());
            }
        } finally {
            server.close();
        }
    }

    private static void send(Socket socket, String... values)
            throws Exception {
        StringBuilder request =
                new StringBuilder("*").append(values.length).append("\r\n");
        for (String value : values) {
            request.append('$')
                    .append(value.getBytes(StandardCharsets.UTF_8).length)
                    .append("\r\n")
                    .append(value)
                    .append("\r\n");
        }
        socket.getOutputStream().write(
                request.toString().getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static String readLine(Socket socket) throws Exception {
        StringBuilder response = new StringBuilder();
        int previous = -1;
        int current;
        while ((current = socket.getInputStream().read()) >= 0) {
            response.append((char) current);
            if (previous == '\r' && current == '\n') {
                return response.toString();
            }
            previous = current;
        }
        throw new AssertionError("RESP connection closed");
    }
}
