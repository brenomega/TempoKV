package io.tempokv.protocol.resp;

import io.tempokv.application.AdminCommand;
import io.tempokv.application.Command;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Maps supported RESP request frames to typed application commands. */
public final class RespCommandMapper {
    /** Maps the E2 PING request and rejects unsupported RESP request shapes. */
    public Command map(RespFrame frame) throws CommandMappingException {
        if (!(frame instanceof RespFrame.Array(List<RespFrame> values)) || values.size() != 1 || !(values.getFirst() instanceof RespFrame.BulkString name)) {
            throw new CommandMappingException("ERR expected an array containing one bulk-string command");
        }
        String command = new String(name.value(), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
        if ("PING".equals(command)) return new AdminCommand(AdminCommand.Kind.PING);
        throw new CommandMappingException("ERR unsupported command " + command);
    }

    /** Reports a syntactically valid but unsupported client request. */
    public static final class CommandMappingException extends Exception { public CommandMappingException(String message) { super(message); } }
}
