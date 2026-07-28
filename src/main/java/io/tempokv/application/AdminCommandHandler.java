package io.tempokv.application;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.server.Session;
import java.util.Objects;

/** Executes administrative commands that are available before storage is introduced. */
public final class AdminCommandHandler implements CommandHandler<AdminCommand> {
    private final MetricsRegistry metrics;

    /** Creates the handler that records administrative command metrics. */
    public AdminCommandHandler(MetricsRegistry metrics) { this.metrics = Objects.requireNonNull(metrics, "metrics"); }

    /** Returns the command family handled by this instance. */
    @Override public Class<AdminCommand> commandType() { return AdminCommand.class; }

    /** Executes PING and records the completed operation. */
    @Override public CommandResult handle(AdminCommand command, Session session) {
        metrics.incrementCounter("commands.ping");
        return CommandResult.simpleString("PONG");
    }
}
