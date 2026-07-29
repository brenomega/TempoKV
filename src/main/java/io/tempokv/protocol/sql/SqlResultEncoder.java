package io.tempokv.protocol.sql;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Encodes SQL results as deterministic tab-separated UTF-8 tables terminated by a blank line.
 *
 * <p>Text produced from SQL literals uses a readable escaped form. Arbitrary non-UTF-8 command
 * bytes use a {@code base64:} prefix, preserving binary values without corrupting framing.</p>
 */
public final class SqlResultEncoder {
    /** Encodes a successful table, including its header and response terminator. */
    public byte[] encode(SqlResult result) {
        Objects.requireNonNull(result, "result");
        StringBuilder output = new StringBuilder();
        appendRow(output, result.columns());
        for (List<Object> row : result.rows()) {
            appendRow(output, row.stream().map(SqlResultEncoder::cell).toList());
        }
        output.append('\n');
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Encodes a typed client-safe error with its optional source position. */
    public byte[] encodeError(SqlException exception) {
        Objects.requireNonNull(exception, "exception");
        String position = exception.line() == 0
                ? "-"
                : exception.line() + ":" + exception.column();
        StringBuilder output = new StringBuilder("ERROR\t")
                .append(exception.kind())
                .append('\t')
                .append(position)
                .append('\t')
                .append(escape(exception.getMessage()))
                .append("\n\n");
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendRow(StringBuilder output, List<?> cells) {
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                output.append('\t');
            }
            output.append(cells.get(index));
        }
        output.append('\n');
    }

    private static String cell(Object value) {
        if (value == null) {
            return "\\N";
        }
        if (value instanceof byte[] bytes) {
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            if (java.util.Arrays.equals(
                    decoded.getBytes(StandardCharsets.UTF_8), bytes)) {
                return escape(decoded);
            }
            return "base64:" + Base64.getEncoder().encodeToString(bytes);
        }
        return escape(String.valueOf(value));
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
