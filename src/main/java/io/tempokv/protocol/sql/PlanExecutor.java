package io.tempokv.protocol.sql;

import io.tempokv.application.Command;
import io.tempokv.application.CommandDispatcher;
import io.tempokv.application.CommandResult;
import io.tempokv.security.AccessController;
import io.tempokv.server.Session;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes SQL plans exclusively through the shared command dispatcher and application handlers.
 */
public final class PlanExecutor {
    private final CommandDispatcher dispatcher;
    private final AccessController accessController;

    /** Creates an executor over the composed application pipeline and authorization policy. */
    public PlanExecutor(
            CommandDispatcher dispatcher,
            AccessController accessController) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.accessController =
                Objects.requireNonNull(accessController, "accessController");
    }

    /** Dispatches the plan's terminal command and applies its bounded relational operators. */
    public SqlResult execute(ExecutionPlan plan, Session session) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(session, "session");
        return switch (plan) {
            case ExecutionPlan.PointLookup lookup -> pointLookup(
                    lookup,
                    dispatch(lookup.command(), session));
            case ExecutionPlan.HistoryLookup history -> history(
                    history,
                    dispatch(history.command(), session));
            case ExecutionPlan.Diff diff -> diff(
                    dispatch(diff.command(), session));
            case ExecutionPlan.Mutation mutation -> mutation(
                    mutation,
                    dispatch(mutation.command(), session));
            case ExecutionPlan.Transaction transaction -> status(
                    dispatch(transaction.command(), session),
                    "transaction");
            case ExecutionPlan.Admin admin -> admin(
                    admin,
                    dispatch(admin.command(), session));
        };
    }

    private CommandResult dispatch(Command command, Session session) {
        if (!accessController.isAllowed(session, command)) {
            throw execution(accessController.denialMessage(session, command));
        }
        CommandResult result = dispatcher.dispatch(command, session);
        if (result instanceof CommandResult.Error error) {
            throw execution(error.message());
        }
        return result;
    }

    private static SqlResult pointLookup(
            ExecutionPlan.PointLookup plan,
            CommandResult result) {
        if (result instanceof CommandResult.NullValue) {
            return SqlResult.empty(plan.columns());
        }
        byte[] value = requireBulk(result, "point lookup").value();
        List<Object> row = new ArrayList<>();
        for (String column : plan.columns()) {
            row.add(switch (column) {
                case "key" -> plan.key();
                case "value" -> value;
                default -> throw execution(
                        "unsupported point projection " + column);
            });
        }
        return new SqlResult(plan.columns(), List.of(row));
    }

    private static SqlResult history(
            ExecutionPlan.HistoryLookup plan,
            CommandResult result) {
        CommandResult.Array array = requireArray(result, "history");
        List<HistoryRow> rows = array.values().stream()
                .map(PlanExecutor::historyRow)
                .filter(row -> plan.minimumVersion() == null
                        || row.version() >= plan.minimumVersion())
                .sorted(plan.direction() == Statement.Order.Direction.ASC
                        ? Comparator.comparingLong(HistoryRow::version)
                        : Comparator.comparingLong(HistoryRow::version).reversed())
                .skip(plan.offset())
                .limit(plan.limit())
                .toList();
        List<List<Object>> projected = rows.stream()
                .map(row -> projectHistory(plan.columns(), row))
                .toList();
        return new SqlResult(plan.columns(), projected);
    }

    private static SqlResult diff(CommandResult result) {
        CommandResult.Array values = requireArray(result, "diff");
        if (values.values().size() != 5) {
            throw execution("invalid DIFF result shape");
        }
        return SqlResult.row(
                List.of(
                        "before_state",
                        "after_state",
                        "common_prefix",
                        "before_suffix",
                        "after_suffix"),
                requireSimple(values.values().get(0), "diff before state").value(),
                requireSimple(values.values().get(1), "diff after state").value(),
                requireInteger(values.values().get(2), "diff common prefix").value(),
                optionalBulk(values.values().get(3), "diff before suffix"),
                optionalBulk(values.values().get(4), "diff after suffix"));
    }

    private static SqlResult mutation(
            ExecutionPlan.Mutation plan,
            CommandResult result) {
        return switch (plan.kind()) {
            case UPSERT -> SqlResult.row(
                    List.of("status"),
                    requireSimple(result, "UPSERT").value());
            case DELETE -> SqlResult.row(
                    List.of("affected"),
                    requireInteger(result, "DELETE").value());
            case RESTORE -> SqlResult.row(
                    result instanceof CommandResult.SimpleString queued
                            ? List.of("status")
                            : List.of("version"),
                    result instanceof CommandResult.SimpleString queued
                            ? queued.value()
                            : requireInteger(result, "RESTORE").value());
        };
    }

    private static SqlResult status(
            CommandResult result, String operation) {
        return SqlResult.row(
                List.of("status"),
                requireSimple(result, operation).value());
    }

    private static SqlResult admin(
            ExecutionPlan.Admin plan, CommandResult result) {
        if (plan.command().kind()
                == io.tempokv.application.AdminCommand.Kind.PING) {
            return status(result, "PING");
        }
        CommandResult.Array rows = requireArray(result, plan.command().name());
        List<List<Object>> values = rows.values().stream()
                .map(entry -> {
                    CommandResult.Array pair = requireArray(
                            entry, plan.command().name() + " entry");
                    if (pair.values().size() != 2) {
                        throw execution("invalid administrative result shape");
                    }
                    return List.<Object>of(
                            requireSimple(pair.values().get(0), "admin key").value(),
                            requireSimple(pair.values().get(1), "admin value").value());
                })
                .toList();
        return new SqlResult(List.of("name", "value"), values);
    }

    private static HistoryRow historyRow(CommandResult result) {
        CommandResult.Array entry = requireArray(result, "history entry");
        if (entry.values().size() != 3) {
            throw execution("invalid HISTORY entry shape");
        }
        long version = requireInteger(
                entry.values().get(0), "history version").value();
        long committedAt = requireInteger(
                entry.values().get(1), "history timestamp").value();
        CommandResult state = entry.values().get(2);
        if (state instanceof CommandResult.BulkString value) {
            return new HistoryRow(version, committedAt, "VALUE", value.value());
        }
        String marker = requireSimple(state, "history state").value();
        return new HistoryRow(version, committedAt, marker, null);
    }

    private static List<Object> projectHistory(
            List<String> columns,
            HistoryRow row) {
        Map<String, Object> values = new HashMap<>();
        values.put("version", row.version());
        values.put("committed_at", row.committedAt());
        values.put("state", row.state());
        values.put("value", row.value());
        return columns.stream().map(values::get).toList();
    }

    private static CommandResult.Array requireArray(
            CommandResult result,
            String operation) {
        if (result instanceof CommandResult.Array array) {
            return array;
        }
        throw execution("invalid " + operation + " result shape");
    }

    private static CommandResult.BulkString requireBulk(
            CommandResult result,
            String operation) {
        if (result instanceof CommandResult.BulkString value) {
            return value;
        }
        throw execution("invalid " + operation + " result shape");
    }

    private static CommandResult.SimpleString requireSimple(
            CommandResult result,
            String operation) {
        if (result instanceof CommandResult.SimpleString value) {
            return value;
        }
        throw execution("invalid " + operation + " result shape");
    }

    private static CommandResult.IntegerValue requireInteger(
            CommandResult result,
            String operation) {
        if (result instanceof CommandResult.IntegerValue value) {
            return value;
        }
        throw execution("invalid " + operation + " result shape");
    }

    private static byte[] optionalBulk(
            CommandResult result,
            String operation) {
        if (result instanceof CommandResult.NullValue) {
            return null;
        }
        return requireBulk(result, operation).value();
    }

    private static SqlException execution(String message) {
        String safeMessage = message.startsWith("ERR ")
                ? message.substring(4)
                : message;
        return new SqlException(SqlException.Kind.EXECUTION, safeMessage);
    }

    /** Captures the normalized cells available to SQL history projection. */
    private record HistoryRow(
            long version,
            long committedAt,
            String state,
            byte[] value) {
        private HistoryRow {
            state = Objects.requireNonNull(state, "state");
            value = value == null
                    ? null
                    : java.util.Arrays.copyOf(value, value.length);
        }

        @Override
        public byte[] value() {
            return value == null
                    ? null
                    : java.util.Arrays.copyOf(value, value.length);
        }
    }
}
