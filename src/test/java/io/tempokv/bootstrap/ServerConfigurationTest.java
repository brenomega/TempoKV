package io.tempokv.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies configuration precedence and fail-fast validation before server startup.
 */
class ServerConfigurationTest {

    @TempDir
    Path temporaryDirectory;

    /** Prefers command-line values over environment, file, and defaults. */
    @Test
    void loadsSourcesWithDocumentedPrecedence() throws Exception {
        Path fileDataDirectory = temporaryDirectory.resolve("file-data");
        Path commandLineDataDirectory = temporaryDirectory.resolve("cli-data");
        Path configurationFile = temporaryDirectory.resolve("tempokv.properties");
        Files.writeString(configurationFile, String.join("\n",
                "tempokv.resp.port=6381",
                "tempokv.sql.port=6382",
                "tempokv.data.dir=" + fileDataDirectory,
                "tempokv.node.role=replica"));

        ServerConfiguration configuration = ServerConfiguration.load(
                new String[]{
                        "--config=" + configurationFile,
                        "--resp-port=6385",
                        "--data-dir=" + commandLineDataDirectory
                },
                Map.of("TEMPOKV_RESP_PORT", "6383", "TEMPOKV_SQL_PORT", "6384"));

        assertEquals(6385, configuration.respPort());
        assertEquals(6384, configuration.sqlPort());
        assertEquals(commandLineDataDirectory.toAbsolutePath().normalize(), configuration.dataDirectory());
        assertEquals(ServerConfiguration.NodeRole.REPLICA, configuration.nodeRole());
    }

    /** Rejects invalid ports before a server can acquire infrastructure resources. */
    @Test
    void rejectsInvalidPort() {
        assertThrows(ConfigurationException.class,
                () -> ServerConfiguration.load(new String[]{"--resp-port=0"}, Map.of()));
    }
}
