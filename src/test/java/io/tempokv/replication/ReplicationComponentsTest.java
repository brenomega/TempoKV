package io.tempokv.replication;

import io.tempokv.bootstrap.ServerConfiguration;
import io.tempokv.persistence.FileSystemAdapter;
import io.tempokv.persistence.FileWriteAheadLog;
import io.tempokv.persistence.FsyncPolicy;
import io.tempokv.persistence.SnapshotStore;
import io.tempokv.storage.MvccStore;
import io.tempokv.transaction.CommitRecord;
import io.tempokv.transaction.Mutation;
import io.tempokv.transaction.VersionGenerator;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies the ordering and synchronization decisions that protect replica durability. */
class ReplicationComponentsTest {
    /** Leaves a caught-up replica connected while an idle primary emits no heartbeat frames. */
    @Test
    void disablesInitialSyncTimeoutAfterCatchUp() throws Exception {
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(30_000);

            ReplicaClient.disableCatchUpTimeout(socket);

            assertEquals(0, socket.getSoTimeout());
        }
    }

    @TempDir Path directory;

    /** Installs a full snapshot, applies its next primary commit, and rejects replay regression. */
    @Test
    void replicaAppliesPrimaryVersionsWithoutAllocatingItsOwnSequence() throws Exception {
        MvccStore primary = new MvccStore();
        CommitRecord first = record(1, "profile", "first");
        primary.apply(first);

        MvccStore replica = new MvccStore();
        VersionGenerator versions = new VersionGenerator();
        ReplicaState state = new ReplicaState(ServerConfiguration.NodeRole.REPLICA);
        state.initialize(0);
        FileSystemAdapter files = new FileSystemAdapter();
        try (FileWriteAheadLog wal =
                new FileWriteAheadLog(directory, files, FsyncPolicy.ALWAYS)) {
            ReplicaApplier applier = new ReplicaApplier(
                    replica,
                    versions,
                    wal,
                    new SnapshotStore(directory, files),
                    state);

            applier.install(primary.snapshot());
            CommitRecord second = record(2, "profile", "second");
            applier.apply(second);

            assertEquals(2, versions.currentVersion());
            assertEquals(2, state.appliedVersion());
            assertEquals(List.of(2L), wal.replay().stream()
                    .map(CommitRecord::version)
                    .toList());
            assertArrayEquals(
                    bytes("second"),
                    replica.get("profile", Instant.MAX).orElseThrow().value());
            assertThrows(IOException.class, () -> applier.apply(second));
        }
    }

    /** Rejects a primary stream gap before WAL append or partial replica publication. */
    @Test
    void replicaRejectsCommitVersionGap() throws Exception {
        MvccStore replica = new MvccStore();
        VersionGenerator versions = new VersionGenerator();
        ReplicaState state = new ReplicaState(ServerConfiguration.NodeRole.REPLICA);
        state.initialize(0);
        FileSystemAdapter files = new FileSystemAdapter();
        try (FileWriteAheadLog wal =
                new FileWriteAheadLog(directory, files, FsyncPolicy.ALWAYS)) {
            ReplicaApplier applier = new ReplicaApplier(
                    replica,
                    versions,
                    wal,
                    new SnapshotStore(directory, files),
                    state);

            assertThrows(IOException.class, () -> applier.apply(
                    record(2, "profile", "skipped-first")));

            assertEquals(0, state.appliedVersion());
            assertEquals(List.of(), wal.replay());
        }
    }

    /** Selects a snapshot for an empty replica and WAL catch-up for a covered replica. */
    @Test
    void synchronizationChoosesFullOrIncrementalFromReplicaCutoff() throws Exception {
        MvccStore primary = new MvccStore();
        CommitRecord first = record(1, "one", "first");
        CommitRecord second = record(2, "two", "second");
        primary.apply(first);
        primary.apply(second);
        try (FileWriteAheadLog wal = new FileWriteAheadLog(
                directory, new FileSystemAdapter(), FsyncPolicy.ALWAYS)) {
            wal.append(first);
            wal.append(second);
            SyncCoordinator coordinator = new SyncCoordinator(primary, wal);

            SyncCoordinator.Plan full = coordinator.plan(0);
            SyncCoordinator.Plan incremental = coordinator.plan(1);

            assertEquals(SyncCoordinator.Mode.FULL, full.mode());
            assertEquals(2, full.snapshot().version());
            assertEquals(SyncCoordinator.Mode.INCREMENTAL, incremental.mode());
            assertEquals(List.of(2L), incremental.commits().stream()
                    .map(CommitRecord::version)
                    .toList());
        }
    }

    /** Keeps compaction behind the slowest connected durable acknowledgement. */
    @Test
    void acknowledgementWatermarkTracksSlowestConnectedReplica() {
        AckTracker tracker = new AckTracker();
        tracker.register("replica-a", 7);
        tracker.register("replica-b", 4);
        tracker.acknowledge("replica-b", 6);

        assertEquals(6, tracker.minimumAcknowledgedVersion().orElseThrow());

        tracker.remove("replica-b");
        assertEquals(7, tracker.minimumAcknowledgedVersion().orElseThrow());
        assertThrows(
                IllegalStateException.class,
                () -> tracker.acknowledge("replica-a", 6));
    }

    private static CommitRecord record(long version, String key, String value) {
        return new CommitRecord(
                version,
                Instant.EPOCH.plusSeconds(version),
                List.of(Mutation.put(key, bytes(value))));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
