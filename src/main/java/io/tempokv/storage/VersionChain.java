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

    /** Returns an immutable newest-first history for later temporal operations. */
    public List<VersionedValue> versions() { return versions; }
}
