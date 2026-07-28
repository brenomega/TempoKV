package io.tempokv.protocol.resp;

import io.tempokv.application.CommandResult;
import java.nio.charset.StandardCharsets;

/** Encodes protocol-neutral command results as RESP2 response bytes. */
public final class RespEncoder {
    /** Encodes one result using the RESP2 representation expected by Redis clients. */
    public byte[] encode(CommandResult result) {
        return switch (result) {
            case CommandResult.SimpleString value -> line('+', value.value());
            case CommandResult.Error value -> line('-', value.message());
            case CommandResult.IntegerValue value -> line(':', Long.toString(value.value()));
            case CommandResult.BulkString value -> bulk(value.value());
            case CommandResult.NullValue ignored -> "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
            case CommandResult.Array value -> array(value.values());
        };
    }

    private static byte[] line(char type, String value) { return (type + value + "\r\n").getBytes(StandardCharsets.UTF_8); }
    private static byte[] bulk(byte[] value) {
        byte[] prefix = ("$" + value.length + "\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = new byte[prefix.length + value.length + 2];
        System.arraycopy(prefix, 0, encoded, 0, prefix.length); System.arraycopy(value, 0, encoded, prefix.length, value.length);
        encoded[encoded.length - 2] = '\r'; encoded[encoded.length - 1] = '\n'; return encoded;
    }
    private static byte[] array(java.util.List<CommandResult> values) {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        output.writeBytes(("*" + values.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
        RespEncoder encoder = new RespEncoder();
        values.forEach(value -> output.writeBytes(encoder.encode(value)));
        return output.toByteArray();
    }
}
