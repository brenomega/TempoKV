package io.tempokv.storage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Holds an immutable newest-first history for one key and resolves its current visibility. */
public final class VersionChain {
    private static final int CHECKPOINT_STRIDE = 64;
    private final Node head;
    private final int size;
    private final Checkpoint checkpoints;
    private final boolean timestampsMonotonic;
    private volatile List<VersionedValue> materialized;

    /** Creates an empty chain. */
    public VersionChain() {
        this(null, 0, null, true, List.of());
    }

    private VersionChain(
            Node head,
            int size,
            Checkpoint checkpoints,
            boolean timestampsMonotonic,
            List<VersionedValue> materialized) {
        this.head = head;
        this.size = size;
        this.checkpoints = checkpoints;
        this.timestampsMonotonic = timestampsMonotonic;
        this.materialized = materialized;
    }

    /** Rebuilds a chain from an already validated newest-first retained prefix. */
    public static VersionChain fromNewestFirst(List<VersionedValue> versions) {
        List<VersionedValue> copy = List.copyOf(Objects.requireNonNull(versions, "versions"));
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).version() <= copy.get(index).version()) {
                throw new IllegalArgumentException("Versions must be strictly newest-first");
            }
        }
        VersionChain chain = new VersionChain();
        for (int index = copy.size() - 1; index >= 0; index--) {
            chain = chain.append(copy.get(index));
        }
        chain.materialized = copy;
        return chain;
    }

    /** Returns a new chain with the supplied newer version prepended. */
    public VersionChain append(VersionedValue value) {
        Objects.requireNonNull(value, "value");
        if (head != null && value.version() <= head.value().version()) {
            throw new IllegalArgumentException("Versions must be strictly monotonic");
        }
        Node next = new Node(value, head);
        int nextSize = size + 1;
        Checkpoint nextCheckpoints = nextSize % CHECKPOINT_STRIDE == 0
                ? new Checkpoint(next, checkpoints)
                : checkpoints;
        boolean monotonic = timestampsMonotonic
                && (head == null
                || !value.committedAt().isBefore(head.value().committedAt()));
        return new VersionChain(
                next,
                nextSize,
                nextCheckpoints,
                monotonic,
                null);
    }

    /** Returns the latest version when it is visible at the supplied instant. */
    public Optional<VersionedValue> current(Instant instant) {
        if (head == null) return Optional.empty();
        return head.value().isVisibleAt(instant)
                ? Optional.of(head.value())
                : Optional.empty();
    }

    /** Returns the latest retained version regardless of tombstone or TTL visibility. */
    public Optional<VersionedValue> latest() {
        return head == null ? Optional.empty() : Optional.of(head.value());
    }

    /** Selects the newest version at or before a requested commit version. */
    public Optional<VersionedValue> atVersion(long version) {
        if (version < 1) throw new IllegalArgumentException("Version must be positive");
        for (Node node = nearVersion(version);
                node != null;
                node = node.next()) {
            if (node.value().version() <= version) {
                return Optional.of(node.value());
            }
        }
        return Optional.empty();
    }

    /** Selects the newest version committed at or before a requested instant. */
    public Optional<VersionedValue> atTimestamp(Instant timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        Node start = timestampsMonotonic
                ? nearTimestamp(timestamp)
                : head;
        for (Node node = start; node != null; node = node.next()) {
            if (!node.value().committedAt().isAfter(timestamp)) {
                return Optional.of(node.value());
            }
        }
        return Optional.empty();
    }

    /** Returns a chain retaining the supplied newest-first versions. */
    public VersionChain retain(java.util.function.Predicate<VersionedValue> retained) {
        Objects.requireNonNull(retained, "retained");
        return fromNewestFirst(versions().stream().filter(retained).toList());
    }

    /** Returns the oldest retained version, when one exists. */
    public Optional<VersionedValue> oldest() {
        if (head == null) return Optional.empty();
        Node oldest = head;
        while (oldest.next() != null) oldest = oldest.next();
        return Optional.of(oldest.value());
    }

    /** Returns an immutable newest-first history for later temporal operations. */
    public List<VersionedValue> versions() {
        List<VersionedValue> cached = materialized;
        if (cached != null) return cached;
        java.util.ArrayList<VersionedValue> values =
                new java.util.ArrayList<>(size);
        for (Node node = head; node != null; node = node.next()) {
            values.add(node.value());
        }
        List<VersionedValue> immutable = List.copyOf(values);
        materialized = immutable;
        return immutable;
    }

    /** Returns the sparse checkpoint count for focused memory-shape tests. */
    int checkpointCount() {
        int count = 0;
        for (Checkpoint checkpoint = checkpoints;
                checkpoint != null;
                checkpoint = checkpoint.older()) {
            count++;
        }
        return count;
    }

    private Node nearVersion(long version) {
        Node start = head;
        for (Checkpoint checkpoint = checkpoints;
                checkpoint != null
                        && checkpoint.node().value().version() > version;
                checkpoint = checkpoint.older()) {
            start = checkpoint.node();
        }
        return start;
    }

    private Node nearTimestamp(Instant timestamp) {
        Node start = head;
        for (Checkpoint checkpoint = checkpoints;
                checkpoint != null
                        && checkpoint.node().value().committedAt()
                                .isAfter(timestamp);
                checkpoint = checkpoint.older()) {
            start = checkpoint.node();
        }
        return start;
    }

    private record Node(VersionedValue value, Node next) {
        private Node {
            Objects.requireNonNull(value, "value");
        }
    }

    private record Checkpoint(Node node, Checkpoint older) {
        private Checkpoint {
            Objects.requireNonNull(node, "node");
        }
    }
}
