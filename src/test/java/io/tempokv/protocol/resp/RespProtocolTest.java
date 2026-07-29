package io.tempokv.protocol.resp;

import io.tempokv.application.CommandResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the RESP framing boundary used by every client connection. */
class RespProtocolTest {
    /** Rejects huge aggregate requests before allocating attacker-sized frame lists. */
    @Test
    void rejectsOversizedArrayElementCount() {
        RespDecoder decoder = new RespDecoder();

        RespDecoder.ProtocolException failure = assertThrows(
                RespDecoder.ProtocolException.class,
                () -> decoder.feed("*1025\r\n"
                        .getBytes(StandardCharsets.US_ASCII)));

        assertEquals(
                "ERR RESP array exceeds 1024 elements",
                failure.getMessage());
    }

    /** Retains an incomplete frame, then decodes it and a concatenated following frame in order. */
    @Test
    void reconstructsFragmentedAndConcatenatedFrames() throws Exception {
        RespDecoder decoder = new RespDecoder();

        assertTrue(decoder.feed("*1\r\n$4\r\nPI".getBytes(StandardCharsets.US_ASCII)).isEmpty());
        assertTrue(decoder.hasPendingBytes());

        List<RespFrame> frames = decoder.feed("NG\r\n*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals(2, frames.size());
        assertEquals("PING", commandName(frames.getFirst()));
        assertEquals("PING", commandName(frames.get(1)));
    }

    /** Produces canonical RESP bytes for the PING pipeline response. */
    @Test
    void encodesPongAsRespSimpleString() {
        assertArrayEquals("+PONG\r\n".getBytes(StandardCharsets.US_ASCII),
                new RespEncoder().encode(CommandResult.simpleString("PONG")));
    }

    /** Rejects malformed CRLF framing deterministically instead of accepting an ambiguous request. */
    @Test
    void rejectsMalformedRespLineEnding() {
        assertThrows(RespDecoder.ProtocolException.class,
                () -> new RespDecoder().feed("+PING\n".getBytes(StandardCharsets.US_ASCII)));
    }

    /** Releases attacker-controlled buffered bytes after a terminal frame error. */
    @Test
    void clearsPendingBytesAfterProtocolError() {
        RespDecoder decoder = new RespDecoder();

        assertThrows(
                RespDecoder.ProtocolException.class,
                () -> decoder.feed("+PING\n".getBytes(StandardCharsets.US_ASCII)));

        assertFalse(decoder.hasPendingBytes());
    }

    /** Encodes nested collection results used by HISTORY and DIFF canonically. */
    @Test
    void encodesNestedTemporalResults() {
        CommandResult.Array result = new CommandResult.Array(List.of(
                new CommandResult.IntegerValue(2),
                new CommandResult.Array(List.of(
                        CommandResult.simpleString("VALUE"),
                        new CommandResult.BulkString(
                                "data".getBytes(StandardCharsets.US_ASCII))))));

        assertArrayEquals(
                "*2\r\n:2\r\n*2\r\n+VALUE\r\n$4\r\ndata\r\n"
                        .getBytes(StandardCharsets.US_ASCII),
                new RespEncoder().encode(result));
    }

    /** Rejects recursively nested arrays before parser recursion can exhaust the stack. */
    @Test
    void rejectsExcessiveNesting() {
        String frame = "*1\r\n".repeat(130) + "$1\r\nx\r\n";
        assertThrows(
                RespDecoder.ProtocolException.class,
                () -> new RespDecoder().feed(
                        frame.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String commandName(RespFrame frame) {
        RespFrame.Array array = (RespFrame.Array) frame;
        return new String(((RespFrame.BulkString) array.values().getFirst()).value(), StandardCharsets.US_ASCII);
    }
}
