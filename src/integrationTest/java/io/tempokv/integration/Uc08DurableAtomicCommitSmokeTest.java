package io.tempokv.integration;

import io.tempokv.persistence.FileSystemAdapter;
import io.tempokv.persistence.FileWriteAheadLog;
import io.tempokv.persistence.FsyncPolicy;
import io.tempokv.persistence.RecoveryManager;
import io.tempokv.persistence.SnapshotStore;
import io.tempokv.storage.MvccStore;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.Mutation;
import io.tempokv.transaction.VersionGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers E5's UC-08 prerequisite: a multi-mutation commit is durable as one version. */
class Uc08DurableAtomicCommitSmokeTest {
    @TempDir Path directory;
    /** Replays every mutation from one commit record under the same recovered version. */
    @Test void recoversAtomicMultiMutationCommit() throws Exception {
        FileSystemAdapter files = new FileSystemAdapter(); FileWriteAheadLog wal = new FileWriteAheadLog(directory, files, FsyncPolicy.ALWAYS); MvccStore source = new MvccStore();
        CommitCoordinator commits = new CommitCoordinator(new VersionGenerator(), source, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), record -> { try { wal.append(record); } catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); } });
        commits.commit(List.of(Mutation.put("left", "L".getBytes(StandardCharsets.UTF_8)), Mutation.put("right", "R".getBytes(StandardCharsets.UTF_8))));
        MvccStore recovered = new MvccStore(); new RecoveryManager(new SnapshotStore(directory, files), wal).recover(recovered, new VersionGenerator());
        assertArrayEquals("L".getBytes(StandardCharsets.UTF_8), recovered.get("left", Instant.EPOCH).orElseThrow().value()); assertEquals(1, recovered.history("right").getFirst().version());
    }
}
