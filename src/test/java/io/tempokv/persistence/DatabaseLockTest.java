package io.tempokv.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies exclusive ownership of one data directory inside a JVM process.
 */
class DatabaseLockTest {

    @TempDir
    Path temporaryDirectory;

    /** Rejects a second writer while the first lock remains held. */
    @Test
    void rejectsConcurrentOwner() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("data");
        DatabaseLock first = new DatabaseLock(new FileSystemAdapter(), dataDirectory);
        DatabaseLock second = new DatabaseLock(new FileSystemAdapter(), dataDirectory);
        first.acquire();
        try {
            assertTrue(first.isHeld());
            assertThrows(IOException.class, second::acquire);
        } finally {
            first.close();
            second.close();
        }
    }
}
