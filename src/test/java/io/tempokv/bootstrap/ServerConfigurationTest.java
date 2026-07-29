package io.tempokv.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                "tempokv.node.role=replica",
                "tempokv.replication.enabled=true",
                "tempokv.persistence.enabled=true",
                "tempokv.replication.token=a-valid-replication-secret",
                "tempokv.security.authentication.enabled=false"));

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

    /** Allows the server endpoints to request atomically allocated ephemeral ports. */
    @Test
    void allowsEphemeralServerPorts() {
        ServerConfiguration configuration = ServerConfiguration.load(
                new String[]{
                        "--resp-port=0",
                        "--sql-port=0",
                        "--authentication-enabled=false"
                }, Map.of());

        assertEquals(0, configuration.respPort());
        assertEquals(0, configuration.sqlPort());
    }

    /** Rejects invalid ports before a server can acquire infrastructure resources. */
    @Test
    void rejectsInvalidPort() {
        assertThrows(ConfigurationException.class,
                () -> ServerConfiguration.load(new String[]{"--resp-port=-1"}, Map.of()));
    }

    /** Rejects replication identity fields before modified-UTF handshake encoding. */
    @Test
    void boundsReplicationIdentityConfiguration() {
        assertThrows(
                ConfigurationException.class,
                () -> ServerConfiguration.load(
                        new String[]{
                                "--node-id=" + "n".repeat(129),
                                "--authentication-enabled=false"
                        },
                        Map.of()));
        assertThrows(
                ConfigurationException.class,
                () -> ServerConfiguration.load(
                        new String[]{
                                "--replication-enabled=true",
                                "--persistence-enabled=true",
                                "--replication-token=" + "s".repeat(4_097),
                                "--authentication-enabled=false"
                        },
                        Map.of()));
    }

    /** Requires an explicit authentication decision and valid configured credentials. */
    @Test
    void composesSecureAuthenticationConfiguration() {
        assertThrows(
                ConfigurationException.class,
                () -> ServerConfiguration.load(new String[0], Map.of()));
        assertThrows(
                ConfigurationException.class,
                () -> ServerConfiguration.load(
                        new String[]{"--authentication-enabled=true"},
                        Map.of()));

        ServerConfiguration secured = ServerConfiguration.load(
                new String[]{
                        "--authentication-enabled=true",
                        "--authentication-username=operator",
                        "--authentication-password=correct-horse-battery"
                },
                Map.of());
        ServerConfiguration open = ServerConfiguration.load(
                new String[]{"--authentication-enabled=false"}, Map.of());

        assertTrue(secured.authenticationEnabled());
        assertFalse(open.authenticationEnabled());
        assertFalse(secured.toString().contains("correct-horse-battery"));
    }

    /** Rejects absent and placeholder replication credentials for either role. */
    @Test
    void requiresExplicitNonTrivialReplicationSecret() {
        String[] enabled = {
                "--replication-enabled=true",
                "--persistence-enabled=true",
                "--authentication-enabled=false"
        };
        assertThrows(
                ConfigurationException.class,
                () -> ServerConfiguration.load(enabled, Map.of()));
        assertThrows(
                ConfigurationException.class,
                () -> ServerConfiguration.load(
                        new String[]{
                                "--replication-enabled=true",
                                "--persistence-enabled=true",
                                "--replication-token=changeme",
                                "--authentication-enabled=false"
                        },
                        Map.of()));

        ServerConfiguration valid = ServerConfiguration.load(
                new String[]{
                        "--replication-enabled=true",
                        "--persistence-enabled=true",
                        "--replication-token=unique-replication-secret",
                        "--authentication-enabled=false"
                },
                Map.of());
        assertTrue(valid.replicationEnabled());
        assertFalse(valid.toString().contains("unique-replication-secret"));
    }

    /** Makes non-loopback cleartext binding an explicit deployment decision. */
    @Test
    void requiresOptInForInsecureRemoteTransport() {
        assertThrows(
                ConfigurationException.class,
                () -> ServerConfiguration.load(
                        new String[]{
                                "--bind-address=0.0.0.0",
                                "--authentication-enabled=false"
                        },
                        Map.of()));

        ServerConfiguration configured = ServerConfiguration.load(
                new String[]{
                        "--bind-address=0.0.0.0",
                        "--allow-insecure-remote-transport=true",
                        "--authentication-enabled=false"
                },
                Map.of());
        assertEquals("0.0.0.0", configured.bindAddress());
    }

    /** Validates bounded operational defaults, overrides, overflow, and combinations. */
    @Test
    void validatesOperationalLimits() {
        ServerConfiguration defaults = ServerConfiguration.load(
                new String[]{"--authentication-enabled=false"}, Map.of());
        assertEquals(
                4_096,
                defaults.limits().maxConnectionsPerProtocol());
        assertEquals(
                64L * 1_048_576,
                defaults.limits().maxSnapshotBytes());

        ServerConfiguration overridden = ServerConfiguration.load(
                new String[]{
                        "--authentication-enabled=false",
                        "--max-connections-per-protocol=32",
                        "--max-resp-array-elements=64",
                        "--max-command-bytes=2048",
                        "--max-pending-replica-bytes=4096",
                        "--replication-heartbeat-interval=PT0.1S",
                        "--replication-heartbeat-timeout=PT0.3S"
                },
                Map.of());
        assertEquals(
                32,
                overridden.limits().maxConnectionsPerProtocol());
        assertEquals(64, overridden.limits().maxRespArrayElements());

        assertThrows(
                ConfigurationException.class,
                () -> ServerConfiguration.load(
                        new String[]{
                                "--authentication-enabled=false",
                                "--max-command-bytes=0"
                        },
                        Map.of()));
        assertThrows(
                ConfigurationException.class,
                () -> ServerConfiguration.load(
                        new String[]{
                                "--authentication-enabled=false",
                                "--max-command-bytes=999999999999"
                        },
                        Map.of()));
        assertThrows(
                ConfigurationException.class,
                () -> ServerConfiguration.load(
                        new String[]{
                                "--authentication-enabled=false",
                                "--replication-heartbeat-interval=PT5S",
                                "--replication-heartbeat-timeout=PT5S"
                        },
                        Map.of()));
    }
}
