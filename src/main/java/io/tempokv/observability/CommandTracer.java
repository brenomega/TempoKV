package io.tempokv.observability;

import io.tempokv.application.Command;
import io.tempokv.application.CommandResult;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Records command names, outcomes, and latency while deliberately excluding arguments and values.
 */
public final class CommandTracer {
    private final MetricsRegistry metrics;

    /** Creates a tracer that publishes sanitized aggregates into the shared registry. */
    public CommandTracer(MetricsRegistry metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Executes one command and records only its normalized name, result class, and elapsed time.
     */
    public CommandResult trace(
            Command command, Supplier<CommandResult> operation) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(operation, "operation");
        String name = command.name().toLowerCase(Locale.ROOT);
        long started = System.nanoTime();
        try {
            CommandResult result = operation.get();
            metrics.incrementCounter("commands.total");
            metrics.incrementCounter("command." + name + ".count");
            if (result instanceof CommandResult.Error) {
                metrics.incrementCounter("command." + name + ".errors");
            }
            return result;
        } catch (RuntimeException failure) {
            metrics.incrementCounter("commands.total");
            metrics.incrementCounter("command." + name + ".errors");
            throw failure;
        } finally {
            metrics.recordLatency(
                    "command." + name + ".latency",
                    Duration.ofNanos(System.nanoTime() - started));
        }
    }
}
