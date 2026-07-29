package io.tempokv.benchmark;

import io.tempokv.storage.StorageSnapshot;
import io.tempokv.storage.VersionChain;
import io.tempokv.storage.VersionedValue;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BenchmarkFixtures {
    static final Instant BASE_TIME = Instant.parse("2025-01-01T00:00:00Z");

    private BenchmarkFixtures() {}

    static StorageSnapshot snapshot(int datasetSize, int historyDepth) {
        Map<String, VersionChain> chains = new HashMap<>();
        Map<String, StorageSnapshot.HistoryBoundary> boundaries = new HashMap<>();
        for (int index = 0; index < datasetSize; index++) {
            String key = "key-" + index;
            long version = index + 1L;
            VersionedValue value = value(version, "value-" + index);
            chains.put(key, VersionChain.fromNewestFirst(List.of(value)));
            boundaries.put(
                    key,
                    new StorageSnapshot.HistoryBoundary(
                            version, value.committedAt(), false));
        }

        java.util.ArrayList<VersionedValue> history =
                new java.util.ArrayList<>(historyDepth);
        long firstHistoryVersion = datasetSize + 1L;
        for (int index = historyDepth - 1; index >= 0; index--) {
            history.add(value(firstHistoryVersion + index, "history-" + index));
        }
        chains.put("hot", VersionChain.fromNewestFirst(history));
        boundaries.put(
                "hot",
                new StorageSnapshot.HistoryBoundary(
                        firstHistoryVersion, BASE_TIME.plusMillis(firstHistoryVersion), false));
        return new StorageSnapshot(
                datasetSize + (long) historyDepth,
                chains,
                boundaries,
                List.of());
    }

    private static VersionedValue value(long version, String value) {
        return new VersionedValue(
                version,
                value.getBytes(StandardCharsets.UTF_8),
                false,
                BASE_TIME.plusMillis(version),
                null);
    }
}
