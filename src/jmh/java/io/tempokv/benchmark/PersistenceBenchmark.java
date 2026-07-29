package io.tempokv.benchmark;

import io.tempokv.persistence.FileSystemAdapter;
import io.tempokv.persistence.FileWriteAheadLog;
import io.tempokv.persistence.FsyncPolicy;
import io.tempokv.persistence.SnapshotStore;
import io.tempokv.persistence.WalRecordCodec;
import io.tempokv.storage.StorageSnapshot;
import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.Mutation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@State(Scope.Thread)
public class PersistenceBenchmark {
    @Param({"NEVER", "ALWAYS"})
    public String policy;

    private Path directory;
    private FileWriteAheadLog wal;
    private WalRecordCodec codec;
    private SnapshotStore snapshots;
    private StorageSnapshot snapshot;
    private byte[] value;
    private long version;

    @Setup(Level.Iteration)
    public void setup() throws IOException {
        Path root = Path.of(System.getProperty(
                "tempokv.benchmark.dir",
                System.getProperty("java.io.tmpdir")));
        Files.createDirectories(root);
        directory = Files.createTempDirectory(root, "tempokv-jmh-persistence-");
        wal = new FileWriteAheadLog(
                directory,
                new FileSystemAdapter(),
                FsyncPolicy.valueOf(policy));
        codec = new WalRecordCodec();
        snapshots = new SnapshotStore(directory, new FileSystemAdapter());
        snapshot = BenchmarkFixtures.snapshot(1000, 100);
        value = "persistent-benchmark-value".getBytes(StandardCharsets.UTF_8);
        version = 0;
    }

    @TearDown(Level.Iteration)
    public void tearDown() throws IOException {
        wal.close();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Benchmark
    public Object walAppend() throws IOException {
        CommitRecord record = record(++version);
        wal.append(record);
        return record;
    }

    @Benchmark
    public Object walEncode() throws IOException {
        return codec.encode(record(++version));
    }

    @Benchmark
    public void snapshotWrite() throws IOException {
        snapshots.save(snapshot);
    }

    private CommitRecord record(long recordVersion) {
        return new CommitRecord(
                recordVersion,
                BenchmarkFixtures.BASE_TIME.plusMillis(recordVersion),
                List.of(Mutation.put("persistent-key", value)));
    }
}
