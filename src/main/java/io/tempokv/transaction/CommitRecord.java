package io.tempokv.transaction;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Groups ordered mutations that become visible atomically under one version. */
public record CommitRecord(long version, Instant committedAt, List<Mutation> mutations) {
    /** Validates an immutable, non-empty commit record. */
    public CommitRecord {
        if (version < 1) throw new IllegalArgumentException("Version must be positive");
        committedAt = Objects.requireNonNull(committedAt, "committedAt");
        mutations = List.copyOf(Objects.requireNonNull(mutations, "mutations"));
        if (mutations.isEmpty()) throw new IllegalArgumentException("Commit must contain a mutation");
    }
}
