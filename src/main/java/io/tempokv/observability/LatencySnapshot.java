package io.tempokv.observability;

/**
 * Captures aggregate latency measurements in nanoseconds for one metric name.
 */
public record LatencySnapshot(
        long count,
        long totalNanos,
        long minNanos,
        long maxNanos,
        long p50Nanos,
        long p95Nanos,
        long p99Nanos) {
    /** Preserves callers that only provide aggregate fields. */
    public LatencySnapshot(
            long count, long totalNanos, long minNanos, long maxNanos) {
        this(count, totalNanos, minNanos, maxNanos, 0L, 0L, 0L);
    }

    /** Returns the average latency while avoiding division by zero. */
    public double averageNanos() {
        return count == 0 ? 0.0d : (double) totalNanos / count;
    }
}
