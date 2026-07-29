package io.tempokv.protocol.sql;

import io.tempokv.application.Command;
import io.tempokv.application.TemporalCommand;
import io.tempokv.application.AdminCommand;
import io.tempokv.application.TransactionCommand;
import java.util.List;
import java.util.Objects;

/**
 * Defines bounded logical SQL operations whose terminal work is an existing application command.
 */
public sealed interface ExecutionPlan permits ExecutionPlan.PointLookup,
        ExecutionPlan.HistoryLookup, ExecutionPlan.Diff, ExecutionPlan.Mutation,
        ExecutionPlan.Transaction, ExecutionPlan.Admin {

    /** Reads one current or historical key and projects the returned row. */
    record PointLookup(Command command, String key, List<String> columns)
            implements ExecutionPlan {
        /** Requires an application command, key, and immutable projection. */
        public PointLookup {
            command = Objects.requireNonNull(command, "command");
            key = Objects.requireNonNull(key, "key");
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        }
    }

    /** Reads up to the retained-history bound before applying SQL filter, sort, offset, and limit. */
    record HistoryLookup(TemporalCommand command, List<String> columns,
                         Long minimumVersion, Statement.Order.Direction direction,
                         int limit, int offset) implements ExecutionPlan {
        /** Requires a HISTORY command and immutable relational operators. */
        public HistoryLookup {
            command = Objects.requireNonNull(command, "command");
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
            direction = Objects.requireNonNull(direction, "direction");
        }
    }

    /** Executes the shared temporal DIFF command. */
    record Diff(TemporalCommand command) implements ExecutionPlan {
        /** Requires a DIFF command. */
        public Diff {
            command = Objects.requireNonNull(command, "command");
        }
    }

    /** Executes a shared SET, DEL, or RESTOREAT command and shapes its mutation result. */
    record Mutation(Command command, Kind kind) implements ExecutionPlan {
        /** Identifies the mutation result shape exposed to SQL. */
        public enum Kind { UPSERT, DELETE, RESTORE }

        /** Requires a command and mutation kind. */
        public Mutation {
            command = Objects.requireNonNull(command, "command");
            kind = Objects.requireNonNull(kind, "kind");
        }
    }

    /** Executes one shared transaction lifecycle command. */
    record Transaction(TransactionCommand command) implements ExecutionPlan {
        /** Requires a transaction command. */
        public Transaction {
            command = Objects.requireNonNull(command, "command");
        }
    }

    /** Executes one shared administrative command and returns a tabular result. */
    record Admin(AdminCommand command) implements ExecutionPlan {
        /** Requires an administrative command. */
        public Admin {
            command = Objects.requireNonNull(command, "command");
        }
    }
}
