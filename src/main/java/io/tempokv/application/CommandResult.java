package io.tempokv.application;

import java.util.Arrays;
import java.util.Objects;

/**
 * Defines a protocol-neutral result that a front end can encode for its client.
 */
public sealed interface CommandResult permits CommandResult.SimpleString, CommandResult.Error,
        CommandResult.IntegerValue, CommandResult.BulkString, CommandResult.NullValue, CommandResult.Array {
    /** Returns a successful simple string result. */
    static SimpleString simpleString(String value) {
        return new SimpleString(value);
    }

    /** Returns an error result safe to expose to a protocol client. */
    static Error error(String message) {
        return new Error(message);
    }

    /** Represents a successful short textual status. */
    record SimpleString(String value) implements CommandResult {
        public SimpleString { value = requireLine(value, "value"); }
    }

    /** Represents a client-safe application error. */
    record Error(String message) implements CommandResult {
        public Error { message = requireLine(message, "message"); }
    }

    /** Represents an integral application result. */
    record IntegerValue(long value) implements CommandResult { }

    /** Represents a binary-safe application value. */
    record BulkString(byte[] value) implements CommandResult {
        public BulkString { value = Arrays.copyOf(Objects.requireNonNull(value, "value"), value.length); }
        @Override public byte[] value() { return Arrays.copyOf(value, value.length); }
    }

    /** Represents an absent application value. */
    record NullValue() implements CommandResult { }

    /** Represents an ordered, nestable collection result. */
    record Array(java.util.List<CommandResult> values) implements CommandResult {
        /** Copies the response elements so a caller cannot alter an encoded result. */
        public Array { values = java.util.List.copyOf(Objects.requireNonNull(values, "values")); }
    }

    private static String requireLine(String value, String field) {
        String normalized = Objects.requireNonNull(value, field);
        if (normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " must not contain a line break");
        }
        return normalized;
    }
}
