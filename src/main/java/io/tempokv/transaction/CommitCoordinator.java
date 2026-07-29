package io.tempokv.transaction;

import io.tempokv.storage.StorageEngine;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Serializes version allocation, optional WAL publication, and storage application. */
public final class CommitCoordinator {
    private final VersionGenerator versions;
    private final StorageEngine storage;
    private final Clock clock;
    private final DurableAppender writeAheadLog;
    private final Consumer<Throwable> failureHandler;
    private Consumer<CommitRecord> publisher = ignored -> { };
    private Throwable terminalFailure;

    /** Creates an in-memory coordinator whose WAL append action is intentionally a no-op. */
    public CommitCoordinator(VersionGenerator versions, StorageEngine storage, Clock clock) {
        this(versions, storage, clock, ignored -> { }, ignored -> { });
    }

    /** Creates a coordinator with an optional durable append action executed before publication. */
    public CommitCoordinator(VersionGenerator versions, StorageEngine storage, Clock clock, DurableAppender writeAheadLog) {
        this(versions, storage, clock, writeAheadLog, ignored -> { });
    }

    /** Creates a coordinator that reports terminal commit-pipeline failures to node health. */
    public CommitCoordinator(
            VersionGenerator versions,
            StorageEngine storage,
            Clock clock,
            DurableAppender writeAheadLog,
            Consumer<Throwable> failureHandler) {
        this.versions = Objects.requireNonNull(versions, "versions");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.writeAheadLog = Objects.requireNonNull(writeAheadLog, "writeAheadLog");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    /** Returns a snapshot cut only after any in-flight commit has completed publication. */
    public synchronized long currentVersion() {
        return versions.currentVersion();
    }

    /** Executes a read-side action while no commit can be allocated or published. */
    public synchronized <T> T withStableState(Supplier<T> action) {
        return Objects.requireNonNull(action, "action").get();
    }

    /** Installs the one non-blocking listener notified after local durable publication. */
    public synchronized void setCommitPublisher(Consumer<CommitRecord> publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /** Allocates a version and atomically applies the requested mutations after WAL publication. */
    public synchronized CommitRecord commit(List<Mutation> mutations) {
        return commitValidated(mutations, () -> { });
    }

    /**
     * Runs conflict validation under the same monitor as ordinary commits, then publishes once.
     *
     * <p>The validation action runs before version allocation and WAL append, closing the race
     * between write-write conflict detection and concurrent non-transactional commits.</p>
     */
    public synchronized CommitRecord commitValidated(
            List<Mutation> mutations, Runnable validation) {
        Objects.requireNonNull(mutations, "mutations");
        if (mutations.isEmpty()) throw new IllegalArgumentException("Commit requires at least one mutation");
        if (terminalFailure != null) throw new CommitFailedException(terminalFailure);
        Objects.requireNonNull(validation, "validation").run();
        CommitRecord record = new CommitRecord(versions.nextVersion(), Instant.now(clock), mutations);
        try {
            writeAheadLog.append(record);
        } catch (Exception exception) {
            markTerminal(exception);
            throw new CommitFailedException(exception);
        }
        try {
            storage.apply(record);
        } catch (RuntimeException | Error failure) {
            markTerminal(failure);
            throw failure;
        }
        try {
            publisher.accept(record);
        } catch (RuntimeException failure) {
            reportFailure(failure);
        }
        return record;
    }

    private void markTerminal(Throwable failure) {
        if (terminalFailure == null) terminalFailure = failure;
        reportFailure(failure);
    }

    private void reportFailure(Throwable failure) {
        try {
            failureHandler.accept(failure);
        } catch (RuntimeException handlerFailure) {
            failure.addSuppressed(handlerFailure);
        }
    }

    /** Allows a durable append implementation to report checked infrastructure failures. */
    @FunctionalInterface
    public interface DurableAppender {
        /** Persists one complete record before it may become visible. */
        void append(CommitRecord record) throws Exception;
    }
}
