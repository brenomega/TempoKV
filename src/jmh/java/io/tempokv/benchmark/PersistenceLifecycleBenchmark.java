package io.tempokv.benchmark;

import io.tempokv.persistence.FileSystemAdapter;
import io.tempokv.persistence.FileWriteAheadLog;
import io.tempokv.persistence.FsyncPolicy;
import io.tempokv.persistence.RecoveryManager;
import io.tempokv.persistence.SnapshotStore;
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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class PersistenceLifecycleBenchmark {
    @State(Scope.Thread)
    public static class ReplayState extends DirectoryState {
        @Param({"100", "1000"})
        public int records;
        private FileWriteAheadLog wal;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            createDirectory("replay");
            wal = wal(16 * 1024 * 1024L);
            append(wal, 1, records);
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            deleteDirectory();
        }
    }

    @State(Scope.Thread)
    public static class SnapshotState extends DirectoryState {
        private SnapshotStore snapshots;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            createDirectory("snapshot-load");
            snapshots = new SnapshotStore(
                    directory, new FileSystemAdapter());
            snapshots.save(BenchmarkFixtures.snapshot(1_000, 100));
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            deleteDirectory();
        }
    }

    @State(Scope.Thread)
    public static class RecoveryState extends DirectoryState {
        @Param({"100", "1000"})
        public int records;
        private RecoveryManager recovery;
        private MvccStore target;
        private VersionGenerator versions;

        @Setup(Level.Invocation)
        public void setup() throws IOException {
            createDirectory("recovery");
            FileSystemAdapter files = new FileSystemAdapter();
            SnapshotStore snapshots =
                    new SnapshotStore(directory, files);
            StorageSnapshot snapshot =
                    BenchmarkFixtures.snapshot(100, 10);
            snapshots.save(snapshot);
            FileWriteAheadLog wal = new FileWriteAheadLog(
                    directory, files, FsyncPolicy.NEVER);
            append(wal, snapshot.version() + 1, records);
            recovery = new RecoveryManager(snapshots, wal);
            target = new MvccStore();
            versions = new VersionGenerator();
        }

        @TearDown(Level.Invocation)
        public void tearDown() throws IOException {
            deleteDirectory();
        }
    }

    @State(Scope.Thread)
    public static class RotationState extends DirectoryState {
        private FileWriteAheadLog wal;

        @Setup(Level.Invocation)
        public void setup() throws IOException {
            createDirectory("rotation");
            wal = wal(256);
            wal.append(record(1, 200));
        }

        @TearDown(Level.Invocation)
        public void tearDown() throws IOException {
            deleteDirectory();
        }
    }

    @State(Scope.Thread)
    public static class CompactionState extends DirectoryState {
        private FileWriteAheadLog wal;

        @Setup(Level.Invocation)
        public void setup() throws IOException {
            createDirectory("compaction");
            wal = wal(256);
            append(wal, 1, 200);
        }

        @TearDown(Level.Invocation)
        public void tearDown() throws IOException {
            deleteDirectory();
        }
    }

    public abstract static class DirectoryState {
        protected Path directory;

        void createDirectory(String name) throws IOException {
            Path root = Path.of(System.getProperty(
                    "tempokv.benchmark.dir",
                    System.getProperty("java.io.tmpdir")));
            Files.createDirectories(root);
            directory = Files.createTempDirectory(
                    root, "tempokv-jmh-" + name + "-");
        }

        FileWriteAheadLog wal(long segmentBytes) throws IOException {
            return new FileWriteAheadLog(
                    directory,
                    new FileSystemAdapter(),
                    FsyncPolicy.NEVER,
                    segmentBytes);
        }

        void deleteDirectory() throws IOException {
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
    public void walReplay(ReplayState state, Blackhole blackhole)
            throws IOException {
        state.wal.replay(blackhole::consume);
    }

    @Benchmark
    public Object snapshotLoad(SnapshotState state) throws IOException {
        return state.snapshots.load();
    }

    @Benchmark
    public long recoverySnapshotAndWal(RecoveryState state)
            throws IOException {
        state.recovery.recover(state.target, state.versions);
        return state.versions.currentVersion();
    }

    @Benchmark
    public void walSegmentRotation(RotationState state)
            throws IOException {
        state.wal.append(record(2, 200));
    }

    @Benchmark
    public void walCompaction(CompactionState state)
            throws IOException {
        state.wal.compactThrough(100);
    }

    private static void append(
            FileWriteAheadLog wal, long firstVersion, int count)
            throws IOException {
        for (int index = 0; index < count; index++) {
            wal.append(record(firstVersion + index, 32));
        }
    }

    private static CommitRecord record(long version, int payloadBytes) {
        return new CommitRecord(
                version,
                BenchmarkFixtures.BASE_TIME.plusMillis(version),
                List.of(Mutation.put("key-" + version, new byte[payloadBytes])));
    }
}
