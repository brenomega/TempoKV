package io.tempokv.integration;

import io.tempokv.bootstrap.ConfigurationException;
import io.tempokv.bootstrap.TempoKvApplication;
import io.tempokv.bootstrap.TempoKvServer;
import io.tempokv.observability.ServerHealth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises UC-00 through the public application bootstrap and real filesystem locks.
 */
class Uc00StartupIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    /** Starts with an empty directory and releases resources for a later startup. */
    @Test
    void startsAndShutsDownAgainstEmptyDirectory() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("data");
        TempoKvServer first = TempoKvApplication.bootstrap(
                serverArguments(dataDirectory), Map.of());
        try {
            assertEquals(ServerHealth.READY, first.state());
            assertTrue(first.isRunning());
            assertTrue(Files.isRegularFile(dataDirectory.resolve(".tempokv.lock")));
            assertEquals(1L, first.metrics().counters().get("server.starts"));
            assertEquals(1L, first.metrics().gauges().get("server.ready"));
            assertEquals(1L, first.metrics().gauges().get("server.lock_held"));
        } finally {
            first.close();
        }

        assertEquals(ServerHealth.STOPPING, first.state());
        assertEquals(0L, first.metrics().gauges().get("server.ready"));
        assertEquals(0L, first.metrics().gauges().get("server.lock_held"));

        TempoKvServer second = TempoKvApplication.bootstrap(
                serverArguments(dataDirectory), Map.of());
        try {
            assertTrue(second.isRunning());
        } finally {
            second.close();
        }
    }

    /** Starts the executable JAR and verifies its shutdown hook releases the data lock. */
    // SmokeTest
    @Test
    void startsExecutableJarAndReleasesLockOnShutdownSignal() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("jar-data");
        Process process = new ProcessBuilder(jarCommand(dataDirectory))
                .redirectErrorStream(true)
                .start();
        try {
            awaitLockFile(dataDirectory.resolve(".tempokv.lock"));
            assertTrue(process.isAlive());
        } finally {
            process.destroy();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "TempoKV JAR did not stop after SIGTERM");
        }

        TempoKvServer restarted = TempoKvApplication.bootstrap(
                serverArguments(dataDirectory), Map.of());
        try {
            assertTrue(restarted.isRunning());
        } finally {
            restarted.close();
        }
    }

    /** Rejects a second active instance for the same data directory. */
    @Test
    void rejectsSecondInstanceForSameDirectory() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("data");
        TempoKvServer first = TempoKvApplication.bootstrap(
                serverArguments(dataDirectory), Map.of());
        try {
            assertThrows(IOException.class,
                    () -> TempoKvApplication.bootstrap(new String[]{"--data-dir=" + dataDirectory}, Map.of()));
        } finally {
            first.close();
        }
    }

    /** Rejects invalid configuration before creating the requested data directory. */
    @Test
    void rejectsInvalidConfigurationBeforeOpeningResources() {
        Path dataDirectory = temporaryDirectory.resolve("must-not-exist");

        assertThrows(ConfigurationException.class,
                () -> TempoKvApplication.bootstrap(
                        new String[]{"--data-dir=" + dataDirectory, "--resp-port=0"}, Map.of()));

        assertFalse(Files.exists(dataDirectory));
    }

    private static Path executableJar() {
        String configuredPath = System.getProperty("tempokv.jar");
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalStateException("The integrationTest task must provide tempokv.jar");
        }
        Path jar = Path.of(configuredPath);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("Executable JAR was not built: " + jar);
        }
        return jar;
    }

    private static String[] serverArguments(Path dataDirectory) throws IOException {
        try (ServerSocket resp = new ServerSocket(0); ServerSocket sql = new ServerSocket(0)) {
            return new String[]{
                    "--data-dir=" + dataDirectory,
                    "--resp-port=" + resp.getLocalPort(),
                    "--sql-port=" + sql.getLocalPort()
            };
        }
    }

    private static List<String> jarCommand(Path dataDirectory) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                executableJar().toString()));
        command.addAll(List.of(serverArguments(dataDirectory)));
        return command;
    }

    private static void awaitLockFile(Path lockFile) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            if (Files.isRegularFile(lockFile)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("TempoKV JAR did not acquire the data-directory lock");
    }
}
