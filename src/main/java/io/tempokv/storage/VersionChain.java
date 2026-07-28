package io.tempokv.storage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Holds an immutable newest-first history for one key and resolves its current visibility. */
public final class VersionChain {
    private final List<VersionedValue> versions;

    /** Creates an empty chain. */
    public VersionChain() { this(List.of()); }

    private VersionChain(List<VersionedValue> versions) { this.versions = versions; }

    /** Rebuilds a chain from an already validated newest-first retained prefix. */
    public static VersionChain fromNewestFirst(List<VersionedValue> versions) {
        List<VersionedValue> copy = List.copyOf(Objects.requireNonNull(versions, "versions"));
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).version() <= copy.get(index).version()) {
                throw new IllegalArgumentException("Versions must be strictly newest-first");
            }
        }
        return new VersionChain(copy);
    }

    /** Returns a new chain with the supplied newer version prepended. */
    public VersionChain append(VersionedValue value) {
        Objects.requireNonNull(value, "value");
        if (!versions.isEmpty() && value.version() <= versions.getFirst().version()) {
            throw new IllegalArgumentException("Versions must be strictly monotonic");
        }
        java.util.ArrayList<VersionedValue> updated = new java.util.ArrayList<>(versions.size() + 1);
        updated.add(value); updated.addAll(versions);
        return new VersionChain(List.copyOf(updated));
    }

    /** Returns the latest version when it is visible at the supplied instant. */
    public Optional<VersionedValue> current(Instant instant) {
        if (versions.isEmpty()) return Optional.empty();
        VersionedValue head = versions.getFirst();
        return head.isVisibleAt(instant) ? Optional.of(head) : Optional.empty();
    }

    /** Selects the newest version at or before a requested commit version. */
    public Optional<VersionedValue> atVersion(long version) {
        if (version < 1) throw new IllegalArgumentException("Version must be positive");
        return versions.stream().filter(candidate -> candidate.version() <= version).findFirst();
    }

    /** Selects the newest version committed at or before a requested instant. */
    public Optional<VersionedValue> atTimestamp(Instant timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        return versions.stream().filter(candidate -> !candidate.committedAt().isAfter(timestamp)).findFirst();
    }

    /** Returns a chain retaining the supplied newest-first versions. */
    public VersionChain retain(java.util.function.Predicate<VersionedValue> retained) {
        Objects.requireNonNull(retained, "retained");
        return new VersionChain(versions.stream().filter(retained).toList());
    }

    /** Returns the oldest retained version, when one exists. */
    public Optional<VersionedValue> oldest() { return versions.isEmpty() ? Optional.empty() : Optional.of(versions.getLast()); }

    /** Returns an immutable newest-first history for later temporal operations. */
    public List<VersionedValue> versions() { return versions; }
}
