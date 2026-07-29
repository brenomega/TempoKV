package io.tempokv.application;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.observability.MetricsSnapshot;
import io.tempokv.observability.ServerHealthService;
import io.tempokv.server.Session;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Executes operational commands without reading user key-value contents. */
public final class AdminCommandHandler implements CommandHandler<AdminCommand> {
    private final MetricsRegistry metrics;
    private final ServerHealthService health;
    private final String role;
    private final LongSupplier currentVersion;
    private final Supplier<Map<String, String>> operationalInfo;

    /** Creates the handler that records administrative command metrics. */
    public AdminCommandHandler(MetricsRegistry metrics) {
        this(metrics, new ServerHealthService(), "PRIMARY", () -> 0L, Map::of);
    }

    /** Creates the operational handler over live node health, role, version, and metrics. */
    public AdminCommandHandler(
            MetricsRegistry metrics,
            ServerHealthService health,
            String role,
            LongSupplier currentVersion) {
        this(metrics, health, role, currentVersion, Map::of);
    }

    /**
     * Creates the operational handler with additional live subsystem diagnostics.
     */
    public AdminCommandHandler(
            MetricsRegistry metrics,
            ServerHealthService health,
            String role,
            LongSupplier currentVersion,
            Supplier<Map<String, String>> operationalInfo) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.health = Objects.requireNonNull(health, "health");
        this.role = Objects.requireNonNull(role, "role");
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.operationalInfo = Objects.requireNonNull(operationalInfo, "operationalInfo");
    }

    /** Returns the command family handled by this instance. */
    @Override public Class<AdminCommand> commandType() { return AdminCommand.class; }

    /** Executes PING, HEALTH, or INFO without consulting user key-value contents. */
    @Override public CommandResult handle(AdminCommand command, Session session) {
        return switch (command.kind()) {
            case PING -> {
                metrics.incrementCounter("commands.ping");
                yield CommandResult.simpleString("PONG");
            }
            case HEALTH -> health();
            case INFO -> info();
        };
    }

    private CommandResult health() {
        var status = health.currentHealth();
        metrics.incrementCounter("commands.health");
        return pairs(List.of(
                pair("status", status.state().name()),
                pair("code", health.operationalCode()),
                pair("reason", status.reason().orElse("")),
                pair("updated_at", status.updatedAt().toString())));
    }

    private CommandResult info() {
        MetricsSnapshot snapshot = metrics.snapshot();
        TreeMap<String, String> values = new TreeMap<>();
        values.put("server.role", role);
        values.put("storage.version", Long.toString(currentVersion.getAsLong()));
        values.putAll(operationalInfo.get());
        values.put("clients.active", Long.toString(
                snapshot.gauges().getOrDefault("resp.connections_active", 0L)
                        + snapshot.gauges().getOrDefault(
                                "sql.connections_active", 0L)));
        values.put("wal.bytes", Long.toString(
                snapshot.gauges().getOrDefault("wal.bytes", 0L)));
        values.put("snapshots.active", Long.toString(
                snapshot.gauges().getOrDefault("snapshots.active", 0L)));
        values.put("transactions.conflicts", Long.toString(
                snapshot.counters().getOrDefault(
                        "transactions.conflicts", 0L)));
        snapshot.counters().forEach((name, value) ->
                values.put(name, Long.toString(value)));
        snapshot.gauges().forEach((name, value) ->
                values.put(name, Long.toString(value)));
        snapshot.latencies().forEach((name, value) -> {
            values.put(name + ".p50_nanos", Long.toString(value.p50Nanos()));
            values.put(name + ".p95_nanos", Long.toString(value.p95Nanos()));
            values.put(name + ".p99_nanos", Long.toString(value.p99Nanos()));
        });
        values.putIfAbsent("commands.latency.p50_nanos", "0");
        values.putIfAbsent("commands.latency.p95_nanos", "0");
        values.putIfAbsent("commands.latency.p99_nanos", "0");
        metrics.incrementCounter("commands.info");
        return pairs(values.entrySet().stream()
                .map(entry -> pair(entry.getKey(), entry.getValue()))
                .toList());
    }

    private static CommandResult pairs(List<List<String>> pairs) {
        return new CommandResult.Array(pairs.stream()
                .map(pair -> (CommandResult) new CommandResult.Array(List.of(
                        CommandResult.simpleString(pair.get(0)),
                        CommandResult.simpleString(pair.get(1)))))
                .toList());
    }

    private static List<String> pair(String name, String value) {
        return List.of(name, value);
    }
}
