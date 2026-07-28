package io.tempokv.protocol.resp;

import io.tempokv.application.CommandResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the RESP framing boundary used by every client connection. */
class RespProtocolTest {
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

    private static String commandName(RespFrame frame) {
        RespFrame.Array array = (RespFrame.Array) frame;
        return new String(((RespFrame.BulkString) array.values().getFirst()).value(), StandardCharsets.US_ASCII);
    }
}
