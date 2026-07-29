package io.tempokv.benchmark;

import io.tempokv.storage.MvccStore;
import io.tempokv.storage.HistoryGarbageCollector;
import io.tempokv.storage.RetentionPolicy;
import io.tempokv.storage.StorageSnapshot;
import io.tempokv.storage.VersionChain;
import io.tempokv.storage.VersionedValue;
import io.tempokv.application.TemporalCommand;
import io.tempokv.application.TemporalCommandHandler;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.server.Session;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.Mutation;
import io.tempokv.transaction.VersionGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@State(Scope.Thread)
public class StorageWriteBenchmark {
    @Param({"100", "1000", "10000"})
    public int datasetSize;

    @Param({"10", "100", "1000"})
    public int historyDepth;

    private StorageSnapshot baseline;
    private MvccStore store;
    private long version;
    private byte[] value;
    private List<Mutation> multiMutation;
    private VersionChain deepChain;
    private CommitCoordinator commits;
    private TemporalCommandHandler temporal;
    private HistoryGarbageCollector garbageCollector;
    private Session session;

    @Setup(Level.Trial)
    public void setupTrial() {
        baseline = BenchmarkFixtures.snapshot(datasetSize, historyDepth);
        store = new MvccStore();
        value = "updated-value".getBytes(StandardCharsets.UTF_8);
        version = baseline.version() + 1;
        ArrayList<Mutation> mutations = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            mutations.add(Mutation.put("key-" + index, value));
        }
        multiMutation = List.copyOf(mutations);
        deepChain = baseline.chains().get("hot");
        VersionGenerator generator = new VersionGenerator();
        generator.advanceTo(baseline.version());
        commits = new CommitCoordinator(
                generator,
                store,
                Clock.fixed(
                        BenchmarkFixtures.BASE_TIME.plusSeconds(60),
                        ZoneOffset.UTC));
        temporal = new TemporalCommandHandler(
                store, commits, new MetricsRegistry());
        garbageCollector = new HistoryGarbageCollector(
                new RetentionPolicy(
                        new RetentionPolicy.Rule(
                                10, Duration.ofNanos(1)),
                        Map.of()));
        session = new Session();
    }

    @Setup(Level.Invocation)
    public void resetStore() {
        store.restore(baseline);
    }

    @Benchmark
    public Object setNewKey() {
        return apply(Mutation.put("new-key", value));
    }

    @Benchmark
    public Object setExistingKey() {
        return apply(Mutation.put("key-50", value));
    }

    @Benchmark
    public Object tombstoneExistingKey() {
        return apply(Mutation.tombstone("key-50"));
    }

    @Benchmark
    public Object expireExistingKey() {
        return apply(Mutation.expire(
                "key-50",
                BenchmarkFixtures.BASE_TIME.plusSeconds(120)));
    }

    @Benchmark
    public Object multiMutationCommit() {
        CommitRecord record = new CommitRecord(
                version,
                BenchmarkFixtures.BASE_TIME.plusSeconds(60),
                multiMutation);
        store.apply(record);
        return record;
    }

    @Benchmark
    public Object noPersistenceCommit() {
        return commits.commit(List.of(Mutation.put("key-50", value)));
    }

    @Benchmark
    public Object appendDeepVersionChain() {
        return deepChain.append(new VersionedValue(
                version,
                value,
                false,
                BenchmarkFixtures.BASE_TIME.plusSeconds(60),
                null));
    }

    @Benchmark
    public Object restoreAtOldestVersion() {
        return temporal.handle(
                TemporalCommand.restoreAt(
                        "hot",
                        datasetSize + 1L),
                session);
    }

    @Benchmark
    public int garbageCollectDeepHistory() {
        return garbageCollector.collect(
                store,
                Instant.MAX,
                0);
    }

    private CommitRecord apply(Mutation mutation) {
        CommitRecord record = new CommitRecord(
                version,
                BenchmarkFixtures.BASE_TIME.plusSeconds(60),
                List.of(mutation));
        store.apply(record);
        return record;
    }
}
