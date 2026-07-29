package io.tempokv.storage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Holds an immutable newest-first history for one key and resolves its current visibility. */
public final class VersionChain {
    private final Node head;
    private final int size;
    private volatile List<VersionedValue> materialized;

    /** Creates an empty chain. */
    public VersionChain() {
        this(null, 0, List.of());
    }

    private VersionChain(
            Node head,
            int size,
            List<VersionedValue> materialized) {
        this.head = head;
        this.size = size;
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
        Node head = null;
        for (int index = copy.size() - 1; index >= 0; index--) {
            head = new Node(copy.get(index), head);
        }
        return new VersionChain(head, copy.size(), copy);
    }

    /** Returns a new chain with the supplied newer version prepended. */
    public VersionChain append(VersionedValue value) {
        Objects.requireNonNull(value, "value");
        if (head != null && value.version() <= head.value().version()) {
            throw new IllegalArgumentException("Versions must be strictly monotonic");
        }
        return new VersionChain(new Node(value, head), size + 1, null);
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
        for (Node node = head; node != null; node = node.next()) {
            if (node.value().version() <= version) {
                return Optional.of(node.value());
            }
        }
        return Optional.empty();
    }

    /** Selects the newest version committed at or before a requested instant. */
    public Optional<VersionedValue> atTimestamp(Instant timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        for (Node node = head; node != null; node = node.next()) {
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

    private record Node(VersionedValue value, Node next) {
        private Node {
            Objects.requireNonNull(value, "value");
        }
    }
}
