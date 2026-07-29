package io.tempokv.benchmark;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.server.Session;
import io.tempokv.storage.MvccStore;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.ConflictDetector;
import io.tempokv.transaction.Mutation;
import io.tempokv.transaction.SnapshotManager;
import io.tempokv.transaction.TransactionManager;
import io.tempokv.transaction.VersionGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.ThreadParams;

@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class TransactionBenchmark {
    @State(Scope.Thread)
    public static class LifecycleState {
        private TransactionManager transactions;
        private Session first;
        private Session second;

        @Setup(Level.Invocation)
        public void setup() {
            MvccStore storage = new MvccStore();
            CommitCoordinator commits = new CommitCoordinator(
                    new VersionGenerator(),
                    storage,
                    Clock.fixed(
                            BenchmarkFixtures.BASE_TIME,
                            ZoneOffset.UTC));
            SnapshotManager snapshots =
                    new SnapshotManager(commits::currentVersion);
            transactions = new TransactionManager(
                    storage,
                    commits,
                    snapshots,
                    new ConflictDetector(storage),
                    new MetricsRegistry());
            first = new Session();
            second = new Session();
        }
    }

    @State(Scope.Benchmark)
    public static class ConcurrentState {
        private TransactionManager transactions;

        @Setup
        public void setup() {
            MvccStore storage = new MvccStore();
            CommitCoordinator commits = new CommitCoordinator(
                    new VersionGenerator(),
                    storage,
                    Clock.fixed(
                            BenchmarkFixtures.BASE_TIME,
                            ZoneOffset.UTC));
            SnapshotManager snapshots =
                    new SnapshotManager(commits::currentVersion);
            transactions = new TransactionManager(
                    storage,
                    commits,
                    snapshots,
                    new ConflictDetector(storage),
                    new MetricsRegistry());
        }
    }

    @State(Scope.Thread)
    public static class ClientState {
        private Session session;
        private String key;

        @Setup(Level.Invocation)
        public void setup(ThreadParams thread) {
            session = new Session();
            key = "client-" + thread.getThreadIndex();
        }
    }

    @Benchmark
    public Object emptyCommit(LifecycleState state) {
        state.transactions.begin(state.first);
        return state.transactions.commit(state.first);
    }

    @Benchmark
    public Object singleKeyCommit(LifecycleState state) {
        state.transactions.begin(state.first);
        state.transactions.stage(
                state.first, Mutation.put("key", bytes("value")));
        return state.transactions.commit(state.first);
    }

    @Benchmark
    public Object multiKeyCommit(LifecycleState state) {
        state.transactions.begin(state.first);
        for (int index = 0; index < 16; index++) {
            state.transactions.stage(
                    state.first,
                    Mutation.put("key-" + index, bytes("value")));
        }
        return state.transactions.commit(state.first);
    }

    @Benchmark
    public void rollback(LifecycleState state, Blackhole blackhole) {
        blackhole.consume(state.transactions.begin(state.first));
        state.transactions.rollback(state.first);
    }

    @Benchmark
    public Object deterministicConflict(LifecycleState state) {
        state.transactions.begin(state.first);
        state.transactions.begin(state.second);
        state.transactions.stage(
                state.first, Mutation.put("key", bytes("first")));
        state.transactions.stage(
                state.second, Mutation.put("key", bytes("second")));
        state.transactions.commit(state.first);
        return state.transactions.commit(state.second);
    }

    @Benchmark
    public Object conflictFreeConcurrentCommit(
            ConcurrentState state, ClientState client) {
        state.transactions.begin(client.session);
        state.transactions.stage(
                client.session,
                Mutation.put(client.key, bytes("value")));
        return state.transactions.commit(client.session);
    }

    @Benchmark
    @Threads(2)
    public Object conflictFree2Clients(
            ConcurrentState state, ClientState client) {
        return conflictFreeConcurrentCommit(state, client);
    }

    @Benchmark
    @Threads(4)
    public Object conflictFree4Clients(
            ConcurrentState state, ClientState client) {
        return conflictFreeConcurrentCommit(state, client);
    }

    @Benchmark
    @Threads(8)
    public Object conflictFree8Clients(
            ConcurrentState state, ClientState client) {
        return conflictFreeConcurrentCommit(state, client);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
