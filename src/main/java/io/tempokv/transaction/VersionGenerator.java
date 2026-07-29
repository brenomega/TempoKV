package io.tempokv.transaction;

import java.util.concurrent.atomic.AtomicLong;

/** Generates strictly increasing commit versions for one TempoKV node. */
public final class VersionGenerator {
    private final AtomicLong current = new AtomicLong();

    /** Returns the next version, starting at one. */
    public long nextVersion() {
        return current.incrementAndGet();
    }

    /** Advances the generator after recovery without ever moving its sequence backwards. */
    public void advanceTo(long recoveredVersion) {
        if (recoveredVersion < 0) throw new IllegalArgumentException("Recovered version must not be negative");
        current.accumulateAndGet(recoveredVersion, Math::max);
    }

    /** Returns the last allocated version, including a version restored during recovery. */
    public long currentVersion() { return current.get(); }
}
