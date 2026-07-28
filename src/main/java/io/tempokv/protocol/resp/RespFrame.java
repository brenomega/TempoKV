package io.tempokv.protocol.resp;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Models the RESP2 data types independently from their command meaning. */
public sealed interface RespFrame permits RespFrame.SimpleString, RespFrame.Error, RespFrame.IntegerValue,
        RespFrame.BulkString, RespFrame.NullValue, RespFrame.Array {
    /** A RESP simple string. */
    record SimpleString(String value) implements RespFrame { public SimpleString { value = Objects.requireNonNull(value, "value"); } }
    /** A RESP error. */
    record Error(String message) implements RespFrame { public Error { message = Objects.requireNonNull(message, "message"); } }
    /** A RESP integer. */
    record IntegerValue(long value) implements RespFrame { }
    /** A binary-safe RESP bulk string. */
    record BulkString(byte[] value) implements RespFrame {
        public BulkString { value = Arrays.copyOf(Objects.requireNonNull(value, "value"), value.length); }
        @Override public byte[] value() { return Arrays.copyOf(value, value.length); }
    }
    /** A RESP null bulk string or null array. */
    record NullValue() implements RespFrame { }
    /** A RESP array. */
    record Array(List<RespFrame> values) implements RespFrame {
        public Array { values = List.copyOf(Objects.requireNonNull(values, "values")); }
    }
}
