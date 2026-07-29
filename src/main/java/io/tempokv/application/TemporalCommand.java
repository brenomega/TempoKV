package io.tempokv.application;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a historical key operation independently from a transport protocol.
 *
 * <p>Selectors identify either a commit version or the latest commit at or before an instant.
 * RESTOREAT deliberately accepts only a version, making the restored source unambiguous.</p>
 */
public record TemporalCommand(Kind kind, String key, Selector selector, Selector otherSelector,
                              int offset, int limit) implements Command {
    /** Lists the historical operations exposed by the application. */
    public enum Kind { GETAT, HISTORY, DIFF, RESTOREAT }

    /** Identifies a historical point by commit version or commit timestamp. */
    public record Selector(Long version, Instant timestamp) {
        /** Validates that exactly one historical coordinate was supplied. */
        public Selector {
            if ((version == null) == (timestamp == null)) {
                throw new IllegalArgumentException("A selector must contain exactly one of version or timestamp");
            }
            if (version != null && version < 1) throw new IllegalArgumentException("Version must be positive");
        }
        /** Creates a selector for an exact-or-earlier commit version. */
        public static Selector version(long version) { return new Selector(version, null); }
        /** Creates a selector for the latest commit at or before an instant. */
        public static Selector timestamp(Instant timestamp) { return new Selector(null, Objects.requireNonNull(timestamp, "timestamp")); }
    }

    /** Validates temporal command shapes and bounded history pagination. */
    public TemporalCommand {
        kind = Objects.requireNonNull(kind, "kind");
        key = requireKey(key);
        if (offset < 0 || limit < 1 || limit > 1_000) throw new IllegalArgumentException("Invalid history offset or limit");
        switch (kind) {
            case GETAT, RESTOREAT -> {
                selector = Objects.requireNonNull(selector, "selector");
                if (otherSelector != null) throw new IllegalArgumentException(kind + " accepts one selector");
                if (kind == Kind.RESTOREAT && selector.version() == null) {
                    throw new IllegalArgumentException("RESTOREAT requires a version selector");
                }
            }
            case DIFF -> {
                selector = Objects.requireNonNull(selector, "selector");
                otherSelector = Objects.requireNonNull(otherSelector, "otherSelector");
            }
            case HISTORY -> {
                if (selector != null || otherSelector != null) throw new IllegalArgumentException("HISTORY does not accept selectors");
            }
        }
    }

    /** Creates a historical read by version. */
    public static TemporalCommand getAtVersion(String key, long version) { return new TemporalCommand(Kind.GETAT, key, Selector.version(version), null, 0, 1); }
    /** Creates a historical read by timestamp. */
    public static TemporalCommand getAtTimestamp(String key, Instant timestamp) { return new TemporalCommand(Kind.GETAT, key, Selector.timestamp(timestamp), null, 0, 1); }
    /** Creates a bounded newest-first history request. */
    public static TemporalCommand history(String key, int offset, int limit) { return new TemporalCommand(Kind.HISTORY, key, null, null, offset, limit); }
    /** Creates a comparison of two historical coordinates. */
    public static TemporalCommand diff(String key, Selector first, Selector second) { return new TemporalCommand(Kind.DIFF, key, first, second, 0, 1); }
    /** Creates an append-only restoration from an explicitly retained version. */
    public static TemporalCommand restoreAt(String key, long version) { return new TemporalCommand(Kind.RESTOREAT, key, Selector.version(version), null, 0, 1); }

    /** Returns the normalized temporal operation name. */
    @Override public String name() { return kind.name(); }

    private static String requireKey(String key) {
        String normalized = Objects.requireNonNull(key, "key");
        if (normalized.isEmpty()) throw new IllegalArgumentException("Key must not be empty");
        return normalized;
    }
}
