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
    private final List<Mutation> writeSet = new ArrayList<>();
    private State state = State.ACTIVE;

    /** Creates an active context at a non-negative committed version. */
    public TransactionContext(long snapshotVersion) {
        if (snapshotVersion < 0) throw new IllegalArgumentException("Snapshot version must not be negative");
        this.snapshotVersion = snapshotVersion;
    }

    /** Returns the greatest commit version visible to base reads. */
    public long snapshotVersion() { return snapshotVersion; }

    /** Returns the current lifecycle state. */
    public synchronized State state() { return state; }

    /** Appends one mutation in program order without making it globally visible. */
    public synchronized void stage(Mutation mutation) {
        requireActive();
        writeSet.add(Objects.requireNonNull(mutation, "mutation"));
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
    }

    /** Marks a client-discarded context as rolled back and clears staged bytes. */
    public synchronized void markRolledBack() {
        transition(State.ROLLED_BACK);
        writeSet.clear();
    }

    /** Marks a conflicting or failed context as aborted and clears staged bytes. */
    public synchronized void markAborted() {
        transition(State.ABORTED);
        writeSet.clear();
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
