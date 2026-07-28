package io.tempokv.observability;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Maintains the lifecycle health of a node from explicit lifecycle events and subsystem signals.
 */
public final class ServerHealthService {
    private final Clock clock;
    private final AtomicReference<HealthStatus> current;

    /** Creates a service initially marked as starting. */
    public ServerHealthService() {
        this(Clock.systemUTC());
    }

    /** Creates a service with an injectable clock for deterministic callers. */
    public ServerHealthService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.current = new AtomicReference<>(status(ServerHealth.STARTING, Optional.empty()));
    }

    /** Marks bootstrap work as in progress. */
    public void markStarting() {
        update(ServerHealth.STARTING, Optional.empty());
    }

    /** Marks persistent-state recovery as in progress. */
    public void markRecovering() {
        update(ServerHealth.RECOVERING, Optional.empty());
    }

    /** Marks the node ready to accept its enabled endpoints. */
    public void markReady() {
        update(ServerHealth.READY, Optional.empty());
    }

    /** Marks the node degraded and records a bounded diagnostic reason. */
    public void markDegraded(String reason) {
        update(ServerHealth.DEGRADED, Optional.of(reason));
    }

    /** Marks shutdown as in progress. */
    public void markStopping() {
        update(ServerHealth.STOPPING, Optional.empty());
    }

    /** Returns the latest immutable health snapshot. */
    public HealthStatus currentHealth() {
        return current.get();
    }

    /** Evaluates subsystem signals without overriding shutdown in progress. */
    public HealthStatus evaluate(Map<String, Boolean> subsystemSignals) {
        Objects.requireNonNull(subsystemSignals, "subsystemSignals");
        HealthStatus existing = current.get();
        if (existing.state() == ServerHealth.STOPPING) {
            return existing;
        }
        String failedSubsystem = subsystemSignals.entrySet().stream()
                .filter(entry -> !Boolean.TRUE.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .map(name -> name == null || name.isBlank() ? "unnamed" : name.trim())
                .findFirst()
                .orElse(null);
        if (failedSubsystem != null) {
            markDegraded("Subsystem unavailable: " + failedSubsystem);
        } else if (!subsystemSignals.isEmpty()) {
            markReady();
        }
        return currentHealth();
    }

    /** Atomically publishes one new lifecycle state. */
    private void update(ServerHealth state, Optional<String> reason) {
        current.set(status(state, reason));
    }

    /** Creates a timestamped immutable health status. */
    private HealthStatus status(ServerHealth state, Optional<String> reason) {
        return new HealthStatus(state, reason, Instant.now(clock));
    }
}
