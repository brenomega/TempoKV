package io.tempokv.observability;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides an immutable snapshot of the node health state and its optional reason.
 */
public record HealthStatus(ServerHealth state, Optional<String> reason, Instant updatedAt) {
    /** Validates the immutable health snapshot fields. */
    public HealthStatus {
        Objects.requireNonNull(state, "state");
        reason = Objects.requireNonNull(reason, "reason").map(HealthStatus::sanitizeReason);
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** Trims and bounds a diagnostic message before it becomes observable state. */
    private static String sanitizeReason(String value) {
        String normalized = Objects.requireNonNull(value, "reason").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Health reason must not be blank");
        }
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }
}
