package io.tempokv.bootstrap;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.observability.ServerHealth;
import io.tempokv.observability.ServerHealthService;
import io.tempokv.persistence.DatabaseLock;
import io.tempokv.persistence.FileSystemAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the essential lifecycle transitions and lock ownership of a TempoKV server.
 */
class TempoKvServerTest {

    @TempDir
    Path temporaryDirectory;

    /** Starts once, reports readiness, and releases the lock during idempotent shutdown. */
    @Test
    void managesEssentialLifecycle() throws Exception {
        DatabaseLock lock = new DatabaseLock(new FileSystemAdapter(), temporaryDirectory.resolve("data"));
        TempoKvServer server = new TempoKvServer(
                new ServerConfiguration(6379, 6380, temporaryDirectory.resolve("data"),
                        ServerConfiguration.NodeRole.PRIMARY, Duration.ofDays(30), false, false),
                lock,
                new MetricsRegistry(),
                new ServerHealthService());

        server.start();
        server.start();

        assertTrue(server.isRunning());
        assertTrue(lock.isHeld());
        assertEquals(ServerHealth.READY, server.state());

        server.stop();
        server.stop();

        assertFalse(server.isRunning());
        assertFalse(lock.isHeld());
        assertEquals(ServerHealth.STOPPING, server.state());
    }
}
