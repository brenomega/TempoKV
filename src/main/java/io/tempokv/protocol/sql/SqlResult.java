package io.tempokv.protocol.sql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Holds a protocol-neutral tabular SQL result with immutable columns, rows, and binary-safe cells.
 */
public record SqlResult(List<String> columns, List<List<Object>> rows) {
    /** Defensively copies the complete result, including byte-array cells. */
    public SqlResult {
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        List<List<Object>> copiedRows = new ArrayList<>();
        for (List<Object> row : Objects.requireNonNull(rows, "rows")) {
            if (row.size() != columns.size()) {
                throw new IllegalArgumentException("SQL row width does not match its columns");
            }
            copiedRows.add(row.stream().map(SqlResult::copyCell).toList());
        }
        rows = List.copyOf(copiedRows);
    }

    /** Returns defensive copies of rows that contain mutable binary cells. */
    @Override
    public List<List<Object>> rows() {
        return rows.stream()
                .map(row -> row.stream().map(SqlResult::copyCell).toList())
                .toList();
    }

    /** Creates a single-row result. */
    public static SqlResult row(List<String> columns, Object... values) {
        return new SqlResult(columns, List.of(Arrays.asList(values)));
    }

    /** Creates an empty result that still exposes its columns. */
    public static SqlResult empty(List<String> columns) {
        return new SqlResult(columns, List.of());
    }

    private static Object copyCell(Object value) {
        if (value == null || value instanceof String || value instanceof Long) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return Arrays.copyOf(bytes, bytes.length);
        }
        throw new IllegalArgumentException(
                "Unsupported SQL result cell " + value.getClass().getSimpleName());
    }
}
