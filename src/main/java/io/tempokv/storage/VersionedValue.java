package io.tempokv.storage;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** Represents an immutable value version, deletion tombstone, or expiration state. */
public record VersionedValue(long version, byte[] value, boolean tombstone, Instant committedAt, Instant expiresAt) {
    /** Defensively copies values and validates the version metadata. */
    public VersionedValue {
        if (version < 1) throw new IllegalArgumentException("Version must be positive");
        value = value == null ? null : Arrays.copyOf(value, value.length);
        if (tombstone == (value != null)) throw new IllegalArgumentException("A version must contain either a value or a tombstone");
        committedAt = Objects.requireNonNull(committedAt, "committedAt");
    }

    /** Returns a defensive copy of the stored binary value. */
    @Override public byte[] value() { return value == null ? null : Arrays.copyOf(value, value.length); }

    /** Returns whether this version is unavailable at the supplied instant. */
    public boolean isVisibleAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !tombstone && (expiresAt == null || instant.isBefore(expiresAt));
    }
}
