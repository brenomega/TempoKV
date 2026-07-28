package io.tempokv.protocol.resp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Decodes complete RESP2 frames while retaining incomplete bytes for a later read. */
public final class RespDecoder {
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    /** Adds received bytes and returns every complete frame in wire order. */
    public List<RespFrame> feed(byte[] bytes) throws ProtocolException {
        if (bytes.length > 0) pending.writeBytes(bytes);
        if (pending.size() > MAX_FRAME_BYTES) throw new ProtocolException("ERR protocol frame exceeds 16 MiB");
        byte[] input = pending.toByteArray();
        int offset = 0;
        List<RespFrame> frames = new ArrayList<>();
        while (offset < input.length) {
            ParseResult parsed = parse(input, offset);
            if (parsed == null) break;
            frames.add(parsed.frame());
            offset = parsed.nextOffset();
        }
        pending.reset();
        pending.writeBytes(Arrays.copyOfRange(input, offset, input.length));
        return frames;
    }

    /** Returns whether a partial frame is waiting for additional bytes. */
    public boolean hasPendingBytes() { return pending.size() > 0; }

    private static ParseResult parse(byte[] input, int offset) throws ProtocolException {
        if (offset >= input.length) return null;
        return switch ((char) input[offset]) {
            case '+' -> lineFrame(input, offset, RespFrame.SimpleString::new);
            case '-' -> lineFrame(input, offset, RespFrame.Error::new);
            case ':' -> integerFrame(input, offset);
            case '$' -> bulkFrame(input, offset);
            case '*' -> arrayFrame(input, offset);
            default -> throw new ProtocolException("ERR invalid RESP type byte");
        };
    }

    private static ParseResult lineFrame(byte[] input, int offset, LineFactory factory) throws ProtocolException {
        Line line = line(input, offset + 1);
        return line == null ? null : new ParseResult(factory.create(line.value()), line.nextOffset());
    }

    private static ParseResult integerFrame(byte[] input, int offset) throws ProtocolException {
        Line line = line(input, offset + 1);
        if (line == null) return null;
        try { return new ParseResult(new RespFrame.IntegerValue(Long.parseLong(line.value())), line.nextOffset()); }
        catch (NumberFormatException exception) { throw new ProtocolException("ERR invalid RESP integer"); }
    }

    private static ParseResult bulkFrame(byte[] input, int offset) throws ProtocolException {
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

    private static ParseResult arrayFrame(byte[] input, int offset) throws ProtocolException {
        Line line = line(input, offset + 1);
        if (line == null) return null;
        int count = length(line.value());
        if (count == -1) return new ParseResult(new RespFrame.NullValue(), line.nextOffset());
        if (count < 0) throw new ProtocolException("ERR invalid array length");
        List<RespFrame> values = new ArrayList<>(count);
        int current = line.nextOffset();
        for (int i = 0; i < count; i++) {
            ParseResult item = parse(input, current);
            if (item == null) return null;
            values.add(item.frame()); current = item.nextOffset();
        }
        return new ParseResult(new RespFrame.Array(values), current);
    }

    private static int length(String value) throws ProtocolException {
        try { int parsed = Integer.parseInt(value); if (parsed > MAX_FRAME_BYTES) throw new ProtocolException("ERR protocol frame exceeds 16 MiB"); return parsed; }
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
