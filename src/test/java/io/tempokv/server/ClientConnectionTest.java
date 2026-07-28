package io.tempokv.server;

import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies bounded writes and idempotent close behavior at the socket boundary. */
class ClientConnectionTest {
    /** Rejects response accumulation above the configured per-client write bound. */
    @Test
    void appliesBackpressureBeforePendingWritesGrowWithoutBound() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            try (SocketChannel client = SocketChannel.open(listener.getLocalAddress());
                    SocketChannel accepted = listener.accept()) {
                accepted.configureBlocking(false);
                ClientConnection connection = new ClientConnection(
                        accepted,
                        (bytes, responses) -> {
                            responses.accept(new byte[6]);
                            responses.accept(new byte[6]);
                        },
                        () -> { },
                        10);
                client.write(java.nio.ByteBuffer.wrap(new byte[]{1}));

                assertThrows(
                        ClientConnection.BackpressureException.class,
                        connection::read);
            }
        }
    }

    /** Notifies connection accounting only once when shutdown closes a client repeatedly. */
    @Test
    void closesConnectionExactlyOnce() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            try (SocketChannel client = SocketChannel.open(listener.getLocalAddress());
                    SocketChannel accepted = listener.accept()) {
                ClientConnection connection = new ClientConnection(
                        accepted, (bytes, responses) -> { }, closes::incrementAndGet);
                connection.close();
                connection.close();
            }
        }
        assertEquals(1, closes.get());
    }
}
