package io.tempokv.replication;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Tracks connected replicas and their monotonically increasing durable acknowledgements.
 */
public final class AckTracker {
    private final Map<String, Long> acknowledged = new HashMap<>();

    /** Registers a connected replica at the version declared by its handshake. */
    public synchronized void register(String replicaId, long version) {
        if (version < 0) throw new IllegalArgumentException("ACK version must not be negative");
        acknowledged.put(requireId(replicaId), version);
    }

    /** Advances one replica acknowledgement without accepting regression or unknown peers. */
    public synchronized void acknowledge(String replicaId, long version) {
        String id = requireId(replicaId);
        Long previous = acknowledged.get(id);
        if (previous == null) throw new IllegalStateException("Replica is not connected");
        if (version < previous) throw new IllegalStateException("Replica ACK moved backwards");
        acknowledged.put(id, version);
    }

    /** Removes a disconnected replica so future compaction may force it to full resync. */
    public synchronized void remove(String replicaId) {
        acknowledged.remove(requireId(replicaId));
    }

    /** Returns the minimum connected ACK, or empty when compaction has no replica constraint. */
    public synchronized OptionalLong minimumAcknowledgedVersion() {
        return acknowledged.values().stream().mapToLong(Long::longValue).min();
    }

    /** Returns an immutable diagnostic view keyed by replica identifier. */
    public synchronized Map<String, Long> snapshot() {
        return Map.copyOf(acknowledged);
    }

    private static String requireId(String value) {
        String id = Objects.requireNonNull(value, "replicaId").trim();
        if (id.isEmpty()) throw new IllegalArgumentException("Replica id must not be blank");
        return id;
    }
}
