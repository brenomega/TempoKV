package io.tempokv.storage;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** Represents an immutable value version, deletion tombstone, expiration, and restoration provenance. */
public record VersionedValue(
        long version,
        byte[] value,
        boolean tombstone,
        Instant committedAt,
        Instant expiresAt,
        Long restoredFromVersion,
        TombstoneReason tombstoneReason) {
    /** Identifies why a tombstone was committed for historical auditing. */
    public enum TombstoneReason { DELETED, EXPIRED, RESTORED }

    /** Creates a version while deriving ordinary tombstone provenance. */
    public VersionedValue(
            long version,
            byte[] value,
            boolean tombstone,
            Instant committedAt,
            Instant expiresAt,
            Long restoredFromVersion) {
        this(
                version,
                value,
                tombstone,
                committedAt,
                expiresAt,
                restoredFromVersion,
                tombstone
                        ? (restoredFromVersion == null
                                ? TombstoneReason.DELETED
                                : TombstoneReason.RESTORED)
                        : null);
    }

    /** Creates a version without restoration provenance. */
    public VersionedValue(
            long version, byte[] value, boolean tombstone, Instant committedAt, Instant expiresAt) {
        this(version, value, tombstone, committedAt, expiresAt, null);
    }

    /** Defensively copies values and validates the version metadata. */
    public VersionedValue {
        if (version < 1) throw new IllegalArgumentException("Version must be positive");
        value = value == null ? null : Arrays.copyOf(value, value.length);
        if (tombstone == (value != null)) throw new IllegalArgumentException("A version must contain either a value or a tombstone");
        committedAt = Objects.requireNonNull(committedAt, "committedAt");
        if (tombstone && expiresAt != null) throw new IllegalArgumentException("Tombstones must not expire");
        if (tombstone != (tombstoneReason != null)) {
            throw new IllegalArgumentException("Only tombstones require a tombstone reason");
        }
        if (restoredFromVersion != null && restoredFromVersion < 1) {
            throw new IllegalArgumentException("Restored source version must be positive");
        }
    }

    /** Returns a defensive copy of the stored binary value. */
    @Override public byte[] value() { return value == null ? null : Arrays.copyOf(value, value.length); }

    /** Returns whether this version is unavailable at the supplied instant. */
    public boolean isVisibleAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !tombstone && (expiresAt == null || instant.isBefore(expiresAt));
    }
}
