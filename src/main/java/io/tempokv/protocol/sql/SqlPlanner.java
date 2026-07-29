package io.tempokv.protocol.sql;

import io.tempokv.application.KeyValueCommand;
import io.tempokv.application.TemporalCommand;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Converts a semantically valid SQL AST into bounded operations over application commands.
 *
 * <p>The planner cannot create a full storage scan and never receives a storage dependency.</p>
 */
public final class SqlPlanner {
    private static final int MAX_HISTORY_SCAN = 1_000;

    /** Produces a logical plan whose terminal operation can be dispatched by {@link PlanExecutor}. */
    public ExecutionPlan plan(Statement statement) {
        return switch (statement) {
            case Statement.Select select -> pointLookup(select);
            case Statement.Upsert upsert -> upsert(upsert);
            case Statement.Delete delete -> new ExecutionPlan.Mutation(
                    KeyValueCommand.del(delete.predicate().value().value()),
                    ExecutionPlan.Mutation.Kind.DELETE);
            case Statement.History history -> history(history);
            case Statement.Diff diff -> new ExecutionPlan.Diff(
                    TemporalCommand.diff(
                            diff.key().value(),
                            selector(diff.first()),
                            selector(diff.second())));
            case Statement.Restore restore -> new ExecutionPlan.Mutation(
                    TemporalCommand.restoreAt(
                            restore.key().value(),
                            restore.version()),
                    ExecutionPlan.Mutation.Kind.RESTORE);
            case Statement.TransactionControl ignored -> throw new SqlException(
                    SqlException.Kind.PLANNING,
                    "transaction control has no E6 execution plan");
        };
    }

    private static ExecutionPlan pointLookup(Statement.Select select) {
        String key = select.predicate().value().value();
        var command = select.asOf() == null
                ? KeyValueCommand.get(key)
                : select.asOf().version() != null
                    ? TemporalCommand.getAtVersion(key, select.asOf().version())
                    : TemporalCommand.getAtTimestamp(
                            key,
                            Instant.parse(select.asOf().timestamp()));
        return new ExecutionPlan.PointLookup(
                command,
                key,
                select.columns().stream().map(Expression.Column::name).toList());
    }

    private static ExecutionPlan upsert(Statement.Upsert upsert) {
        int keyIndex = upsert.columns().indexOf(new Expression.Column("key"));
        int valueIndex = upsert.columns().indexOf(new Expression.Column("value"));
        return new ExecutionPlan.Mutation(
                KeyValueCommand.set(
                        upsert.values().get(keyIndex).value(),
                        upsert.values().get(valueIndex).value()
                                .getBytes(StandardCharsets.UTF_8)),
                ExecutionPlan.Mutation.Kind.UPSERT);
    }

    private static ExecutionPlan history(Statement.History history) {
        Long minimumVersion = history.predicate() == null
                ? null
                : history.predicate().minimum().value();
        return new ExecutionPlan.HistoryLookup(
                TemporalCommand.history(
                        history.key().value(),
                        0,
                        MAX_HISTORY_SCAN),
                history.columns().stream().map(Expression.Column::name).toList(),
                minimumVersion,
                history.order().direction(),
                history.limit(),
                history.offset());
    }

    private static TemporalCommand.Selector selector(Expression.TemporalPoint point) {
        return point.version() != null
                ? TemporalCommand.Selector.version(point.version())
                : TemporalCommand.Selector.timestamp(Instant.parse(point.timestamp()));
    }
}
