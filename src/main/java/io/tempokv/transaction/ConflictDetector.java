package io.tempokv.transaction;

import io.tempokv.storage.StorageEngine;
import java.util.List;
import java.util.Objects;

/**
 * Detects write-write conflicts between a transaction snapshot and current committed heads.
 */
public final class ConflictDetector {
    private final StorageEngine storage;

    /** Creates a detector over the same storage published by the commit coordinator. */
    public ConflictDetector(StorageEngine storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /** Returns sorted conflicting keys without reading or exposing their values. */
    public List<String> conflicts(TransactionContext context) {
        Objects.requireNonNull(context, "context");
        return context.writtenKeys().stream()
                .filter(key -> storage.latestVersion(key) > context.snapshotVersion())
                .sorted()
                .toList();
    }
}
