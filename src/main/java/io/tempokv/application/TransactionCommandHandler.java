package io.tempokv.application;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.server.Session;
import io.tempokv.transaction.TransactionManager;
import java.util.Objects;

/**
 * Connects protocol-neutral transaction commands to the session transaction manager.
 */
public final class TransactionCommandHandler
        implements CommandHandler<TransactionCommand> {
    private final TransactionManager transactions;
    private final MetricsRegistry metrics;

    /** Creates a handler over the node's single transaction service. */
    public TransactionCommandHandler(
            TransactionManager transactions, MetricsRegistry metrics) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** Returns the transaction-control category served by this handler. */
    @Override public Class<TransactionCommand> commandType() {
        return TransactionCommand.class;
    }

    /** Opens, commits, or rolls back the transaction associated with the supplied session. */
    @Override public CommandResult handle(
            TransactionCommand command, Session session) {
        return switch (command.kind()) {
            case BEGIN -> {
                transactions.begin(session);
                metrics.incrementCounter("commands.begin");
                yield CommandResult.simpleString("OK");
            }
            case COMMIT -> {
                TransactionManager.CommitOutcome outcome =
                        transactions.commit(session);
                metrics.incrementCounter("commands.commit");
                yield outcome.conflicted()
                        ? CommandResult.error(
                                "ERR transaction conflict on keys "
                                        + String.join(",", outcome.conflictingKeys()))
                        : CommandResult.simpleString("OK");
            }
            case ROLLBACK -> {
                transactions.rollback(session);
                metrics.incrementCounter("commands.rollback");
                yield CommandResult.simpleString("OK");
            }
        };
    }
}
