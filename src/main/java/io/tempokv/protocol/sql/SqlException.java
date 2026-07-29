package io.tempokv.protocol.sql;

import java.util.Objects;

/**
 * Reports a client-safe SQL compilation or execution error with an optional source position.
 */
public final class SqlException extends IllegalArgumentException {
    /** Identifies the compilation or execution phase that rejected a statement. */
    public enum Kind { LEXICAL, SYNTAX, SEMANTIC, PLANNING, EXECUTION }

    private final Kind kind;
    private final int line;
    private final int column;

    /** Creates a positioned SQL error. Line and column are one-based; zero means unavailable. */
    public SqlException(Kind kind, String message, int line, int column) {
        super(Objects.requireNonNull(message, "message"));
        this.kind = Objects.requireNonNull(kind, "kind");
        if (line < 0 || column < 0) {
            throw new IllegalArgumentException("SQL error position must not be negative");
        }
        this.line = line;
        this.column = column;
    }

    /** Creates an unpositioned SQL error for a post-parse phase. */
    public SqlException(Kind kind, String message) {
        this(kind, message, 0, 0);
    }

    /** Returns the phase that rejected the SQL statement. */
    public Kind kind() {
        return kind;
    }

    /** Returns the one-based source line or zero when unavailable. */
    public int line() {
        return line;
    }

    /** Returns the one-based source column or zero when unavailable. */
    public int column() {
        return column;
    }
}
