package io.tempokv.protocol.sql;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * Validates the names, shapes, types, and temporal clauses supported by TempoKV SQL.
 *
 * <p>This phase deliberately performs no reads or writes. It also expands {@code *} into an
 * explicit projection so the planner receives a fully defined statement.</p>
 */
public final class SqlSemanticAnalyzer {
    private static final String TABLE = "tempokv";
    private static final Set<String> POINT_COLUMNS = Set.of("key", "value");
    private static final List<String> POINT_STAR = List.of("key", "value");
    private static final Set<String> HISTORY_COLUMNS =
            Set.of("version", "committed_at", "state", "value");
    private static final List<String> HISTORY_STAR =
            List.of("version", "committed_at", "state", "value");

    /** Validates and normalizes a parsed statement for logical planning. */
    public Statement analyze(Statement statement) {
        return switch (statement) {
            case Statement.Select select -> analyzeSelect(select);
            case Statement.Upsert upsert -> analyzeUpsert(upsert);
            case Statement.Delete delete -> analyzeDelete(delete);
            case Statement.History history -> analyzeHistory(history);
            case Statement.Diff diff -> {
                validatePoint(diff.first());
                validatePoint(diff.second());
                yield diff;
            }
            case Statement.Restore restore -> restore;
            case Statement.TransactionControl control -> control;
            case Statement.Admin admin -> admin;
        };
    }

    private static Statement.Select analyzeSelect(Statement.Select select) {
        requireTable(select.table());
        Expression.KeyPredicate predicate = requireKeyPredicate(select.predicate());
        if (select.asOf() != null) {
            validatePoint(select.asOf());
        }
        return new Statement.Select(
                projection(select.columns(), POINT_COLUMNS, POINT_STAR),
                select.table(),
                predicate,
                select.asOf());
    }

    private static Statement.Upsert analyzeUpsert(Statement.Upsert upsert) {
        requireTable(upsert.table());
        if (upsert.columns().size() != 2 || upsert.values().size() != 2) {
            throw semantic("UPSERT requires exactly key and value");
        }
        Set<String> columns = upsert.columns().stream()
                .map(Expression.Column::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!columns.equals(Set.of("key", "value"))) {
            throw semantic("UPSERT columns must be key and value");
        }
        if (upsert.values().get(upsert.columns().indexOf(new Expression.Column("key")))
                .value().isEmpty()) {
            throw semantic("key must not be empty");
        }
        return upsert;
    }

    private static Statement.Delete analyzeDelete(Statement.Delete delete) {
        requireTable(delete.table());
        return new Statement.Delete(
                delete.table(),
                requireKeyPredicate(delete.predicate()));
    }

    private static Statement.History analyzeHistory(Statement.History history) {
        List<Expression.Column> columns = projection(
                history.columns(), HISTORY_COLUMNS, HISTORY_STAR);
        if (history.predicate() != null
                && !history.predicate().column().name().equals("version")) {
            throw semantic("HISTORY filter supports only version >= integer");
        }
        if (!history.order().column().name().equals("version")) {
            throw semantic("HISTORY ordering supports only version");
        }
        if (history.key().value().isEmpty()) {
            throw semantic("key must not be empty");
        }
        return new Statement.History(
                columns,
                history.key(),
                history.predicate(),
                history.order(),
                history.limit(),
                history.offset());
    }

    private static Expression.KeyPredicate requireKeyPredicate(
            Expression.KeyPredicate predicate) {
        if (predicate == null) {
            throw semantic("point lookup requires WHERE key = '...'");
        }
        if (!predicate.column().name().equals("key")) {
            throw semantic("point lookup predicate must use key");
        }
        if (predicate.value().value().isEmpty()) {
            throw semantic("key must not be empty");
        }
        return predicate;
    }

    private static List<Expression.Column> projection(
            List<Expression.Column> columns,
            Set<String> allowed,
            List<String> starExpansion) {
        if (columns.isEmpty()) {
            throw semantic("projection must not be empty");
        }
        if (columns.size() == 1 && columns.getFirst().name().equals("*")) {
            return starExpansion.stream().map(Expression.Column::new).toList();
        }
        for (Expression.Column column : columns) {
            if (!allowed.contains(column.name())) {
                throw semantic("unsupported projection column " + column.name());
            }
        }
        return columns;
    }

    private static void validatePoint(Expression.TemporalPoint point) {
        if (point.timestamp() == null) {
            return;
        }
        try {
            Instant.parse(point.timestamp());
        } catch (DateTimeParseException exception) {
            throw semantic("AS OF timestamp must be an ISO-8601 instant");
        }
    }

    private static void requireTable(String table) {
        if (!TABLE.equals(table)) {
            throw semantic("unknown table " + table);
        }
    }

    private static SqlException semantic(String message) {
        return new SqlException(SqlException.Kind.SEMANTIC, message);
    }
}
