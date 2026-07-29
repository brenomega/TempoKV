package io.tempokv.transaction;

import io.tempokv.storage.VersionedValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Holds one session's stable snapshot, ordered write set, and lifecycle state.
 */
public final class TransactionContext {
    /** Identifies whether a context may still accept reads and staged mutations. */
    public enum State { ACTIVE, COMMITTED, ROLLED_BACK, ABORTED }

    private final long snapshotVersion;
    private final int maxMutations;
    private final long maxStagedBytes;
    private final List<Mutation> writeSet = new ArrayList<>();
    private final LinkedHashSet<String> stagedKeys = new LinkedHashSet<>();
    private long stagedBytes;
    private State state = State.ACTIVE;

    /** Creates an active context at a non-negative committed version. */
    public TransactionContext(long snapshotVersion) {
        this(snapshotVersion, 4_096, 32L * 1_048_576);
    }

    /** Creates an active context with explicit mutation and byte limits. */
    public TransactionContext(
            long snapshotVersion, int maxMutations, long maxStagedBytes) {
        if (snapshotVersion < 0) throw new IllegalArgumentException("Snapshot version must not be negative");
        if (maxMutations < 1 || maxStagedBytes < 1) {
            throw new IllegalArgumentException(
                    "Transaction limits must be positive");
        }
        this.snapshotVersion = snapshotVersion;
        this.maxMutations = maxMutations;
        this.maxStagedBytes = maxStagedBytes;
    }

    /** Returns the greatest commit version visible to base reads. */
    public long snapshotVersion() { return snapshotVersion; }

    /** Returns the current lifecycle state. */
    public synchronized State state() { return state; }

    /** Appends one mutation in program order without making it globally visible. */
    public synchronized void stage(Mutation mutation) {
        requireActive();
        Mutation staged = Objects.requireNonNull(mutation, "mutation");
        if (writeSet.size() == maxMutations) {
            throw new IllegalArgumentException(
                    "ERR transaction write set exceeds configured mutation limit");
        }
        if (stagedKeys.contains(staged.key())) {
            throw new IllegalArgumentException(
                    "ERR transaction contains multiple mutations for key");
        }
        long mutationBytes = staged.key().getBytes(
                java.nio.charset.StandardCharsets.UTF_8).length
                + (long) staged.valueSize();
        if (mutationBytes > maxStagedBytes - stagedBytes) {
            throw new IllegalArgumentException(
                    "ERR transaction write set exceeds configured byte limit");
        }
        writeSet.add(staged);
        stagedKeys.add(staged.key());
        stagedBytes += mutationBytes;
    }

    /** Returns an immutable copy of all mutations in commit order. */
    public synchronized List<Mutation> writeSet() {
        return List.copyOf(writeSet);
    }

    /** Returns distinct written keys in deterministic first-write order. */
    public synchronized Set<String> writtenKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        writeSet.forEach(mutation -> keys.add(mutation.key()));
        return Set.copyOf(keys);
    }

    /**
     * Applies staged mutations to a snapshot value, providing read-your-writes semantics.
     */
    public synchronized Optional<PendingValue> visibleValue(
            String key, Optional<VersionedValue> snapshotValue, Instant now) {
        requireActive();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(snapshotValue, "snapshotValue");
        Objects.requireNonNull(now, "now");
        PendingValue current = snapshotValue
                .filter(value -> value.isVisibleAt(now))
                .map(value -> new PendingValue(value.value(), value.expiresAt()))
                .orElse(null);
        for (Mutation mutation : writeSet) {
            if (!mutation.key().equals(key)) continue;
            current = switch (mutation.type()) {
                case PUT -> new PendingValue(mutation.value(), null);
                case RESTORE_PUT -> new PendingValue(mutation.value(), mutation.expiresAt());
                case EXPIRE -> current == null
                        ? null
                        : new PendingValue(current.value(), mutation.expiresAt());
                case TOMBSTONE, RESTORE_TOMBSTONE, EXPIRED_TOMBSTONE -> null;
            };
        }
        return Optional.ofNullable(current)
                .filter(value -> value.expiresAt() == null || now.isBefore(value.expiresAt()));
    }

    /** Marks a successfully published context as committed. */
    public synchronized void markCommitted() {
        transition(State.COMMITTED);
        clearWriteSet();
    }

    /** Marks a client-discarded context as rolled back and clears staged bytes. */
    public synchronized void markRolledBack() {
        transition(State.ROLLED_BACK);
        clearWriteSet();
    }

    /** Marks a conflicting or failed context as aborted and clears staged bytes. */
    public synchronized void markAborted() {
        transition(State.ABORTED);
        clearWriteSet();
    }

    private void clearWriteSet() {
        writeSet.clear();
        stagedKeys.clear();
        stagedBytes = 0;
    }

    private void transition(State target) {
        requireActive();
        state = target;
    }

    private void requireActive() {
        if (state != State.ACTIVE) {
            throw new IllegalStateException("Transaction is no longer active");
        }
    }

    /** Represents a transaction-visible value that has not necessarily been committed. */
    public record PendingValue(byte[] value, Instant expiresAt) {
        /** Defensively copies the staged binary value. */
        public PendingValue {
            value = Arrays.copyOf(Objects.requireNonNull(value, "value"), value.length);
        }
        /** Returns a defensive value copy. */
        @Override public byte[] value() { return Arrays.copyOf(value, value.length); }
    }
}
