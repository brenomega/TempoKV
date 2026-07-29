package io.tempokv.replication;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.observability.ServerHealthService;
import io.tempokv.persistence.SnapshotStore;
import io.tempokv.persistence.WalRecordCodec;
import io.tempokv.transaction.CommitRecord;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Maintains a reconnecting replica stream and exposes readiness only after initial catch-up.
 */
public final class ReplicaClient implements AutoCloseable {
    private static final Duration RECONNECT_DELAY = Duration.ofMillis(200);
    private final String host;
    private final int port;
    private final String token;
    private final String nodeId;
    private final ReplicaApplier applier;
    private final ReplicaState state;
    private final SnapshotStore snapshots;
    private final MetricsRegistry metrics;
    private final ServerHealthService health;
    private final WalRecordCodec walCodec = new WalRecordCodec();
    private final CountDownLatch initialCatchUp = new CountDownLatch(1);
    private volatile boolean running;
    private volatile Socket activeSocket;
    private volatile Thread worker;

    /** Creates a client for one configured primary replication endpoint. */
    public ReplicaClient(
            String host,
            int port,
            String token,
            String nodeId,
            ReplicaApplier applier,
            ReplicaState state,
            SnapshotStore snapshots,
            MetricsRegistry metrics,
            ServerHealthService health) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.token = Objects.requireNonNull(token, "token");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.applier = Objects.requireNonNull(applier, "applier");
        this.state = Objects.requireNonNull(state, "state");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.health = Objects.requireNonNull(health, "health");
    }

    /**
     * Starts the reconnect loop and waits until the replica reaches its first consistent boundary.
     */
    public synchronized void startAndAwait(Duration timeout) throws IOException {
        if (!running) {
            running = true;
            worker = Thread.ofPlatform()
                    .daemon(true)
                    .name("tempokv-replica-client")
                    .start(this::run);
        }
        try {
            if (!initialCatchUp.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Timed out waiting for initial replica synchronization");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for replica synchronization", interrupted);
        }
    }

    /** Returns whether the reconnect loop is active. */
    public boolean isRunning() {
        Thread thread = worker;
        return running && thread != null && thread.isAlive();
    }

    private void run() {
        while (running) {
            try {
                synchronize();
            } catch (IOException | RuntimeException failure) {
                if (running) {
                    state.markDisconnected();
                    metrics.incrementCounter("replication.reconnects");
                    metrics.setGauge("replication.connected", 0);
                    if (initialCatchUp.getCount() == 0) {
                        health.markDegraded("Replica disconnected from primary");
                    }
                    pauseBeforeReconnect();
                }
            }
        }
    }

    private void synchronize() throws IOException {
        try (Socket socket = new Socket()) {
            activeSocket = socket;
            socket.connect(new InetSocketAddress(host, port), 2_000);
            socket.setTcpNoDelay(true);
            state.markSynchronizing();
            metrics.setGauge("replication.connected", 1);
            try (DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                writeHandshake(output);
                while (running) {
                    int message = input.read();
                    if (message < 0) throw new EOFException("Primary closed replication stream");
                    process(message, input, output);
                }
            }
        } finally {
            activeSocket = null;
        }
    }

    private void process(
            int message, DataInputStream input, DataOutputStream output) throws IOException {
        switch (message) {
            case PrimaryReplicationEndpoint.SNAPSHOT -> {
                byte[] encoded = readFrame(input);
                applier.install(snapshots.decodeTransfer(encoded));
                acknowledge(output, state.appliedVersion());
            }
            case PrimaryReplicationEndpoint.COMMIT -> {
                CommitRecord record = walCodec.decode(readFrame(input));
                applier.apply(record);
                acknowledge(output, record.version());
                state.markCaughtUp(record.version());
                publishState();
            }
            case PrimaryReplicationEndpoint.CAUGHT_UP -> {
                long primaryVersion = input.readLong();
                if (state.appliedVersion() != primaryVersion) {
                    state.markFailed();
                    throw new IOException("Replica did not reach the primary synchronization boundary");
                }
                state.markCaughtUp(primaryVersion);
                publishState();
                metrics.incrementCounter("replication.catch_ups");
                initialCatchUp.countDown();
                health.markReady();
            }
            case PrimaryReplicationEndpoint.ERROR ->
                    throw new IOException("Primary rejected replication: " + input.readUTF());
            default -> throw new IOException("Unknown replication message: " + message);
        }
    }

    private void writeHandshake(DataOutputStream output) throws IOException {
        output.writeInt(PrimaryReplicationEndpoint.MAGIC);
        output.writeShort(PrimaryReplicationEndpoint.PROTOCOL_VERSION);
        output.writeUTF(token);
        output.writeUTF(nodeId);
        output.writeLong(state.appliedVersion());
        output.flush();
    }

    private static byte[] readFrame(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > PrimaryReplicationEndpoint.MAX_FRAME_BYTES) {
            throw new IOException("Invalid replication frame size");
        }
        byte[] payload = input.readNBytes(size);
        if (payload.length != size) throw new IOException("Truncated replication frame");
        return payload;
    }

    private void acknowledge(DataOutputStream output, long version) throws IOException {
        output.writeLong(version);
        output.flush();
        state.markAcknowledged(version);
        metrics.incrementCounter("replication.applied_commits");
    }

    private void publishState() {
        metrics.setGauge("replication.applied_version", state.appliedVersion());
        metrics.setGauge("replication.acknowledged_version", state.acknowledgedVersion());
        metrics.setGauge("replication.primary_version", state.primaryVersion());
        metrics.setGauge("replication.lag", state.lag());
    }

    private void pauseBeforeReconnect() {
        try {
            Thread.sleep(RECONNECT_DELAY);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops reconnect attempts and closes the active primary connection. */
    @Override
    public synchronized void close() throws IOException {
        running = false;
        Socket socket = activeSocket;
        if (socket != null) socket.close();
        Thread thread = worker;
        if (thread != null) thread.interrupt();
        metrics.setGauge("replication.connected", 0);
    }
}
