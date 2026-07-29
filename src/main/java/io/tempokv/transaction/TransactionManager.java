package io.tempokv.transaction;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.server.Session;
import io.tempokv.storage.StorageEngine;
import io.tempokv.storage.VersionedValue;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates transaction snapshots, staged writes, validation, and terminal state transitions.
 */
public final class TransactionManager {
    private final StorageEngine storage;
    private final CommitCoordinator commits;
    private final SnapshotManager snapshots;
    private final ConflictDetector conflicts;
    private final MetricsRegistry metrics;
    private final int maxMutations;
    private final long maxWriteBytes;

    /** Creates the session transaction service over the node's shared commit pipeline. */
    public TransactionManager(
            StorageEngine storage,
            CommitCoordinator commits,
            SnapshotManager snapshots,
            ConflictDetector conflicts,
            MetricsRegistry metrics) {
        this(
                storage,
                commits,
                snapshots,
                conflicts,
                metrics,
                4_096,
                32L * 1_048_576);
    }

    /** Creates the transaction service with explicit write-set limits. */
    public TransactionManager(
            StorageEngine storage,
            CommitCoordinator commits,
            SnapshotManager snapshots,
            ConflictDetector conflicts,
            MetricsRegistry metrics,
            int maxMutations,
            long maxWriteBytes) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.conflicts = Objects.requireNonNull(conflicts, "conflicts");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (maxMutations < 1 || maxWriteBytes < 1) {
            throw new IllegalArgumentException(
                    "Transaction limits must be positive");
        }
        this.maxMutations = maxMutations;
        this.maxWriteBytes = maxWriteBytes;
    }

    /** Opens one snapshot and attaches its context to a session with no active transaction. */
    public TransactionContext begin(Session session) {
        Objects.requireNonNull(session, "session");
        if (session.transaction().isPresent()) throw new IllegalStateException("ERR transaction already active");
        long snapshot = snapshots.openSnapshot();
        TransactionContext context =
                new TransactionContext(snapshot, maxMutations, maxWriteBytes);
        try {
            session.attachTransaction(context, () -> abortDetached(session, context));
        } catch (RuntimeException failure) {
            snapshots.releaseSnapshot(snapshot);
            throw failure;
        }
        updateSnapshotGauge();
        metrics.incrementCounter("transactions.begun");
        return context;
    }

    /** Adds one ordered mutation to the active session write set. */
    public void stage(Session session, Mutation mutation) {
        requireActive(session).stage(mutation);
    }

    /** Reads the transaction view: snapshot state followed by the session's staged mutations. */
    public Optional<TransactionContext.PendingValue> get(Session session, String key, Instant now) {
        TransactionContext context = requireActive(session);
        StorageEngine.HistoricalValue historical = context.snapshotVersion() == 0
                ? StorageEngine.HistoricalValue.missingKey()
                : storage.historical(key, context.snapshotVersion(), null);
        Optional<VersionedValue> base =
                historical.status() == StorageEngine.HistoricalValue.Status.FOUND
                        ? Optional.of(historical.value())
                        : Optional.empty();
        return context.visibleValue(key, base, now);
    }

    /** Returns transaction-visible Redis-compatible TTL seconds. */
    public long ttl(Session session, String key, Instant now) {
        return get(session, key, now).map(value -> {
            if (value.expiresAt() == null) return -1L;
            return Math.max(0L, java.time.Duration.between(now, value.expiresAt()).toSeconds());
        }).orElse(-2L);
    }

    /**
     * Validates and publishes the complete write set under the coordinator lock.
     *
     * <p>A conflicting or failed commit always terminates the transaction and releases its
     * snapshot. Conflict keys are returned without allocating a version or appending the WAL.</p>
     */
    public CommitOutcome commit(Session session) {
        TransactionContext context = requireActive(session);
        AtomicReference<List<String>> detected = new AtomicReference<>(List.of());
        try {
            if (context.writeSet().isEmpty()) {
                context.markCommitted();
                metrics.incrementCounter("transactions.committed");
                return CommitOutcome.committed(null);
            }
            CommitRecord record = commits.commitValidated(context.writeSet(), () -> {
                detected.set(conflicts.conflicts(context));
                if (!detected.get().isEmpty()) throw new ConflictSignal();
            });
            context.markCommitted();
            metrics.incrementCounter("transactions.committed");
            return CommitOutcome.committed(record);
        } catch (ConflictSignal conflict) {
            List<String> keys = detected.get();
            context.markAborted();
            metrics.incrementCounter("transactions.conflicts");
            return CommitOutcome.conflicted(keys);
        } catch (RuntimeException failure) {
            context.markAborted();
            metrics.incrementCounter("transactions.failures");
            throw failure;
        } finally {
            detachAndRelease(session, context);
        }
    }

    /** Discards the active write set without allocating a commit version. */
    public void rollback(Session session) {
        TransactionContext context = requireActive(session);
        context.markRolledBack();
        metrics.incrementCounter("transactions.rolled_back");
        detachAndRelease(session, context);
    }

    private TransactionContext requireActive(Session session) {
        return Objects.requireNonNull(session, "session").transaction()
                .orElseThrow(() -> new IllegalStateException("ERR no active transaction"));
    }

    private void abortDetached(Session session, TransactionContext context) {
        if (context.state() == TransactionContext.State.ACTIVE) {
            context.markAborted();
            metrics.incrementCounter("transactions.aborted");
            snapshots.releaseSnapshot(context.snapshotVersion());
            updateSnapshotGauge();
        }
        session.detachTransaction(context);
    }

    private void detachAndRelease(Session session, TransactionContext context) {
        session.detachTransaction(context);
        snapshots.releaseSnapshot(context.snapshotVersion());
        updateSnapshotGauge();
    }

    private void updateSnapshotGauge() {
        metrics.setGauge("snapshots.active", snapshots.activeCount());
        metrics.setGauge("snapshots.oldest_version", snapshots.oldestActiveVersion());
    }

    /** Describes either one committed record or a conflict that published nothing. */
    public record CommitOutcome(
            CommitRecord record, List<String> conflictingKeys, boolean committed) {
        /** Copies the client-safe conflict key list and validates mutually exclusive outcomes. */
        public CommitOutcome {
            conflictingKeys = List.copyOf(Objects.requireNonNull(conflictingKeys, "conflictingKeys"));
            if (committed && !conflictingKeys.isEmpty()) {
                throw new IllegalArgumentException("Committed outcome must not contain conflicts");
            }
            if (!committed && (record != null || conflictingKeys.isEmpty())) {
                throw new IllegalArgumentException("Conflicted outcome requires keys and no record");
            }
        }
        /** Creates a successful outcome; a null record denotes a read-only commit. */
        static CommitOutcome committed(CommitRecord record) {
            return new CommitOutcome(record, List.of(), true);
        }
        /** Creates an outcome for a transaction rejected before WAL publication. */
        static CommitOutcome conflicted(List<String> keys) {
            return new CommitOutcome(null, keys, false);
        }
        /** Returns whether validation rejected the transaction. */
        public boolean conflicted() { return !committed; }
    }

    /** Internal control-flow signal thrown while the commit monitor is held. */
    private static final class ConflictSignal extends RuntimeException {
        private ConflictSignal() { super(null, null, false, false); }
    }
}
