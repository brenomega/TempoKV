package io.tempokv.observability;

import java.util.Map;
import java.util.Objects;

/**
 * Provides an immutable read model of the metrics collected by one TempoKV node.
 */
public record MetricsSnapshot(
        Map<String, Long> counters,
        Map<String, Long> gauges,
        Map<String, LatencySnapshot> latencies) {
    /** Defensively copies every metric map to prevent mutation by callers. */
    public MetricsSnapshot {
        counters = Map.copyOf(Objects.requireNonNull(counters, "counters"));
        gauges = Map.copyOf(Objects.requireNonNull(gauges, "gauges"));
        latencies = Map.copyOf(Objects.requireNonNull(latencies, "latencies"));
    }
}
