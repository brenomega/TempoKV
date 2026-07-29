package io.tempokv.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.function.Consumer;

/** Owns protocol-neutral per-client read buffering and queued non-blocking writes. */
public final class ClientConnection {
    private static final int DEFAULT_MAX_PENDING_WRITE_BYTES = 32 * 1024 * 1024;
    private final SocketChannel channel;
    private final ConnectionProcessor processor;
    private final Runnable onClose;
    private final int maxPendingWriteBytes;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(8192);
    private final Queue<ByteBuffer> pendingWrites = new ArrayDeque<>();
    private long pendingWriteBytes;
    private boolean closed;

    /** Creates the network state for one accepted socket. */
    public ClientConnection(SocketChannel channel, ConnectionProcessor processor, Runnable onClose) {
        this(channel, processor, onClose, DEFAULT_MAX_PENDING_WRITE_BYTES);
    }

    /** Creates a connection with an explicit pending-write bound for deterministic tests. */
    ClientConnection(
            SocketChannel channel,
            ConnectionProcessor processor,
            Runnable onClose,
            int maxPendingWriteBytes) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.onClose = Objects.requireNonNull(onClose, "onClose");
        if (maxPendingWriteBytes < 1) {
            throw new IllegalArgumentException("maxPendingWriteBytes must be positive");
        }
        this.maxPendingWriteBytes = maxPendingWriteBytes;
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
            pendingWriteBytes -= pendingWrites.remove().capacity();
        }
        return false;
    }

    /** Queues a response; only the event-loop thread consumes the queue. */
    private void enqueue(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (closed) return;
        if (bytes.length > maxPendingWriteBytes - pendingWriteBytes) {
            throw new BackpressureException(
                    "Pending responses exceed " + maxPendingWriteBytes + " bytes");
        }
        pendingWrites.add(ByteBuffer.wrap(bytes));
        pendingWriteBytes += bytes.length;
    }

    /** Returns whether reads should remain enabled below the high-water mark. */
    boolean acceptsReads() {
        return pendingWriteBytes <= maxPendingWriteBytes / 2L;
    }

    /** Closes this connection exactly once. */
    void close() throws IOException { if (!closed) { closed = true; try { channel.close(); } finally { onClose.run(); } } }

    /** Receives transport bytes and emits ordered response bytes without accessing the socket. */
    @FunctionalInterface public interface ConnectionProcessor { void onBytes(byte[] bytes, Consumer<byte[]> responses); }

    /** Signals bounded connection shedding when a slow client stops consuming responses. */
    static final class BackpressureException extends RuntimeException {
        BackpressureException(String message) { super(message); }
    }
}
