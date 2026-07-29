package io.tempokv.benchmark;

import io.tempokv.application.TemporalCommand;
import io.tempokv.application.TemporalCommandHandler;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.server.Session;
import io.tempokv.storage.MvccStore;
import io.tempokv.storage.StorageSnapshot;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.Mutation;
import io.tempokv.transaction.VersionGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

/** Measures only the historical paths selected for sparse-index validation. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 4, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms768m", "-Xmx768m"})
@State(Scope.Benchmark)
public class HistoricalLookupBenchmark {
    @Param({"1000", "10000", "100000"})
    public int depth;

    private MvccStore store;
    private MvccStore appendStore;
    private TemporalCommandHandler temporal;
    private Session session;
    private Instant now;
    private long middle;
    private AtomicLong appendVersion;

    @Setup
    public void setup() {
        StorageSnapshot snapshot = BenchmarkFixtures.snapshot(0, depth);
        store = new MvccStore();
        store.restore(snapshot);
        appendStore = new MvccStore();
        appendStore.restore(snapshot);
        now = BenchmarkFixtures.BASE_TIME.plusSeconds(1);
        middle = Math.max(1, depth / 2L);
        temporal = new TemporalCommandHandler(
                store,
                new CommitCoordinator(
                        new VersionGenerator(),
                        store,
                        Clock.fixed(now, ZoneOffset.UTC)),
                new MetricsRegistry());
        session = new Session();
        appendVersion = new AtomicLong(depth);
    }

    @Benchmark
    public Object currentVersion() {
        return store.get("hot", now);
    }

    @Benchmark
    public Object middleVersion() {
        return store.historical("hot", middle, null);
    }

    @Benchmark
    public Object oldestVersion() {
        return store.historical("hot", 1L, null);
    }

    @Benchmark
    public Object middleTimestamp() {
        return store.historical(
                "hot",
                null,
                BenchmarkFixtures.BASE_TIME.plusMillis(middle));
    }

    @Benchmark
    public Object oldestTimestamp() {
        return store.historical(
                "hot",
                null,
                BenchmarkFixtures.BASE_TIME.plusMillis(1));
    }

    @Benchmark
    public Object diffNear() {
        return temporal.handle(
                TemporalCommand.diff(
                        "hot",
                        TemporalCommand.Selector.version(depth - 1L),
                        TemporalCommand.Selector.version(depth)),
                session);
    }

    @Benchmark
    public Object diffDistant() {
        return temporal.handle(
                TemporalCommand.diff(
                        "hot",
                        TemporalCommand.Selector.version(1),
                        TemporalCommand.Selector.version(depth)),
                session);
    }

    @Benchmark
    public long append() {
        long version = appendVersion.incrementAndGet();
        appendStore.apply(new CommitRecord(
                version,
                BenchmarkFixtures.BASE_TIME.plusMillis(version),
                List.of(Mutation.put(
                        "hot",
                        Long.toString(version)
                                .getBytes(StandardCharsets.US_ASCII)))));
        return version;
    }
}
