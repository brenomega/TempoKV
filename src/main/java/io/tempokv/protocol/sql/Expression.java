package io.tempokv.protocol.sql;

import java.util.Locale;
import java.util.Objects;

/**
 * Defines immutable values and predicates that can appear in the supported SQL abstract syntax tree.
 */
public sealed interface Expression permits Expression.Column, Expression.StringLiteral,
        Expression.LongLiteral, Expression.KeyPredicate, Expression.VersionPredicate,
        Expression.TemporalPoint {

    /** Identifies a projected or filtered column without assigning storage semantics to it. */
    record Column(String name) implements Expression {
        /** Normalizes an SQL identifier for case-insensitive semantic analysis. */
        public Column {
            name = requireText(name, "column").toLowerCase(Locale.ROOT);
        }
    }

    /** Holds a decoded SQL string literal. */
    record StringLiteral(String value) implements Expression {
        /** Rejects a missing literal value. */
        public StringLiteral {
            value = Objects.requireNonNull(value, "value");
        }
    }

    /** Holds an integral SQL literal. */
    record LongLiteral(long value) implements Expression { }

    /** Represents the point-key predicate required by current and historical lookups. */
    record KeyPredicate(Column column, StringLiteral value) implements Expression {
        /** Requires both sides of the equality predicate. */
        public KeyPredicate {
            column = Objects.requireNonNull(column, "column");
            value = Objects.requireNonNull(value, "value");
        }
    }

    /** Represents the optional lower version bound supported by SQL history scans. */
    record VersionPredicate(Column column, LongLiteral minimum) implements Expression {
        /** Requires a column and a non-negative lower bound. */
        public VersionPredicate {
            column = Objects.requireNonNull(column, "column");
            minimum = Objects.requireNonNull(minimum, "minimum");
            if (minimum.value() < 0) {
                throw new IllegalArgumentException("History version bound must not be negative");
            }
        }
    }

    /**
     * Selects a historical point using either a positive commit version or an ISO-8601 timestamp.
     */
    record TemporalPoint(Long version, String timestamp) implements Expression {
        /** Enforces one and only one temporal coordinate. */
        public TemporalPoint {
            if ((version == null) == (timestamp == null)) {
                throw new IllegalArgumentException(
                        "A temporal point requires exactly one of version or timestamp");
            }
            if (version != null && version < 1) {
                throw new IllegalArgumentException("Version must be positive");
            }
            if (timestamp != null) {
                timestamp = requireText(timestamp, "timestamp");
            }
        }

        /** Creates a point selected by commit version. */
        public static TemporalPoint version(long version) {
            return new TemporalPoint(version, null);
        }

        /** Creates a point selected by ISO-8601 timestamp text. */
        public static TemporalPoint timestamp(String timestamp) {
            return new TemporalPoint(null, timestamp);
        }
    }

    private static String requireText(String value, String field) {
        String text = Objects.requireNonNull(value, field);
        if (text.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return text;
    }
}
