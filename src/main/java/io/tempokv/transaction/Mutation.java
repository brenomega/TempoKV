package io.tempokv.transaction;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** Describes one immutable key change, including optional restoration provenance. */
public record Mutation(
        String key,
        Type type,
        byte[] value,
        Instant expiresAt,
        Long restoredFromVersion) {
    /** Distinguishes ordinary changes from value and tombstone restorations. */
    public enum Type {
        PUT,
        TOMBSTONE,
        EXPIRE,
        RESTORE_PUT,
        RESTORE_TOMBSTONE,
        EXPIRED_TOMBSTONE
    }

    /** Preserves the E3 constructor shape for ordinary mutations. */
    public Mutation(String key, Type type, byte[] value, Instant expiresAt) {
        this(key, type, value, expiresAt, null);
    }

    /** Defensively copies mutable values and validates the mutation shape. */
    public Mutation {
        key = requireKey(key);
        type = Objects.requireNonNull(type, "type");
        value = value == null ? null : Arrays.copyOf(value, value.length);
        if ((type == Type.PUT || type == Type.RESTORE_PUT) && value == null) {
            throw new IllegalArgumentException(type + " requires a value");
        }
        if (type == Type.EXPIRE && expiresAt == null) throw new IllegalArgumentException("EXPIRE requires an expiration");
        if (type != Type.PUT && type != Type.RESTORE_PUT && value != null) {
            throw new IllegalArgumentException(type + " must not carry a value");
        }
        if (type != Type.EXPIRE && type != Type.RESTORE_PUT && expiresAt != null) {
            throw new IllegalArgumentException(type + " must not carry an expiration");
        }
        boolean restoration = type == Type.RESTORE_PUT || type == Type.RESTORE_TOMBSTONE;
        if (restoration != (restoredFromVersion != null)) {
            throw new IllegalArgumentException("Restoration type and source version must be supplied together");
        }
        if (restoredFromVersion != null && restoredFromVersion < 1) {
            throw new IllegalArgumentException("Restored source version must be positive");
        }
    }

    /** Creates a mutation that stores the supplied binary value. */
    public static Mutation put(String key, byte[] value) { return new Mutation(key, Type.PUT, value, null); }

    /** Creates a deletion tombstone without removing historical versions. */
    public static Mutation tombstone(String key) { return new Mutation(key, Type.TOMBSTONE, null, null); }

    /** Creates a tombstone whose durable history identifies automatic TTL expiration. */
    public static Mutation expiredTombstone(String key) {
        return new Mutation(key, Type.EXPIRED_TOMBSTONE, null, null);
    }

    /** Creates a mutation that changes the expiration of an existing value. */
    public static Mutation expire(String key, Instant expiresAt) { return new Mutation(key, Type.EXPIRE, null, expiresAt); }

    /** Restores a historical value, TTL, and provenance as a new commit. */
    public static Mutation restorePut(
            String key, byte[] value, Instant expiresAt, long restoredFromVersion) {
        return new Mutation(
                key, Type.RESTORE_PUT, value, expiresAt, restoredFromVersion);
    }

    /** Restores a historical tombstone while recording its source version. */
    public static Mutation restoreTombstone(String key, long restoredFromVersion) {
        return new Mutation(
                key, Type.RESTORE_TOMBSTONE, null, null, restoredFromVersion);
    }

    /** Returns a defensive copy of the binary value, when present. */
    @Override public byte[] value() { return value == null ? null : Arrays.copyOf(value, value.length); }

    private static String requireKey(String key) {
        String normalized = Objects.requireNonNull(key, "key");
        if (normalized.isEmpty()) throw new IllegalArgumentException("Key must not be empty");
        return normalized;
    }
}
