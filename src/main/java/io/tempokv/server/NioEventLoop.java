package io.tempokv.server;

import java.io.IOException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Consumer;

/** Runs a single non-blocking selector loop without protocol or command semantics. */
public final class NioEventLoop implements AutoCloseable {
    private final Selector selector;
    private final Thread thread;
    private final Consumer<Throwable> failureHandler;
    private volatile boolean running;

    /** Creates an event loop that is started by {@link #start(ServerSocketChannel, ConnectionFactory)}. */
    public NioEventLoop() throws IOException {
        this(ignored -> { });
    }

    /** Creates an event loop that reports failures not caused by normal shutdown. */
    public NioEventLoop(Consumer<Throwable> failureHandler) throws IOException {
        selector = Selector.open();
        this.failureHandler =
                Objects.requireNonNull(failureHandler, "failureHandler");
        thread = new Thread(this::run, "tempokv-nio");
    }

    /** Registers the listening socket and begins serving accepted connections. */
    public synchronized void start(ServerSocketChannel server, ConnectionFactory factory) throws IOException {
        if (running) return;
        Objects.requireNonNull(server, "server").configureBlocking(false);
        server.register(selector, SelectionKey.OP_ACCEPT, Objects.requireNonNull(factory, "factory"));
        running = true; thread.start();
    }

    /** Returns whether the selector thread is accepting and processing events. */
    public boolean isRunning() { return running; }

    private void run() {
        try {
            while (running) {
                selector.select();
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) { SelectionKey key = keys.next(); keys.remove(); process(key); }
            }
        } catch (IOException | ClosedSelectorException exception) {
            if (running) failureHandler.accept(exception);
        } finally { running = false; }
    }

    private void process(SelectionKey key) {
        try {
            if (!key.isValid()) return;
            if (key.isAcceptable()) accept(key);
            if (key.isReadable()) read(key);
            if (key.isWritable()) write(key);
        } catch (IOException | RuntimeException exception) { closeKey(key); }
    }

    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel(); SocketChannel socket;
        while ((socket = server.accept()) != null) {
            try {
                socket.configureBlocking(false);
                ClientConnection connection =
                        ((ConnectionFactory) key.attachment()).create(socket);
                socket.register(selector, SelectionKey.OP_READ, connection);
            } catch (IOException | RuntimeException exception) {
                try {
                    socket.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
        }
    }

    private static void read(SelectionKey key) throws IOException {
        ClientConnection connection = (ClientConnection) key.attachment();
        if (!connection.read()) { key.cancel(); return; }
        updateInterest(key, connection, connection.write());
    }

    private static void write(SelectionKey key) throws IOException {
        ClientConnection connection = (ClientConnection) key.attachment();
        updateInterest(key, connection, connection.write());
    }

    /** Applies write readiness and pauses reads above the connection high-water mark. */
    private static void updateInterest(
            SelectionKey key, ClientConnection connection, boolean pendingWrites) {
        int operations = pendingWrites ? SelectionKey.OP_WRITE : 0;
        if (connection.acceptsReads()) operations |= SelectionKey.OP_READ;
        key.interestOps(operations);
    }

    private static void closeKey(SelectionKey key) {
        key.cancel(); try { if (key.attachment() instanceof ClientConnection connection) connection.close(); else key.channel().close(); } catch (IOException ignored) { }
    }

    /** Stops accepting events and closes every registered channel. */
    @Override public synchronized void close() throws IOException {
        if (!running) { selector.close(); return; }
        running = false; selector.wakeup();
        for (SelectionKey key : selector.keys()) closeKey(key);
        selector.close();
        try { thread.join(5000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    /** Builds transport-specific connection state after a socket is accepted. */
    @FunctionalInterface public interface ConnectionFactory { ClientConnection create(SocketChannel channel) throws IOException; }
}
