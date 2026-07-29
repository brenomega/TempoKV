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

/** Verifies UC-08 durability: a multi-mutation commit is recovered as one version. */
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

    /** Commits two SQL writes as one version while making staged values visible to their session. */
    @Test
    void executesPublicSnapshotTransactionAsOneAtomicCommit() throws Exception {
        try (Uc05HistoricalReadSmokeTest.ServerFixture fixture =
                        Uc05HistoricalReadSmokeTest.ServerFixture.start(
                                directory.resolve("public"));
                SqlTestClient sql = SqlTestClient.connect(fixture.server())) {
            sql.send(
                    "BEGIN;"
                            + "UPSERT INTO tempokv (key, value) VALUES ('left', 'L');"
                            + "UPSERT INTO tempokv (key, value) VALUES ('right', 'R');"
                            + "SELECT value FROM tempokv WHERE key = 'left';"
                            + "COMMIT;"
                            + "SELECT version FROM HISTORY('right') LIMIT 1;");

            assertEquals("status\nOK\n\n", sql.readResponse());
            assertEquals("status\nOK\n\n", sql.readResponse());
            assertEquals("status\nOK\n\n", sql.readResponse());
            assertEquals("value\nL\n\n", sql.readResponse());
            assertEquals("status\nOK\n\n", sql.readResponse());
            assertEquals("version\n1\n\n", sql.readResponse());
        }
    }
}
