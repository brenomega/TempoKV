package io.tempokv.protocol.resp;

import io.tempokv.application.AdminCommand;
import io.tempokv.application.Command;
import io.tempokv.application.KeyValueCommand;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Maps supported RESP request frames to typed application commands. */
public final class RespCommandMapper {
    /** Maps supported RESP requests to E2 administrative or E3 key-value commands. */
    public Command map(RespFrame frame) throws CommandMappingException {
        if (!(frame instanceof RespFrame.Array(List<RespFrame> values)) || values.isEmpty() || !(values.getFirst() instanceof RespFrame.BulkString name)) {
            throw new CommandMappingException("ERR expected an array beginning with a bulk-string command");
        }
        String command = new String(name.value(), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
        if ("PING".equals(command)) {
            if (values.size() != 1) throw new CommandMappingException("ERR wrong number of arguments for command");
            return new AdminCommand(AdminCommand.Kind.PING);
        }
        return switch (command) {
            case "GET" -> KeyValueCommand.get(key(values, 2));
            case "SET" -> KeyValueCommand.set(key(values, 3), argument(values, 2));
            case "DEL" -> KeyValueCommand.del(key(values, 2));
            case "EXPIRE" -> KeyValueCommand.expire(key(values, 3), seconds(argument(values, 2)));
            case "TTL" -> KeyValueCommand.ttl(key(values, 2));
            default -> throw new CommandMappingException("ERR unsupported command " + command);
        };
    }

    private static String key(List<RespFrame> values, int arity) throws CommandMappingException {
        return new String(argumentForArity(values, arity, 1), StandardCharsets.UTF_8);
    }

    private static byte[] argument(List<RespFrame> values, int index) throws CommandMappingException {
        if (index >= values.size() || !(values.get(index) instanceof RespFrame.BulkString argument)) {
            throw new CommandMappingException("ERR command arguments must be bulk strings");
        }
        return argument.value();
    }

    private static byte[] argumentForArity(List<RespFrame> values, int arity, int index) throws CommandMappingException {
        if (values.size() != arity) throw new CommandMappingException("ERR wrong number of arguments for command");
        return argument(values, index);
    }

    private static long seconds(byte[] value) throws CommandMappingException {
        try {
            long seconds = Long.parseLong(new String(value, StandardCharsets.US_ASCII));
            if (seconds < 0) throw new NumberFormatException();
            return seconds;
        } catch (NumberFormatException exception) {
            throw new CommandMappingException("ERR value is not an integer or out of range");
        }
    }

    /** Reports a syntactically valid but unsupported client request. */
    public static final class CommandMappingException extends Exception { public CommandMappingException(String message) { super(message); } }
}
