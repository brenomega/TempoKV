package io.tempokv.integration;

import io.tempokv.bootstrap.TempoKvApplication;
import io.tempokv.bootstrap.TempoKvServer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises UC-01 by recovering a RESP-written value after an orderly restart. */
class Uc01DurableRecoverySmokeTest {
    @TempDir Path directory;
    /** Rebuilds current value, retained history, and TTL before accepting a new connection. */
    @Test void recoversDurableCurrentValueAfterRestart() throws Exception {
        Ports ports = ports(); TempoKvServer first = start(ports);
        try (Socket client = new Socket("127.0.0.1", first.respPort())) {
            send(client, "SET", "customer", "Ada");
            assertEquals("+OK\r\n", read(client, 5));
            send(client, "SET", "customer", "Bea");
            assertEquals("+OK\r\n", read(client, 5));
            send(client, "EXPIRE", "customer", "60");
            assertEquals(":1\r\n", read(client, 4));
        } finally { first.close(); }
        TempoKvServer restarted = start(ports);
        try (Socket client = new Socket("127.0.0.1", restarted.respPort())) {
            send(client, "GET", "customer");
            assertEquals("$3\r\nBea\r\n", read(client, 9));
            send(client, "GETAT", "customer", "VERSION", "1");
            assertEquals("$3\r\nAda\r\n", read(client, 9));
            send(client, "TTL", "customer");
            long ttl = Long.parseLong(readLine(client).substring(1));
            org.junit.jupiter.api.Assertions.assertTrue(ttl > 0 && ttl <= 60);
        } finally { restarted.close(); }
    }
    private TempoKvServer start(Ports ports) throws Exception { return TempoKvApplication.bootstrap(new String[]{"--data-dir=" + directory.resolve("data"), "--resp-port=" + ports.resp, "--sql-port=" + ports.sql, "--persistence-enabled=true"}, Map.of()); }
    private static void send(Socket socket, String... arguments) throws Exception { StringBuilder value = new StringBuilder("*").append(arguments.length).append("\r\n"); for (String argument : arguments) value.append('$').append(argument.length()).append("\r\n").append(argument).append("\r\n"); socket.getOutputStream().write(value.toString().getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush(); }
    private static String read(Socket socket, int length) throws Exception { return new String(socket.getInputStream().readNBytes(length), StandardCharsets.UTF_8); }
    private static String readLine(Socket socket) throws Exception {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = socket.getInputStream().read()) != -1) {
            if (value == '\r') {
                if (socket.getInputStream().read() != '\n') throw new AssertionError("Invalid RESP line");
                return line.toString();
            }
            line.append((char) value);
        }
        throw new AssertionError("Connection closed before RESP line");
    }
    private static Ports ports() throws Exception { try (ServerSocket resp = new ServerSocket(0); ServerSocket sql = new ServerSocket(0)) { return new Ports(resp.getLocalPort(), sql.getLocalPort()); } }
    private record Ports(int resp, int sql) { }
}
