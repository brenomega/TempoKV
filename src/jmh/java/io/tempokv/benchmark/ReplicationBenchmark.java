package io.tempokv.benchmark;

import io.tempokv.bootstrap.ServerConfiguration;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.observability.ServerHealthService;
import io.tempokv.persistence.FileSystemAdapter;
import io.tempokv.persistence.FileWriteAheadLog;
import io.tempokv.persistence.FsyncPolicy;
import io.tempokv.persistence.SnapshotStore;
import io.tempokv.replication.AckTracker;
import io.tempokv.replication.PrimaryReplicationEndpoint;
import io.tempokv.replication.ReplicaApplier;
import io.tempokv.replication.ReplicaClient;
import io.tempokv.replication.ReplicaState;
import io.tempokv.replication.SyncCoordinator;
import io.tempokv.storage.MvccStore;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.Mutation;
import io.tempokv.transaction.VersionGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ReplicationBenchmark {
    @State(Scope.Benchmark)
    public static class NoReplicaState extends Directories {
        private CommitCoordinator commits;

        @Setup
        public void setup() throws IOException {
            primaryDirectory = directory("primary-no-replica");
            MvccStore storage = new MvccStore();
            FileWriteAheadLog wal = wal(primaryDirectory);
            commits = new CommitCoordinator(
                    new VersionGenerator(),
                    storage,
                    Clock.fixed(
                            BenchmarkFixtures.BASE_TIME,
                            ZoneOffset.UTC),
                    wal::append);
        }
    }

    @State(Scope.Benchmark)
    public static class ConnectedReplicaState extends Directories {
        private static final String REPLICA_ID = "benchmark-replica";
        private CommitCoordinator commits;
        private AckTracker acknowledgements;
        private PrimaryReplicationEndpoint endpoint;
        private ReplicaClient client;

        @Setup
        public void setup() throws IOException {
            primaryDirectory = directory("primary-connected");
            replicaDirectory = directory("replica-connected");
            FileSystemAdapter primaryFiles = new FileSystemAdapter();
            MvccStore primaryStorage = new MvccStore();
            FileWriteAheadLog primaryWal = new FileWriteAheadLog(
                    primaryDirectory,
                    primaryFiles,
                    FsyncPolicy.NEVER);
            SnapshotStore primarySnapshots =
                    new SnapshotStore(primaryDirectory, primaryFiles);
            commits = new CommitCoordinator(
                    new VersionGenerator(),
                    primaryStorage,
                    Clock.fixed(
                            BenchmarkFixtures.BASE_TIME,
                            ZoneOffset.UTC),
                    primaryWal::append);
            acknowledgements = new AckTracker();
            endpoint = new PrimaryReplicationEndpoint(
                    0,
                    "benchmark-token",
                    commits,
                    new SyncCoordinator(primaryStorage, primaryWal),
                    primarySnapshots,
                    acknowledgements,
                    new MetricsRegistry());
            endpoint.start();
            commits.setCommitPublisher(endpoint::publish);

            FileSystemAdapter replicaFiles = new FileSystemAdapter();
            MvccStore replicaStorage = new MvccStore();
            VersionGenerator replicaVersions = new VersionGenerator();
            ReplicaState replicaState = new ReplicaState(
                    ServerConfiguration.NodeRole.REPLICA);
            replicaState.initialize(0);
            FileWriteAheadLog replicaWal = new FileWriteAheadLog(
                    replicaDirectory,
                    replicaFiles,
                    FsyncPolicy.NEVER);
            SnapshotStore replicaSnapshots =
                    new SnapshotStore(replicaDirectory, replicaFiles);
            client = new ReplicaClient(
                    "127.0.0.1",
                    endpoint.port(),
                    "benchmark-token",
                    REPLICA_ID,
                    new ReplicaApplier(
                            replicaStorage,
                            replicaVersions,
                            replicaWal,
                            replicaSnapshots,
                            replicaState),
                    replicaState,
                    replicaSnapshots,
                    new MetricsRegistry(),
                    new ServerHealthService());
            client.startAndAwait(Duration.ofSeconds(5));
        }

        long awaitAcknowledgement(long version) throws IOException {
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(5).toNanos();
            while (acknowledgements.snapshot()
                    .getOrDefault(REPLICA_ID, 0L) < version) {
                if (System.nanoTime() >= deadline) {
                    throw new IOException(
                            "Timed out waiting for benchmark replica ACK");
                }
                Thread.onSpinWait();
            }
            return version;
        }

        @Override
        void closeResources() throws IOException {
            IOException failure = null;
            if (client != null) {
                try {
                    client.close();
                } catch (IOException exception) {
                    failure = exception;
                }
            }
            if (endpoint != null) {
                try {
                    endpoint.close();
                } catch (IOException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            }
            if (failure != null) throw failure;
        }
    }

    public abstract static class Directories {
        protected Path primaryDirectory;
        protected Path replicaDirectory;

        Path directory(String name) throws IOException {
            return Files.createTempDirectory(
                    "tempokv-jmh-" + name + "-");
        }

        FileWriteAheadLog wal(Path directory) throws IOException {
            return new FileWriteAheadLog(
                    directory,
                    new FileSystemAdapter(),
                    FsyncPolicy.NEVER);
        }

        void closeResources() throws IOException {
        }

        @TearDown
        public void tearDown() throws IOException {
            closeResources();
            delete(primaryDirectory);
            delete(replicaDirectory);
        }

        private static void delete(Path directory) throws IOException {
            if (directory == null || !Files.exists(directory)) return;
            try (var paths = Files.walk(directory)) {
                for (Path path :
                        paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Benchmark
    public long primaryCommitNoReplica(NoReplicaState state) {
        return commit(state.commits).version();
    }

    @Benchmark
    public long primaryCommitAndReplicaAck(ConnectedReplicaState state)
            throws IOException {
        CommitRecord record = commit(state.commits);
        return state.awaitAcknowledgement(record.version());
    }

    private static CommitRecord commit(CommitCoordinator commits) {
        return commits.commit(List.of(Mutation.put(
                "replicated-key", new byte[32])));
    }
}
