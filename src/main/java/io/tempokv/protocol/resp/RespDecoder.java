package io.tempokv.protocol.resp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Decodes complete RESP2 frames while retaining incomplete bytes for a later read. */
public final class RespDecoder {
    private static final int MAX_NESTING_DEPTH = 128;
    private final int maxFrameBytes;
    private final int maxArrayElements;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    /** Creates a decoder with the established conservative defaults. */
    public RespDecoder() {
        this(16 * 1_048_576, 1_024);
    }

    /** Creates a decoder with explicit command and array limits. */
    public RespDecoder(int maxFrameBytes, int maxArrayElements) {
        if (maxFrameBytes < 1 || maxArrayElements < 1) {
            throw new IllegalArgumentException("RESP limits must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.maxArrayElements = maxArrayElements;
    }

    /** Adds received bytes and returns every complete frame in wire order. */
    public List<RespFrame> feed(byte[] bytes) throws ProtocolException {
        try {
            if (bytes.length > 0) pending.writeBytes(bytes);
            if (pending.size() > maxFrameBytes) {
                throw new ProtocolException(
                        "ERR protocol frame exceeds configured limit");
            }
            byte[] input = pending.toByteArray();
            int offset = 0;
            List<RespFrame> frames = new ArrayList<>();
            while (offset < input.length) {
                ParseResult parsed = parse(input, offset, 0);
                if (parsed == null) break;
                frames.add(parsed.frame());
                offset = parsed.nextOffset();
            }
            pending.reset();
            pending.writeBytes(Arrays.copyOfRange(input, offset, input.length));
            return frames;
        } catch (ProtocolException failure) {
            pending.reset();
            throw failure;
        }
    }

    /** Returns whether a partial frame is waiting for additional bytes. */
    public boolean hasPendingBytes() { return pending.size() > 0; }

    private ParseResult parse(byte[] input, int offset, int depth) throws ProtocolException {
        if (offset >= input.length) return null;
        if (depth > MAX_NESTING_DEPTH) {
            throw new ProtocolException("ERR RESP nesting exceeds 128 levels");
        }
        return switch ((char) input[offset]) {
            case '+' -> lineFrame(input, offset, RespFrame.SimpleString::new);
            case '-' -> lineFrame(input, offset, RespFrame.Error::new);
            case ':' -> integerFrame(input, offset);
            case '$' -> bulkFrame(input, offset);
            case '*' -> arrayFrame(input, offset, depth);
            default -> throw new ProtocolException("ERR invalid RESP type byte");
        };
    }

    private ParseResult lineFrame(byte[] input, int offset, LineFactory factory) throws ProtocolException {
        Line line = line(input, offset + 1);
        return line == null ? null : new ParseResult(factory.create(line.value()), line.nextOffset());
    }

    private ParseResult integerFrame(byte[] input, int offset) throws ProtocolException {
        Line line = line(input, offset + 1);
        if (line == null) return null;
        try { return new ParseResult(new RespFrame.IntegerValue(Long.parseLong(line.value())), line.nextOffset()); }
        catch (NumberFormatException exception) { throw new ProtocolException("ERR invalid RESP integer"); }
    }

    private ParseResult bulkFrame(byte[] input, int offset) throws ProtocolException {
        Line line = line(input, offset + 1);
        if (line == null) return null;
        int length = length(line.value());
        if (length == -1) return new ParseResult(new RespFrame.NullValue(), line.nextOffset());
        if (length < 0) throw new ProtocolException("ERR invalid bulk string length");
        long end = (long) line.nextOffset() + length + 2;
        if (end > input.length) return null;
        if (input[line.nextOffset() + length] != '\r' || input[line.nextOffset() + length + 1] != '\n') {
            throw new ProtocolException("ERR bulk string is missing CRLF");
        }
        return new ParseResult(new RespFrame.BulkString(Arrays.copyOfRange(input, line.nextOffset(), line.nextOffset() + length)), (int) end);
    }

    private ParseResult arrayFrame(byte[] input, int offset, int depth) throws ProtocolException {
        Line line = line(input, offset + 1);
        if (line == null) return null;
        int count = length(line.value());
        if (count == -1) return new ParseResult(new RespFrame.NullValue(), line.nextOffset());
        if (count < 0) throw new ProtocolException("ERR invalid array length");
        if (count > maxArrayElements) {
            throw new ProtocolException(
                    "ERR RESP array exceeds configured limit");
        }
        List<RespFrame> values = new ArrayList<>(count);
        int current = line.nextOffset();
        for (int i = 0; i < count; i++) {
            ParseResult item = parse(input, current, depth + 1);
            if (item == null) return null;
            values.add(item.frame()); current = item.nextOffset();
        }
        return new ParseResult(new RespFrame.Array(values), current);
    }

    private int length(String value) throws ProtocolException {
        try { int parsed = Integer.parseInt(value); if (parsed > maxFrameBytes) throw new ProtocolException("ERR protocol frame exceeds configured limit"); return parsed; }
        catch (NumberFormatException exception) { throw new ProtocolException("ERR invalid RESP length"); }
    }

    private static Line line(byte[] input, int start) throws ProtocolException {
        for (int i = start; i < input.length; i++) {
            if (i + 1 < input.length && input[i] == '\r' && input[i + 1] == '\n') {
                return new Line(new String(input, start, i - start, StandardCharsets.US_ASCII), i + 2);
            }
            if (input[i] == '\n') throw new ProtocolException("ERR RESP line must end with CRLF");
        }
        return null;
    }

    private record ParseResult(RespFrame frame, int nextOffset) { }
    private record Line(String value, int nextOffset) { }
    @FunctionalInterface private interface LineFactory { RespFrame create(String value); }

    /** Reports malformed RESP bytes without exposing implementation details. */
    public static final class ProtocolException extends Exception { public ProtocolException(String message) { super(message); } }
}
