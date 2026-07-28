package io.tempokv.transaction;

import java.util.concurrent.atomic.AtomicLong;

/** Generates strictly increasing commit versions for one TempoKV node. */
public final class VersionGenerator {
    private final AtomicLong current = new AtomicLong();

    /** Returns the next version, starting at one. */
    public long nextVersion() {
        return current.incrementAndGet();
    }
}
