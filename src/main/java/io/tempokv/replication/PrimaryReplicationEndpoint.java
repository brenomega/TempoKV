package io.tempokv.replication;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.persistence.SnapshotStore;
import io.tempokv.persistence.WalRecordCodec;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.CommitRecord;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Accepts authenticated replica connections and streams a gap-free snapshot/WAL sequence.
 */
public final class PrimaryReplicationEndpoint implements AutoCloseable {
    static final int MAGIC = 0x544B5250;
    static final short PROTOCOL_VERSION = 1;
    static final byte SNAPSHOT = 1;
    static final byte COMMIT = 2;
    static final byte CAUGHT_UP = 3;
    static final byte ERROR = 4;
    static final int MAX_FRAME_BYTES = 128 * 1024 * 1024;

    private final int configuredPort;
    private final String token;
    private final CommitCoordinator commits;
    private final SyncCoordinator synchronizer;
    private final SnapshotStore snapshots;
    private final WalRecordCodec walCodec;
    private final AckTracker acknowledgements;
    private final MetricsRegistry metrics;
    private final ConcurrentHashMap<String, Subscription> subscriptions =
            new ConcurrentHashMap<>();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean running;

    /** Creates the primary-only internal replication endpoint. */
    public PrimaryReplicationEndpoint(
            int port,
            String token,
            CommitCoordinator commits,
            SyncCoordinator synchronizer,
            SnapshotStore snapshots,
            AckTracker acknowledgements,
            MetricsRegistry metrics) {
        this.configuredPort = port;
        this.token = Objects.requireNonNull(token, "token");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.synchronizer = Objects.requireNonNull(synchronizer, "synchronizer");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.acknowledgements = Objects.requireNonNull(acknowledgements, "acknowledgements");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.walCodec = new WalRecordCodec();
    }

    /** Binds the configured port and starts accepting replica sessions. */
    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new java.net.InetSocketAddress(configuredPort));
        serverSocket = socket;
        running = true;
        acceptThread = Thread.ofPlatform()
                .daemon(true)
                .name("tempokv-replication-accept")
                .start(this::acceptLoop);
        metrics.setGauge("replication.endpoint_up", 1);
    }

    /**
     * Enqueues a newly committed record for every replica registered under the commit monitor.
     */
    public void publish(CommitRecord record) {
        subscriptions.values().forEach(subscription -> subscription.records().add(record));
    }

    /** Returns the bound replication port, including an operating-system assigned port. */
    public int port() throws IOException {
        ServerSocket socket = serverSocket;
        if (socket == null) throw new IOException("Replication endpoint is not started");
        return socket.getLocalPort();
    }

    /** Returns whether the accept loop remains available. */
    public boolean isRunning() {
        ServerSocket socket = serverSocket;
        return running && socket != null && !socket.isClosed();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                sockets.add(socket);
                Thread.ofPlatform()
                        .daemon(true)
                        .name("tempokv-replica-session")
                        .start(() -> serve(socket));
            } catch (IOException failure) {
                if (running) metrics.incrementCounter("replication.accept_failures");
            }
        }
    }

    private void serve(Socket socket) {
        String replicaId = null;
        Subscription subscription = null;
        try (socket;
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            Handshake handshake = readHandshake(input);
            replicaId = handshake.replicaId();
            InitialSync initial;
            try {
                initial = commits.withStableState(() -> {
                    try {
                        SyncCoordinator.Plan plan =
                                synchronizer.plan(handshake.appliedVersion());
                        Subscription created =
                                new Subscription(
                                        handshake.replicaId(),
                                        new LinkedBlockingQueue<>(),
                                        socket);
                        Subscription replaced =
                                subscriptions.put(handshake.replicaId(), created);
                        if (replaced != null) replaced.close();
                        acknowledgements.register(
                                handshake.replicaId(), handshake.appliedVersion());
                        updateReplicaMetrics();
                        return new InitialSync(created, plan);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
            } catch (UncheckedIOException failure) {
                writeError(output, failure.getCause().getMessage());
                return;
            }
            subscription = initial.subscription();
            sendInitial(initial.plan(), input, output, replicaId);
            writeCaughtUp(output, initial.plan().primaryVersion());
            while (running && !subscription.closed()) {
                CommitRecord record =
                        subscription.records().poll(500, TimeUnit.MILLISECONDS);
                if (record == null) continue;
                writeFrame(output, COMMIT, walCodec.encode(record));
                readAcknowledgement(input, replicaId, record.version());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            if (running) metrics.incrementCounter("replication.session_failures");
        } finally {
            sockets.remove(socket);
            if (replicaId != null && subscription != null
                    && subscriptions.remove(replicaId, subscription)) {
                acknowledgements.remove(replicaId);
                updateReplicaMetrics();
            }
        }
    }

    private Handshake readHandshake(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) throw new IOException("Invalid replication handshake");
        if (input.readShort() != PROTOCOL_VERSION) {
            throw new IOException("Unsupported replication protocol version");
        }
        String suppliedToken = input.readUTF();
        if (!MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("Replication authentication failed");
        }
        String replicaId = input.readUTF().trim();
        long version = input.readLong();
        if (replicaId.isEmpty() || version < 0) {
            throw new IOException("Invalid replica identity or version");
        }
        return new Handshake(replicaId, version);
    }

    private void sendInitial(
            SyncCoordinator.Plan plan,
            DataInputStream input,
            DataOutputStream output,
            String replicaId) throws IOException {
        if (plan.mode() == SyncCoordinator.Mode.FULL) {
            writeFrame(output, SNAPSHOT, snapshots.encodeForTransfer(plan.snapshot()));
            readAcknowledgement(input, replicaId, plan.primaryVersion());
            metrics.incrementCounter("replication.full_syncs");
            return;
        }
        for (CommitRecord record : plan.commits()) {
            writeFrame(output, COMMIT, walCodec.encode(record));
            readAcknowledgement(input, replicaId, record.version());
        }
        metrics.incrementCounter("replication.incremental_syncs");
    }

    private void readAcknowledgement(
            DataInputStream input, String replicaId, long expectedVersion) throws IOException {
        long acknowledged = input.readLong();
        if (acknowledged != expectedVersion) {
            throw new IOException("Unexpected replica acknowledgement");
        }
        acknowledgements.acknowledge(replicaId, acknowledged);
        metrics.incrementCounter("replication.acks");
    }

    private static void writeFrame(
            DataOutputStream output, byte messageType, byte[] payload) throws IOException {
        output.writeByte(messageType);
        output.writeInt(payload.length);
        output.write(payload);
        output.flush();
    }

    private static void writeCaughtUp(DataOutputStream output, long version) throws IOException {
        output.writeByte(CAUGHT_UP);
        output.writeLong(version);
        output.flush();
    }

    private static void writeError(DataOutputStream output, String message) throws IOException {
        output.writeByte(ERROR);
        output.writeUTF(message == null ? "Replication synchronization failed" : message);
        output.flush();
    }

    private void updateReplicaMetrics() {
        metrics.setGauge("replication.replicas_connected", subscriptions.size());
    }

    /** Stops accepting replicas and closes all live streams. */
    @Override
    public synchronized void close() throws IOException {
        running = false;
        metrics.setGauge("replication.endpoint_up", 0);
        IOException failure = null;
        ServerSocket listener = serverSocket;
        serverSocket = null;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException closeFailure) {
                failure = closeFailure;
            }
        }
        subscriptions.values().forEach(Subscription::close);
        subscriptions.clear();
        acknowledgements.snapshot().keySet().forEach(acknowledgements::remove);
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        sockets.clear();
        updateReplicaMetrics();
        if (failure != null) throw failure;
    }

    private record Handshake(String replicaId, long appliedVersion) {}
    private record InitialSync(Subscription subscription, SyncCoordinator.Plan plan) {}
    private record Subscription(
            String replicaId,
            BlockingQueue<CommitRecord> records,
            Socket socket,
            java.util.concurrent.atomic.AtomicBoolean flag) {
        Subscription(
                String replicaId,
                BlockingQueue<CommitRecord> records,
                Socket socket) {
            this(
                    replicaId,
                    records,
                    socket,
                    new java.util.concurrent.atomic.AtomicBoolean());
        }

        boolean closed() {
            return flag.get();
        }

        void close() {
            flag.set(true);
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing an already disconnected duplicate is idempotent.
            }
        }
    }
}
