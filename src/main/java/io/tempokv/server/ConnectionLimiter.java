package io.tempokv.server;

import java.util.concurrent.atomic.AtomicInteger;

/** Bounds the number of live client transports owned by one protocol endpoint. */
final class ConnectionLimiter {
    private final int maximum;
    private final AtomicInteger active = new AtomicInteger();

    ConnectionLimiter(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        this.maximum = maximum;
    }

    boolean tryAcquire() {
        while (true) {
            int current = active.get();
            if (current >= maximum) return false;
            if (active.compareAndSet(current, current + 1)) return true;
        }
    }

    int release() {
        int remaining = active.decrementAndGet();
        if (remaining < 0) {
            active.incrementAndGet();
            throw new IllegalStateException("Connection limit released without an acquisition");
        }
        return remaining;
    }

    int active() {
        return active.get();
    }
}
