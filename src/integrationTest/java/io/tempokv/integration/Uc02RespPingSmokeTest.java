package io.tempokv.integration;

import io.tempokv.bootstrap.TempoKvApplication;
import io.tempokv.bootstrap.TempoKvServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Smoke-tests UC-02 through a real TCP socket, RESP frames, and the public bootstrap API. */
class Uc02RespPingSmokeTest {
    @TempDir
    Path temporaryDirectory;

    /** Keeps one connection open across fragmented and pipelined PING requests. */
    @Test
    void smokeTestRespondsToFragmentedAndPipelinedPingRequests() throws Exception {
        TempoKvServer server = TempoKvApplication.bootstrap(new String[]{
                "--data-dir=" + temporaryDirectory.resolve("data"),
                "--resp-port=0",
                "--sql-port=0"
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

    /** Serves hundreds of concurrent happy-path clients without serial socket blocking. */
    @Test
    void servesHundredsOfConcurrentConnections() throws Exception {
        TempoKvServer server = TempoKvApplication.bootstrap(new String[]{
                "--data-dir=" + temporaryDirectory.resolve("concurrent-data"),
                "--resp-port=0",
                "--sql-port=0"
        }, Map.of());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = java.util.stream.IntStream.range(0, 200)
                    .mapToObj(index -> executor.submit(() -> {
                        try (Socket client =
                                new Socket("127.0.0.1", server.respPort())) {
                            client.setSoTimeout(5_000);
                            client.getOutputStream().write(
                                    "*1\r\n$4\r\nPING\r\n"
                                            .getBytes(StandardCharsets.US_ASCII));
                            client.getOutputStream().flush();
                            return readExactly(client.getInputStream(), 7);
                        }
                    }))
                    .toList();
            for (var request : requests) {
                assertEquals("+PONG\r\n", request.get());
            }
        } finally {
            server.close();
        }
    }

    /** Keeps a responsive client moving while another connection does not consume output. */
    @Test
    void slowClientDoesNotBlockAnotherConnection() throws Exception {
        TempoKvServer server = TempoKvApplication.bootstrap(new String[]{
                "--data-dir=" + temporaryDirectory.resolve("slow-client-data"),
                "--resp-port=0",
                "--sql-port=0"
        }, Map.of());
        try (Socket slow = new Socket("127.0.0.1", server.respPort());
                Socket responsive =
                        new Socket("127.0.0.1", server.respPort())) {
            slow.setReceiveBufferSize(1_024);
            String pipeline = "*1\r\n$4\r\nPING\r\n".repeat(10_000);
            slow.getOutputStream().write(
                    pipeline.getBytes(StandardCharsets.US_ASCII));
            slow.getOutputStream().flush();

            responsive.setSoTimeout(5_000);
            responsive.getOutputStream().write(
                    "*1\r\n$4\r\nPING\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            responsive.getOutputStream().flush();
            assertEquals(
                    "+PONG\r\n",
                    readExactly(responsive.getInputStream(), 7));
        } finally {
            server.close();
        }
    }

    private static String readExactly(InputStream input, int length) throws Exception {
        byte[] response = input.readNBytes(length);
        if (response.length != length) throw new AssertionError("RESP server closed before completing its response");
        return new String(response, StandardCharsets.US_ASCII);
    }

}
