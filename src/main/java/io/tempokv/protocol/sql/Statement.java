package io.tempokv.protocol.sql;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Defines the immutable SQL statements accepted by the E6 parser.
 *
 * <p>The AST records syntax only. Storage-specific restrictions are applied later by
 * {@link SqlSemanticAnalyzer}, keeping parsing errors distinct from semantic errors.</p>
 */
public sealed interface Statement permits Statement.Select, Statement.Upsert, Statement.Delete,
        Statement.History, Statement.Diff, Statement.Restore, Statement.TransactionControl {

    /** Reads one key at the current head or at an optional historical point. */
    record Select(List<Expression.Column> columns, String table,
                  Expression.KeyPredicate predicate, Expression.TemporalPoint asOf)
            implements Statement {
        /** Copies projection state and normalizes the table identifier. */
        public Select {
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
            table = normalizeIdentifier(table, "table");
        }
    }

    /** Writes a UTF-8 SQL literal through the normal key-value commit command. */
    record Upsert(String table, List<Expression.Column> columns,
                  List<Expression.StringLiteral> values) implements Statement {
        /** Copies the column and value lists and normalizes the table identifier. */
        public Upsert {
            table = normalizeIdentifier(table, "table");
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    /** Deletes the current value selected by a point-key predicate. */
    record Delete(String table, Expression.KeyPredicate predicate) implements Statement {
        /** Normalizes the target table. */
        public Delete {
            table = normalizeIdentifier(table, "table");
        }
    }

    /**
     * Reads retained versions with bounded projection, filtering, ordering, and pagination.
     */
    record History(List<Expression.Column> columns, Expression.StringLiteral key,
                   Expression.VersionPredicate predicate, Order order, int limit, int offset)
            implements Statement {
        /** Copies projections and enforces bounded pagination. */
        public History {
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
            key = Objects.requireNonNull(key, "key");
            order = Objects.requireNonNull(order, "order");
            if (limit < 1 || limit > 1_000 || offset < 0) {
                throw new IllegalArgumentException("Invalid SQL history offset or limit");
            }
        }
    }

    /** Compares two retained points for the same key. */
    record Diff(Expression.StringLiteral key, Expression.TemporalPoint first,
                Expression.TemporalPoint second) implements Statement {
        /** Requires the key and both historical coordinates. */
        public Diff {
            key = Objects.requireNonNull(key, "key");
            first = Objects.requireNonNull(first, "first");
            second = Objects.requireNonNull(second, "second");
        }
    }

    /** Restores a retained version as a new append-only head commit. */
    record Restore(Expression.StringLiteral key, long version) implements Statement {
        /** Requires a key and a positive source version. */
        public Restore {
            key = Objects.requireNonNull(key, "key");
            if (version < 1) {
                throw new IllegalArgumentException("Restore version must be positive");
            }
        }
    }

    /** Represents transaction-control syntax reserved for the E7 transaction manager. */
    record TransactionControl(Kind kind) implements Statement {
        /** Lists the transaction-control keywords recognized by the grammar. */
        public enum Kind { BEGIN, COMMIT, ROLLBACK }

        /** Requires a transaction-control kind. */
        public TransactionControl {
            kind = Objects.requireNonNull(kind, "kind");
        }
    }

    /** Describes the only ordering operation supported for retained history. */
    record Order(Expression.Column column, Direction direction) {
        /** Lists supported sort directions. */
        public enum Direction { ASC, DESC }

        /** Requires a sort column and direction. */
        public Order {
            column = Objects.requireNonNull(column, "column");
            direction = Objects.requireNonNull(direction, "direction");
        }

        /** Returns the default newest-first version order. */
        public static Order newestFirst() {
            return new Order(
                    new Expression.Column("version"),
                    Direction.DESC);
        }
    }

    private static String normalizeIdentifier(String value, String field) {
        String identifier = Objects.requireNonNull(value, field);
        if (identifier.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return identifier.toLowerCase(Locale.ROOT);
    }
}
