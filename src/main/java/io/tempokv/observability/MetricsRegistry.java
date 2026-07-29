package io.tempokv.observability;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.Arrays;

/**
 * Collects bounded, thread-safe counters, gauges, and latency aggregates without business decisions.
 */
public final class MetricsRegistry {
    private static final int MAX_METRIC_NAME_LENGTH = 128;
    private static final int LATENCY_SAMPLE_CAPACITY = 2_048;

    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LatencyAccumulator> latencies = new ConcurrentHashMap<>();

    /** Increments the named counter and returns its current approximate value. */
    public long incrementCounter(String name) {
        return addCounter(name, 1);
    }

    /** Adds a non-negative delta to a counter and returns its current approximate value. */
    public long addCounter(String name, long delta) {
        if (delta < 0) throw new IllegalArgumentException("Counter delta must not be negative");
        String metricName = validateName(name);
        LongAdder counter = counters.computeIfAbsent(metricName, ignored -> new LongAdder());
        counter.add(delta);
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
                || !isValidMetricName(normalized)) {
            throw new IllegalArgumentException("Metric name must match [a-z][a-z0-9_.-]*");
        }
        return normalized;
    }

    private static boolean isValidMetricName(String name) {
        char first = name.charAt(0);
        if (first < 'a' || first > 'z') return false;
        for (int index = 1; index < name.length(); index++) {
            char character = name.charAt(index);
            boolean lowercaseLetter = character >= 'a' && character <= 'z';
            boolean digit = character >= '0' && character <= '9';
            if (!lowercaseLetter && !digit
                    && character != '_' && character != '.'
                    && character != '-') {
                return false;
            }
        }
        return true;
    }

    /** Stores concurrent latency aggregates for one metric. */
    private static final class LatencyAccumulator {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxNanos = new AtomicLong();
        private final long[] samples = new long[LATENCY_SAMPLE_CAPACITY];
        private int nextSample;
        private int sampleCount;

        /** Adds one latency value to the aggregate. */
        synchronized void record(long nanos) {
            count.increment();
            totalNanos.add(nanos);
            minNanos.accumulateAndGet(nanos, Math::min);
            maxNanos.accumulateAndGet(nanos, Math::max);
            samples[nextSample] = nanos;
            nextSample = (nextSample + 1) % samples.length;
            sampleCount = Math.min(sampleCount + 1, samples.length);
        }

        /** Builds an immutable view of the aggregate. */
        synchronized LatencySnapshot snapshot() {
            long observations = count.sum();
            long[] ordered = Arrays.copyOf(samples, sampleCount);
            Arrays.sort(ordered);
            return new LatencySnapshot(
                    observations,
                    totalNanos.sum(),
                    observations == 0 ? 0L : minNanos.get(),
                    maxNanos.get(),
                    percentile(ordered, 0.50d),
                    percentile(ordered, 0.95d),
                    percentile(ordered, 0.99d));
        }

        private static long percentile(long[] ordered, double quantile) {
            if (ordered.length == 0) return 0L;
            int index = (int) Math.ceil(quantile * ordered.length) - 1;
            return ordered[Math.max(0, Math.min(index, ordered.length - 1))];
        }
    }
}
