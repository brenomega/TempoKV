package io.tempokv.observability;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects bounded, thread-safe counters, gauges, and latency aggregates without business decisions.
 */
public final class MetricsRegistry {
    private static final int MAX_METRIC_NAME_LENGTH = 128;

    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LatencyAccumulator> latencies = new ConcurrentHashMap<>();

    /** Increments the named counter and returns its current approximate value. */
    public long incrementCounter(String name) {
        String metricName = validateName(name);
        LongAdder counter = counters.computeIfAbsent(metricName, ignored -> new LongAdder());
        counter.increment();
        return counter.sum();
    }

    /** Records one non-negative latency observation. */
    public void recordLatency(String name, Duration elapsed) {
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("Latency must not be negative");
        }
        latencies.computeIfAbsent(validateName(name), ignored -> new LatencyAccumulator())
                .record(elapsed.toNanos());
    }

    /** Publishes the latest value of a named gauge. */
    public void setGauge(String name, long value) {
        gauges.computeIfAbsent(validateName(name), ignored -> new AtomicLong()).set(value);
    }

    /** Returns a consistent-by-copy, immutable view of all collected metrics. */
    public MetricsSnapshot snapshot() {
        Map<String, Long> counterSnapshot = new HashMap<>();
        counters.forEach((name, counter) -> counterSnapshot.put(name, counter.sum()));
        Map<String, Long> gaugeSnapshot = new HashMap<>();
        gauges.forEach((name, gauge) -> gaugeSnapshot.put(name, gauge.get()));
        Map<String, LatencySnapshot> latencySnapshot = new HashMap<>();
        latencies.forEach((name, accumulator) -> latencySnapshot.put(name, accumulator.snapshot()));
        return new MetricsSnapshot(counterSnapshot, gaugeSnapshot, latencySnapshot);
    }

    private static String validateName(String name) {
        String normalized = Objects.requireNonNull(name, "name").trim();
        if (normalized.isEmpty() || normalized.length() > MAX_METRIC_NAME_LENGTH
                || !normalized.matches("[a-z][a-z0-9_.-]*")) {
            throw new IllegalArgumentException("Metric name must match [a-z][a-z0-9_.-]*");
        }
        return normalized;
    }

    /** Stores concurrent latency aggregates for one metric. */
    private static final class LatencyAccumulator {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxNanos = new AtomicLong();

        /** Adds one latency value to the aggregate. */
        void record(long nanos) {
            count.increment();
            totalNanos.add(nanos);
            minNanos.accumulateAndGet(nanos, Math::min);
            maxNanos.accumulateAndGet(nanos, Math::max);
        }

        /** Builds an immutable view of the aggregate. */
        LatencySnapshot snapshot() {
            long observations = count.sum();
            return new LatencySnapshot(
                    observations,
                    totalNanos.sum(),
                    observations == 0 ? 0L : minNanos.get(),
                    maxNanos.get());
        }
    }
}
