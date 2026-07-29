package io.tempokv.benchmark;

import io.tempokv.application.TemporalCommand;
import io.tempokv.application.TemporalCommandHandler;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.server.Session;
import io.tempokv.storage.MvccStore;
import io.tempokv.storage.StorageSnapshot;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.VersionGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@State(Scope.Benchmark)
public class StorageReadBenchmark {
    @Param({"100", "1000", "10000"})
    public int datasetSize;

    @Param({"10", "100", "1000"})
    public int historyDepth;

    private MvccStore store;
    private Instant now;
    private long newestVersion;
    private long oldestVersion;
    private TemporalCommandHandler temporal;
    private Session session;

    @Setup
    public void setup() {
        StorageSnapshot snapshot =
                BenchmarkFixtures.snapshot(datasetSize, historyDepth);
        store = new MvccStore();
        store.restore(snapshot);
        now = BenchmarkFixtures.BASE_TIME.plusSeconds(60);
        newestVersion = snapshot.version();
        oldestVersion = datasetSize + 1L;
        temporal = new TemporalCommandHandler(
                store,
                new CommitCoordinator(
                        new VersionGenerator(),
                        store,
                        Clock.fixed(now, ZoneOffset.UTC)),
                new MetricsRegistry());
        session = new Session();
    }

    @Benchmark
    public Object currentGetHit() {
        return store.get("key-50", now);
    }

    @Benchmark
    public Object currentGetMiss() {
        return store.get("missing", now);
    }

    @Benchmark
    public Object historicalNewest() {
        return store.historical("hot", newestVersion, null);
    }

    @Benchmark
    public Object historicalOldest() {
        return store.historical("hot", oldestVersion, null);
    }

    @Benchmark
    public Object historicalOldestTimestamp() {
        return store.historical(
                "hot",
                null,
                BenchmarkFixtures.BASE_TIME.plusMillis(oldestVersion));
    }

    @Benchmark
    public long historyShallow() {
        return consumeHistory(Math.min(10, historyDepth));
    }

    @Benchmark
    public long historyDeep() {
        return consumeHistory(historyDepth);
    }

    @Benchmark
    public Object diffDistant() {
        return temporal.handle(
                TemporalCommand.diff(
                        "hot",
                        TemporalCommand.Selector.version(oldestVersion),
                        TemporalCommand.Selector.version(newestVersion)),
                session);
    }

    @Benchmark
    public Object diffNear() {
        return temporal.handle(
                TemporalCommand.diff(
                        "hot",
                        TemporalCommand.Selector.version(newestVersion - 1),
                        TemporalCommand.Selector.version(newestVersion)),
                session);
    }

    private long consumeHistory(int limit) {
        long checksum = 0;
        for (var value : store.history("hot", 0, limit)) {
            checksum += value.version();
            byte[] bytes = value.value();
            checksum += bytes == null ? 0 : bytes.length;
        }
        return checksum;
    }
}
