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

/** Exercises UC-02 through a real TCP socket, RESP frames, and the public bootstrap API. */
class Uc02RespPingIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    /** Keeps one connection open across fragmented and pipelined PING requests. */
    // SmokeTest
    @Test
    void respondsToFragmentedAndPipelinedPingRequests() throws Exception {
        Ports ports = availablePorts();
        TempoKvServer server = TempoKvApplication.bootstrap(new String[]{
                "--data-dir=" + temporaryDirectory.resolve("data"),
                "--resp-port=" + ports.resp(),
                "--sql-port=" + ports.sql()
        }, Map.of());
        try (Socket client = new Socket("127.0.0.1", server.respPort())) {
            client.setSoTimeout(5000);
            OutputStream output = client.getOutputStream();
            output.write("*1\r\n$4\r\nPI".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            output.write("NG\r\n*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();

            assertEquals("+PONG\r\n+PONG\r\n", readExactly(client.getInputStream(), 14));
        } finally {
            server.close();
        }
    }

    private static Ports availablePorts() throws Exception {
        try (ServerSocket resp = new ServerSocket(0); ServerSocket sql = new ServerSocket(0)) {
            return new Ports(resp.getLocalPort(), sql.getLocalPort());
        }
    }

    private static String readExactly(InputStream input, int length) throws Exception {
        byte[] response = input.readNBytes(length);
        if (response.length != length) throw new AssertionError("RESP server closed before completing its response");
        return new String(response, StandardCharsets.US_ASCII);
    }

    private record Ports(int resp, int sql) { }
}
