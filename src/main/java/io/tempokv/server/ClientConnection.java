package io.tempokv.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.function.Consumer;

/** Owns per-client read buffering and queued non-blocking RESP writes. */
public final class ClientConnection {
    private final SocketChannel channel;
    private final ConnectionProcessor processor;
    private final Runnable onClose;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(8192);
    private final Queue<ByteBuffer> pendingWrites = new ArrayDeque<>();
    private boolean closed;

    /** Creates the network state for one accepted socket. */
    public ClientConnection(SocketChannel channel, ConnectionProcessor processor, Runnable onClose) {
        this.channel = Objects.requireNonNull(channel, "channel"); this.processor = Objects.requireNonNull(processor, "processor"); this.onClose = Objects.requireNonNull(onClose, "onClose");
    }

    /** Reads currently available bytes and forwards complete chunks to the protocol handler. */
    boolean read() throws IOException {
        int read = channel.read(readBuffer);
        if (read < 0) { close(); return false; }
        if (read == 0) return true;
        readBuffer.flip(); byte[] bytes = new byte[readBuffer.remaining()]; readBuffer.get(bytes); readBuffer.clear();
        processor.onBytes(bytes, this::enqueue);
        return !closed;
    }

    /** Flushes queued bytes and returns whether the socket still has pending output. */
    boolean write() throws IOException {
        while (!pendingWrites.isEmpty()) {
            channel.write(pendingWrites.peek());
            if (pendingWrites.peek().hasRemaining()) return true;
            pendingWrites.remove();
        }
        return false;
    }

    /** Queues a response; only the event-loop thread consumes the queue. */
    private void enqueue(byte[] bytes) { if (!closed) pendingWrites.add(ByteBuffer.wrap(bytes)); }

    /** Closes this connection exactly once. */
    void close() throws IOException { if (!closed) { closed = true; try { channel.close(); } finally { onClose.run(); } } }

    /** Receives transport bytes and emits ordered response bytes without accessing the socket. */
    @FunctionalInterface public interface ConnectionProcessor { void onBytes(byte[] bytes, Consumer<byte[]> responses); }
}
