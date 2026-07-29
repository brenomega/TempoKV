package io.tempokv.benchmark;

import io.tempokv.bootstrap.ServerConfiguration;
import io.tempokv.persistence.FileSystemAdapter;
import io.tempokv.persistence.FileWriteAheadLog;
import io.tempokv.persistence.FsyncPolicy;
import io.tempokv.persistence.SnapshotStore;
import io.tempokv.replication.ReplicaApplier;
import io.tempokv.replication.ReplicaState;
import io.tempokv.replication.SyncCoordinator;
import io.tempokv.storage.MvccStore;
import io.tempokv.storage.StorageSnapshot;
import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.Mutation;
import io.tempokv.transaction.VersionGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
public class ReplicationDataBenchmark {
    @State(Scope.Thread)
    public static class ApplyState extends DirectoryState {
        private ReplicaApplier applier;
        private long version;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            create("replica-apply");
            FileSystemAdapter files = new FileSystemAdapter();
            MvccStore storage = new MvccStore();
            VersionGenerator versions = new VersionGenerator();
            ReplicaState state = state();
            applier = new ReplicaApplier(
                    storage,
                    versions,
                    wal(files),
                    new SnapshotStore(directory, files),
                    state);
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            delete();
        }
    }

    @State(Scope.Thread)
    public static class FullInstallState extends DirectoryState {
        private ReplicaApplier applier;
        private StorageSnapshot snapshot;

        @Setup(Level.Invocation)
        public void setup() throws IOException {
            create("replica-full-install");
            FileSystemAdapter files = new FileSystemAdapter();
            applier = new ReplicaApplier(
                    new MvccStore(),
                    new VersionGenerator(),
                    wal(files),
                    new SnapshotStore(directory, files),
                    state());
            snapshot = BenchmarkFixtures.snapshot(1_000, 100);
        }

        @TearDown(Level.Invocation)
        public void tearDown() throws IOException {
            delete();
        }
    }

    @State(Scope.Thread)
    public static class IncrementalState extends DirectoryState {
        private SyncCoordinator synchronizer;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            create("incremental-plan");
            MvccStore storage = new MvccStore();
            StorageSnapshot snapshot =
                    BenchmarkFixtures.snapshot(1_000, 100);
            storage.restore(snapshot);
            FileWriteAheadLog wal =
                    wal(new FileSystemAdapter());
            for (long version = snapshot.version() + 1;
                    version <= snapshot.version() + 100;
                    version++) {
                CommitRecord record = record(version);
                wal.append(record);
                storage.apply(record);
            }
            synchronizer = new SyncCoordinator(storage, wal);
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            delete();
        }
    }

    public abstract static class DirectoryState {
        protected Path directory;

        void create(String name) throws IOException {
            directory = Files.createTempDirectory(
                    "tempokv-jmh-" + name + "-");
        }

        FileWriteAheadLog wal(FileSystemAdapter files)
                throws IOException {
            return new FileWriteAheadLog(
                    directory, files, FsyncPolicy.NEVER);
        }

        ReplicaState state() {
            ReplicaState state = new ReplicaState(
                    ServerConfiguration.NodeRole.REPLICA);
            state.initialize(0);
            return state;
        }

        void delete() throws IOException {
            if (directory == null || !Files.exists(directory)) return;
            try (var paths = Files.walk(directory)) {
                for (Path path :
                        paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
            directory = null;
        }
    }

    @Benchmark
    public long replicaApply(ApplyState state) throws IOException {
        CommitRecord record = record(++state.version);
        state.applier.apply(record);
        return record.version();
    }

    @Benchmark
    public long fullSnapshotInstall(FullInstallState state)
            throws IOException {
        state.applier.install(state.snapshot);
        return state.snapshot.version();
    }

    @Benchmark
    public Object incrementalCatchUpPlan(IncrementalState state)
            throws IOException {
        return state.synchronizer.plan(1_150);
    }

    private static CommitRecord record(long version) {
        return new CommitRecord(
                version,
                BenchmarkFixtures.BASE_TIME.plusMillis(version),
                List.of(Mutation.put("replicated-key", new byte[32])));
    }
}
