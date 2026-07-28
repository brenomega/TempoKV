package io.tempokv.observability;

/**
 * Captures aggregate latency measurements in nanoseconds for one metric name.
 */
public record LatencySnapshot(long count, long totalNanos, long minNanos, long maxNanos) {
    /** Returns the average latency while avoiding division by zero. */
    public double averageNanos() {
        return count == 0 ? 0.0d : (double) totalNanos / count;
    }
}
