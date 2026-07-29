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

/** Smoke-tests UC-03 through the public server, RESP mapping, commit pipeline, and MVCC storage. */
class Uc03KeyValueSmokeTest {
    @TempDir
    Path temporaryDirectory;

    /** Preserves the RESP response order while mixing current-state writes, reads, deletion, and expiration. */
    @Test
    void smokeTestExecutesKeyValueLifecycleWithPassiveExpiration() throws Exception {
        Ports ports = availablePorts();
        TempoKvServer server = start(temporaryDirectory, ports);
        try {
            try (Socket client = new Socket("127.0.0.1", server.respPort())) {
                client.setSoTimeout(5000);
                OutputStream output = client.getOutputStream();
                output.write((request("SET", "name", "Ada") + request("GET", "name")
                        + request("SET", "name", "Bea") + request("DEL", "name") + request("GET", "name")
                        + request("SET", "temporary", "value") + request("TTL", "temporary")
                        + request("EXPIRE", "temporary", "0") + request("TTL", "temporary") + request("GET", "temporary"))
                        .getBytes(StandardCharsets.UTF_8));
                output.flush();

                String expected = "+OK\r\n$3\r\nAda\r\n+OK\r\n:1\r\n$-1\r\n+OK\r\n:-1\r\n:1\r\n:-2\r\n$-1\r\n";
                assertEquals(expected, readExactly(client.getInputStream(), expected.getBytes(StandardCharsets.UTF_8).length));
            }
        } finally {
            server.close();
        }

        TempoKvServer recovered = start(temporaryDirectory, ports);
        try (Socket client = new Socket("127.0.0.1", recovered.respPort())) {
            client.getOutputStream().write((
                    request("GET", "name")
                            + request("GETAT", "name", "VERSION", "2"))
                    .getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            String expected = "$-1\r\n$3\r\nBea\r\n";
            assertEquals(
                    expected,
                    readExactly(
                            client.getInputStream(),
                            expected.getBytes(StandardCharsets.UTF_8).length));
        } finally {
            recovered.close();
        }
    }

    private static TempoKvServer start(Path directory, Ports ports) throws Exception {
        return TempoKvApplication.bootstrap(new String[]{
                "--data-dir=" + directory.resolve("data"),
                "--resp-port=" + ports.resp(), "--sql-port=" + ports.sql(),
                "--persistence-enabled=true"
        }, Map.of());
    }

    private static String request(String... arguments) {
        StringBuilder encoded = new StringBuilder("*").append(arguments.length).append("\r\n");
        for (String argument : arguments) encoded.append('$').append(argument.getBytes(StandardCharsets.UTF_8).length)
                .append("\r\n").append(argument).append("\r\n");
        return encoded.toString();
    }

    private static Ports availablePorts() throws Exception {
        try (ServerSocket resp = new ServerSocket(0); ServerSocket sql = new ServerSocket(0)) {
            return new Ports(resp.getLocalPort(), sql.getLocalPort());
        }
    }

    private static String readExactly(InputStream input, int length) throws Exception {
        byte[] response = input.readNBytes(length);
        if (response.length != length) throw new AssertionError("RESP server closed before completing its response");
        return new String(response, StandardCharsets.UTF_8);
    }

    private record Ports(int resp, int sql) { }
}
