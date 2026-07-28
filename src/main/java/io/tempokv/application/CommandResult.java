package io.tempokv.application;

import java.util.Arrays;
import java.util.Objects;

/**
 * Defines a protocol-neutral result that a front end can encode for its client.
 */
public sealed interface CommandResult permits CommandResult.SimpleString, CommandResult.Error,
        CommandResult.IntegerValue, CommandResult.BulkString, CommandResult.NullValue {
    /** Returns a successful simple string result. */
    static SimpleString simpleString(String value) {
        return new SimpleString(value);
    }

    /** Returns an error result safe to expose to a protocol client. */
    static Error error(String message) {
        return new Error(message);
    }

    /** Represents a RESP simple string. */
    record SimpleString(String value) implements CommandResult {
        public SimpleString { value = requireLine(value, "value"); }
    }

    /** Represents a RESP error. */
    record Error(String message) implements CommandResult {
        public Error { message = requireLine(message, "message"); }
    }

    /** Represents a RESP integer. */
    record IntegerValue(long value) implements CommandResult { }

    /** Represents a binary-safe RESP bulk string. */
    record BulkString(byte[] value) implements CommandResult {
        public BulkString { value = Arrays.copyOf(Objects.requireNonNull(value, "value"), value.length); }
        @Override public byte[] value() { return Arrays.copyOf(value, value.length); }
    }

    /** Represents a RESP null value. */
    record NullValue() implements CommandResult { }

    private static String requireLine(String value, String field) {
        String normalized = Objects.requireNonNull(value, field);
        if (normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " must not contain a line break");
        }
        return normalized;
    }
}
