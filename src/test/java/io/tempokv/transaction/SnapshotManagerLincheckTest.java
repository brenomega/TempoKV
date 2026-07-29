package io.tempokv.transaction;

import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions;
import org.junit.jupiter.api.Test;

/**
 * Model-checks the active-snapshot registry used concurrently by sessions and history GC.
 */
public class SnapshotManagerLincheckTest {
    private final AtomicLong version = new AtomicLong(7);
    private final SnapshotManager snapshots =
            new SnapshotManager(version::get);

    /** Opens and releases one snapshot as a single client-level operation. */
    @Operation
    public long openAndRelease() {
        long opened = snapshots.openSnapshot();
        snapshots.releaseSnapshot(opened);
        return opened;
    }

    /** Observes the oldest GC watermark while other actors acquire snapshots. */
    @Operation
    public long oldestActiveVersion() {
        return snapshots.oldestActiveVersion();
    }

    /** Observes the number of currently protected snapshots. */
    @Operation
    public int activeCount() {
        return snapshots.activeCount();
    }

    /** Explores interleavings and compares them with the class's sequential specification. */
    @Test
    void registryIsLinearizable() {
        ModelCheckingOptions options = new ModelCheckingOptions()
                .iterations(20)
                .invocationsPerIteration(100)
                .threads(2)
                .actorsPerThread(2);
        LinChecker.check(SnapshotManagerLincheckTest.class, options);
    }
}
