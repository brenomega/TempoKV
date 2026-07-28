package io.tempokv.transaction;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** Describes one immutable key change to be applied as part of a commit. */
public record Mutation(String key, Type type, byte[] value, Instant expiresAt) {
    /** Distinguishes value creation, deletion, and expiration updates. */
    public enum Type { PUT, TOMBSTONE, EXPIRE }

    /** Defensively copies mutable values and validates the mutation shape. */
    public Mutation {
        key = requireKey(key);
        type = Objects.requireNonNull(type, "type");
        value = value == null ? null : Arrays.copyOf(value, value.length);
        if (type == Type.PUT && value == null) throw new IllegalArgumentException("PUT requires a value");
        if (type == Type.EXPIRE && expiresAt == null) throw new IllegalArgumentException("EXPIRE requires an expiration");
        if (type != Type.PUT && value != null) throw new IllegalArgumentException(type + " must not carry a value");
    }

    /** Creates a mutation that stores the supplied binary value. */
    public static Mutation put(String key, byte[] value) { return new Mutation(key, Type.PUT, value, null); }

    /** Creates a deletion tombstone without removing historical versions. */
    public static Mutation tombstone(String key) { return new Mutation(key, Type.TOMBSTONE, null, null); }

    /** Creates a mutation that changes the expiration of an existing value. */
    public static Mutation expire(String key, Instant expiresAt) { return new Mutation(key, Type.EXPIRE, null, expiresAt); }

    /** Returns a defensive copy of the binary value, when present. */
    @Override public byte[] value() { return value == null ? null : Arrays.copyOf(value, value.length); }

    private static String requireKey(String key) {
        String normalized = Objects.requireNonNull(key, "key");
        if (normalized.isEmpty()) throw new IllegalArgumentException("Key must not be empty");
        return normalized;
    }
}
