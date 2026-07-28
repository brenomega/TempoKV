package io.tempokv.protocol.resp;

import io.tempokv.application.AdminCommand;
import io.tempokv.application.Command;
import io.tempokv.application.KeyValueCommand;
import io.tempokv.application.TemporalCommand;
import java.time.Instant;
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
            case "GETAT" -> getAt(values);
            case "HISTORY" -> history(values);
            case "DIFF" -> diff(values);
            case "RESTOREAT" -> TemporalCommand.restoreAt(key(values, 3), version(argument(values, 2)));
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

    /** Maps GETAT key VERSION version or GETAT key TIMESTAMP ISO-8601-instant. */
    private static TemporalCommand getAt(List<RespFrame> values) throws CommandMappingException {
        if (values.size() != 4) throw new CommandMappingException("ERR wrong number of arguments for command");
        String type = new String(argument(values, 2), StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
        return switch (type) {
            case "VERSION" -> TemporalCommand.getAtVersion(key(values, 4), version(argument(values, 3)));
            case "TIMESTAMP" -> TemporalCommand.getAtTimestamp(key(values, 4), timestamp(argument(values, 3)));
            default -> throw new CommandMappingException("ERR GETAT selector must be VERSION or TIMESTAMP");
        };
    }

    /** Maps HISTORY key with optional offset and bounded limit. */
    private static TemporalCommand history(List<RespFrame> values) throws CommandMappingException {
        if (values.size() < 2 || values.size() > 4) throw new CommandMappingException("ERR wrong number of arguments for command");
        String key = key(values, values.size());
        int offset = values.size() >= 3 ? pageNumber(argument(values, 2), "offset") : 0;
        int limit = values.size() == 4 ? pageNumber(argument(values, 3), "limit") : 100;
        return TemporalCommand.history(key, offset, limit);
    }

    /** Maps DIFF key version-one version-two. */
    private static TemporalCommand diff(List<RespFrame> values) throws CommandMappingException {
        if (values.size() != 4) throw new CommandMappingException("ERR wrong number of arguments for command");
        return TemporalCommand.diff(key(values, 4), TemporalCommand.Selector.version(version(argument(values, 2))), TemporalCommand.Selector.version(version(argument(values, 3))));
    }

    private static long version(byte[] value) throws CommandMappingException {
        long result = seconds(value);
        if (result < 1) throw new CommandMappingException("ERR version must be positive");
        return result;
    }

    private static Instant timestamp(byte[] value) throws CommandMappingException {
        try { return Instant.parse(new String(value, StandardCharsets.US_ASCII)); }
        catch (RuntimeException exception) { throw new CommandMappingException("ERR timestamp must be ISO-8601 UTC"); }
    }

    private static int pageNumber(byte[] value, String name) throws CommandMappingException {
        try { return Math.toIntExact(seconds(value)); }
        catch (ArithmeticException exception) { throw new CommandMappingException("ERR " + name + " is out of range"); }
    }

    /** Reports a syntactically valid but unsupported client request. */
    public static final class CommandMappingException extends Exception { public CommandMappingException(String message) { super(message); } }
}
