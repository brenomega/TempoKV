package io.tempokv.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class RespHealthProbeTest {
    @Test
    void acceptsOnlyCanonicalPong() throws Exception {
        assertTrue(probeWithResponse("+PONG\r\n"));
        assertFalse(probeWithResponse("-ERR not ready\r\n"));
    }

    private static boolean probeWithResponse(String response) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> responder = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    socket.getInputStream().readNBytes(14);
                    socket.getOutputStream().write(
                            response.getBytes(StandardCharsets.US_ASCII));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            boolean result = RespHealthProbe.ping(
                    "127.0.0.1", server.getLocalPort());
            responder.join();
            return result;
        }
    }
}
