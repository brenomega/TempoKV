package io.tempokv.application;

import java.util.Arrays;
import java.util.Objects;

/** Represents a current-state key-value operation independently from the RESP protocol. */
public record KeyValueCommand(Kind kind, String key, byte[] value, long expirationSeconds) implements Command {
    /** Lists the current-state key-value commands supported by the application. */
    public enum Kind { GET, SET, DEL, EXPIRE, TTL }

    /** Validates command-specific arguments and protects binary values from caller mutation. */
    public KeyValueCommand {
        kind = Objects.requireNonNull(kind, "kind");
        key = requireKey(key);
        value = value == null ? null : Arrays.copyOf(value, value.length);
        if (kind == Kind.SET && value == null) throw new IllegalArgumentException("SET requires a value");
        if (kind != Kind.SET && value != null) throw new IllegalArgumentException(kind + " does not accept a value");
        if (kind == Kind.EXPIRE && expirationSeconds < 0) throw new IllegalArgumentException("EXPIRE seconds must not be negative");
        if (kind != Kind.EXPIRE && expirationSeconds != 0) {
            throw new IllegalArgumentException(kind + " does not accept expiration seconds");
        }
    }

    /** Creates a GET command. */
    public static KeyValueCommand get(String key) { return new KeyValueCommand(Kind.GET, key, null, 0); }
    /** Creates a SET command. */
    public static KeyValueCommand set(String key, byte[] value) { return new KeyValueCommand(Kind.SET, key, value, 0); }
    /** Creates a DEL command. */
    public static KeyValueCommand del(String key) { return new KeyValueCommand(Kind.DEL, key, null, 0); }
    /** Creates an EXPIRE command. */
    public static KeyValueCommand expire(String key, long seconds) { return new KeyValueCommand(Kind.EXPIRE, key, null, seconds); }
    /** Creates a TTL command. */
    public static KeyValueCommand ttl(String key) { return new KeyValueCommand(Kind.TTL, key, null, 0); }

    /** Returns the normalized key-value operation name. */
    @Override public String name() { return kind.name(); }
    /** Returns a defensive copy of the SET value. */
    @Override public byte[] value() { return value == null ? null : Arrays.copyOf(value, value.length); }

    private static String requireKey(String key) {
        String normalized = Objects.requireNonNull(key, "key");
        if (normalized.isEmpty()) throw new IllegalArgumentException("Key must not be empty");
        return normalized;
    }
}
