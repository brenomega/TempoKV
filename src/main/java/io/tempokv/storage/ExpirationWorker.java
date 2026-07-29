package io.tempokv.storage;

import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.Mutation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Converts due current TTL entries into durable tombstone commits. */
public final class ExpirationWorker implements AutoCloseable {
    private final MvccStore storage; private final CommitCoordinator commits; private final Clock clock;
    private final Consumer<Throwable> failureHandler;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("tempokv-expiration-", 0).factory());

    /** Creates a worker whose clock is shared with the commit pipeline. */
    public ExpirationWorker(MvccStore storage, CommitCoordinator commits, Clock clock) {
        this(storage, commits, clock, ignored -> { });
    }

    /** Creates a worker that reports failures without permanently cancelling its schedule. */
    public ExpirationWorker(
            MvccStore storage,
            CommitCoordinator commits,
            Clock clock,
            Consumer<Throwable> failureHandler) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }
    /** Starts periodic active expiration. */
    public void start() {
        executor.scheduleWithFixedDelay(this::expireDueSafely, 10, 10, TimeUnit.MILLISECONDS);
    }
    /** Processes every due entry once, discarding entries made obsolete by a newer version. */
    public void expireDue() {
        Instant now = Instant.now(clock); TtlIndex.Entry entry;
        while ((entry = storage.ttlIndex().pollExpired(now)) != null) {
            if (storage.isCurrentVersion(entry.key(), entry.version(), now)) {
                commits.commit(List.of(Mutation.expiredTombstone(entry.key())));
            }
        }
    }

    private void expireDueSafely() {
        try {
            expireDue();
        } catch (Throwable failure) {
            failureHandler.accept(failure);
        }
    }
    /** Stops future expiration work. */
    @Override public void close() { executor.shutdownNow(); }
}
