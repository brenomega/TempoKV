package io.tempokv.transaction;

import io.tempokv.storage.StorageEngine;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Serializes version allocation, optional WAL publication, and storage application. */
public final class CommitCoordinator {
    private final VersionGenerator versions;
    private final StorageEngine storage;
    private final Clock clock;
    private final DurableAppender writeAheadLog;

    /** Creates an in-memory coordinator whose E5 WAL port is intentionally a no-op. */
    public CommitCoordinator(VersionGenerator versions, StorageEngine storage, Clock clock) {
        this(versions, storage, clock, ignored -> { });
    }

    /** Creates a coordinator with an optional durable append action executed before publication. */
    public CommitCoordinator(VersionGenerator versions, StorageEngine storage, Clock clock, DurableAppender writeAheadLog) {
        this.versions = Objects.requireNonNull(versions, "versions");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.writeAheadLog = Objects.requireNonNull(writeAheadLog, "writeAheadLog");
    }

    /** Allocates a version and atomically applies the requested mutations after WAL publication. */
    public synchronized CommitRecord commit(List<Mutation> mutations) {
        CommitRecord record = new CommitRecord(versions.nextVersion(), Instant.now(clock), mutations);
        try {
            writeAheadLog.append(record);
        } catch (Exception exception) {
            throw new CommitFailedException(exception);
        }
        storage.apply(record);
        return record;
    }

    /** Allows a durable append implementation to report checked infrastructure failures. */
    @FunctionalInterface
    public interface DurableAppender {
        /** Persists one complete record before it may become visible. */
        void append(CommitRecord record) throws Exception;
    }
}
