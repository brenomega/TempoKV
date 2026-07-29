package io.tempokv.bootstrap;

import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Defines the immutable, validated configuration required to start one TempoKV node. */
public record ServerConfiguration(
        String bindAddress,
        boolean allowInsecureRemoteTransport,
        int respPort,
        int sqlPort,
        int replicationPort,
        Path dataDirectory,
        NodeRole nodeRole,
        boolean replicationEnabled,
        String nodeId,
        String primaryHost,
        int primaryReplicationPort,
        String replicationToken,
        Duration historyRetention,
        boolean persistenceEnabled,
        boolean authenticationEnabled,
        String authenticationUsername,
        String authenticationPassword,
        OperationalLimits limits) {
    private static final int DEFAULT_RESP_PORT = 6379;
    private static final int DEFAULT_SQL_PORT = 6380;
    private static final int DEFAULT_REPLICATION_PORT = 6381;
    private static final Path DEFAULT_DATA_DIRECTORY = Path.of("data");
    private static final Duration DEFAULT_HISTORY_RETENTION = Duration.ofDays(30);
    private static final long MAX_CONFIGURATION_FILE_BYTES = 1_048_576L;
    private static final Set<String> REJECTED_SECRETS = Set.of(
            "changeme", "default", "password", "secret", "tempokv",
            "tempokv-local", "tempokv-compose", "token");

    private static final Set<String> OPTIONS = Set.of(
            "bind-address",
            "allow-insecure-remote-transport",
            "resp-port",
            "sql-port",
            "replication-port",
            "data-dir",
            "node-role",
            "replication-enabled",
            "node-id",
            "primary-host",
            "primary-replication-port",
            "replication-token",
            "history-retention",
            "persistence-enabled",
            "authentication-enabled",
            "authentication-username",
            "authentication-password",
            "max-connections-per-protocol",
            "max-resp-array-elements",
            "max-command-bytes",
            "max-username-bytes",
            "max-credential-bytes",
            "max-transaction-mutations",
            "max-transaction-write-bytes",
            "max-replication-peers",
            "max-pending-replica-commits",
            "max-pending-replica-bytes",
            "max-snapshot-bytes",
            "replication-sync-timeout",
            "replication-heartbeat-interval",
            "replication-heartbeat-timeout");

    private static final Map<String, String> FILE_OPTIONS = Map.ofEntries(
            Map.entry("tempokv.bind.address", "bind-address"),
            Map.entry("tempokv.transport.allow.insecure.remote",
                    "allow-insecure-remote-transport"),
            Map.entry("tempokv.resp.port", "resp-port"),
            Map.entry("tempokv.sql.port", "sql-port"),
            Map.entry("tempokv.replication.port", "replication-port"),
            Map.entry("tempokv.data.dir", "data-dir"),
            Map.entry("tempokv.node.role", "node-role"),
            Map.entry("tempokv.replication.enabled", "replication-enabled"),
            Map.entry("tempokv.node.id", "node-id"),
            Map.entry("tempokv.primary.host", "primary-host"),
            Map.entry("tempokv.primary.replication.port", "primary-replication-port"),
            Map.entry("tempokv.replication.token", "replication-token"),
            Map.entry("tempokv.history.retention", "history-retention"),
            Map.entry("tempokv.persistence.enabled", "persistence-enabled"),
            Map.entry("tempokv.security.authentication.enabled", "authentication-enabled"),
            Map.entry("tempokv.security.authentication.username", "authentication-username"),
            Map.entry("tempokv.security.authentication.password", "authentication-password"),
            Map.entry("tempokv.limits.connections.per.protocol",
                    "max-connections-per-protocol"),
            Map.entry("tempokv.limits.resp.array.elements", "max-resp-array-elements"),
            Map.entry("tempokv.limits.command.bytes", "max-command-bytes"),
            Map.entry("tempokv.limits.username.bytes", "max-username-bytes"),
            Map.entry("tempokv.limits.credential.bytes", "max-credential-bytes"),
            Map.entry("tempokv.limits.transaction.mutations", "max-transaction-mutations"),
            Map.entry("tempokv.limits.transaction.write.bytes",
                    "max-transaction-write-bytes"),
            Map.entry("tempokv.limits.replication.peers", "max-replication-peers"),
            Map.entry("tempokv.limits.replication.pending.commits",
                    "max-pending-replica-commits"),
            Map.entry("tempokv.limits.replication.pending.bytes",
                    "max-pending-replica-bytes"),
            Map.entry("tempokv.limits.snapshot.bytes", "max-snapshot-bytes"),
            Map.entry("tempokv.timeouts.replication.sync", "replication-sync-timeout"),
            Map.entry("tempokv.timeouts.replication.heartbeat.interval",
                    "replication-heartbeat-interval"),
            Map.entry("tempokv.timeouts.replication.heartbeat",
                    "replication-heartbeat-timeout"));

    /** Validates and normalizes all configuration fields. */
    public ServerConfiguration {
        bindAddress = requireText(bindAddress, "bind-address");
        validateTransport(bindAddress, allowInsecureRemoteTransport);
        validateBindablePort(respPort, "resp-port");
        validateBindablePort(sqlPort, "sql-port");
        validateBindablePort(replicationPort, "replication-port");
        validatePort(primaryReplicationPort, "primary-replication-port");
        if (sameExplicitPort(respPort, sqlPort)
                || sameExplicitPort(respPort, replicationPort)
                || sameExplicitPort(sqlPort, replicationPort)) {
            throw new ConfigurationException(
                    "RESP, SQL and replication ports must be different");
        }
        dataDirectory = normalizeDirectory(dataDirectory);
        nodeRole = Objects.requireNonNull(nodeRole, "node-role");
        nodeId = requireText(nodeId, "node-id");
        primaryHost = requireText(primaryHost, "primary-host");
        replicationToken = normalizeOptional(replicationToken);
        authenticationUsername = normalizeOptional(authenticationUsername);
        authenticationPassword = normalizeOptional(authenticationPassword);
        limits = Objects.requireNonNull(limits, "limits");
        if (nodeId.getBytes(StandardCharsets.UTF_8).length > limits.maxUsernameBytes()) {
            throw new ConfigurationException(
                    "node-id exceeds max-username-bytes");
        }
        if (nodeRole == NodeRole.REPLICA && !replicationEnabled) {
            throw new ConfigurationException(
                    "replication-enabled must be true for a replica node");
        }
        if (nodeRole == NodeRole.REPLICA && replicationEnabled) {
            validateTransport(primaryHost, allowInsecureRemoteTransport);
        }
        if (replicationEnabled && !persistenceEnabled) {
            throw new ConfigurationException(
                    "persistence-enabled must be true when replication-enabled is true");
        }
        validateReplicationSecret(
                replicationEnabled, replicationToken, limits.maxCredentialBytes());
        validateAuthentication(
                authenticationEnabled,
                authenticationUsername,
                authenticationPassword,
                limits);
        historyRetention = Objects.requireNonNull(historyRetention, "history-retention");
        if (historyRetention.isZero() || historyRetention.isNegative()) {
            throw new ConfigurationException("history-retention must be positive");
        }
    }

    /** Preserves the focused constructor shape used by component tests. */
    public ServerConfiguration(
            int respPort,
            int sqlPort,
            Path dataDirectory,
            NodeRole nodeRole,
            Duration historyRetention,
            boolean persistenceEnabled,
            boolean authenticationEnabled) {
        this(
                "127.0.0.1",
                false,
                respPort,
                sqlPort,
                DEFAULT_REPLICATION_PORT,
                dataDirectory,
                nodeRole,
                false,
                "tempokv-node",
                "127.0.0.1",
                DEFAULT_REPLICATION_PORT,
                "",
                historyRetention,
                persistenceEnabled,
                authenticationEnabled,
                "",
                "",
                OperationalLimits.defaults());
    }

    /** Loads configuration using CLI, environment, file, and default precedence. */
    public static ServerConfiguration load(
            String[] args, Map<String, String> environment) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(environment, "environment");
        Map<String, String> cli = parseCommandLine(args);
        Path configPath = configurationPath(
                cli.remove("config"), environment.get("TEMPOKV_CONFIG"));
        Map<String, String> file = configPath == null
                ? Map.of()
                : readProperties(configPath);
        Map<String, String> values = new HashMap<>(defaultValues());
        applyFile(values, file);
        applyEnvironment(values, environment);
        applyCli(values, cli);
        return from(values);
    }

    /** Loads configuration from the current process environment. */
    public static ServerConfiguration load(String[] args) {
        return load(args, System.getenv());
    }

    private static ServerConfiguration from(Map<String, String> values) {
        return new ServerConfiguration(
                values.get("bind-address"),
                parseBoolean(values.get("allow-insecure-remote-transport"),
                        "allow-insecure-remote-transport"),
                parsePort(values.get("resp-port"), "resp-port"),
                parsePort(values.get("sql-port"), "sql-port"),
                parsePort(values.get("replication-port"), "replication-port"),
                Path.of(values.get("data-dir")),
                parseNodeRole(values.get("node-role")),
                parseBoolean(values.get("replication-enabled"), "replication-enabled"),
                values.get("node-id"),
                values.get("primary-host"),
                parsePort(values.get("primary-replication-port"),
                        "primary-replication-port"),
                values.get("replication-token"),
                parseDuration(values.get("history-retention"), "history-retention"),
                parseBoolean(values.get("persistence-enabled"), "persistence-enabled"),
                parseBoolean(values.get("authentication-enabled"), "authentication-enabled"),
                values.get("authentication-username"),
                values.get("authentication-password"),
                new OperationalLimits(
                        parseInt(values, "max-connections-per-protocol"),
                        parseInt(values, "max-resp-array-elements"),
                        parseInt(values, "max-command-bytes"),
                        parseInt(values, "max-username-bytes"),
                        parseInt(values, "max-credential-bytes"),
                        parseInt(values, "max-transaction-mutations"),
                        parseLong(values, "max-transaction-write-bytes"),
                        parseInt(values, "max-replication-peers"),
                        parseInt(values, "max-pending-replica-commits"),
                        parseLong(values, "max-pending-replica-bytes"),
                        parseLong(values, "max-snapshot-bytes"),
                        parseDuration(values.get("replication-sync-timeout"),
                                "replication-sync-timeout"),
                        parseDuration(values.get("replication-heartbeat-interval"),
                                "replication-heartbeat-interval"),
                        parseDuration(values.get("replication-heartbeat-timeout"),
                                "replication-heartbeat-timeout")));
    }

    private static Map<String, String> defaultValues() {
        return Map.ofEntries(
                Map.entry("bind-address", "127.0.0.1"),
                Map.entry("allow-insecure-remote-transport", "false"),
                Map.entry("resp-port", Integer.toString(DEFAULT_RESP_PORT)),
                Map.entry("sql-port", Integer.toString(DEFAULT_SQL_PORT)),
                Map.entry("replication-port", Integer.toString(DEFAULT_REPLICATION_PORT)),
                Map.entry("data-dir", DEFAULT_DATA_DIRECTORY.toString()),
                Map.entry("node-role", NodeRole.PRIMARY.name()),
                Map.entry("replication-enabled", "false"),
                Map.entry("node-id", "tempokv-node"),
                Map.entry("primary-host", "127.0.0.1"),
                Map.entry("primary-replication-port",
                        Integer.toString(DEFAULT_REPLICATION_PORT)),
                Map.entry("replication-token", ""),
                Map.entry("history-retention", DEFAULT_HISTORY_RETENTION.toString()),
                Map.entry("persistence-enabled", "false"),
                Map.entry("authentication-enabled", "true"),
                Map.entry("authentication-username", ""),
                Map.entry("authentication-password", ""),
                Map.entry("max-connections-per-protocol", "4096"),
                Map.entry("max-resp-array-elements", "1024"),
                Map.entry("max-command-bytes", "16777216"),
                Map.entry("max-username-bytes", "128"),
                Map.entry("max-credential-bytes", "4096"),
                Map.entry("max-transaction-mutations", "4096"),
                Map.entry("max-transaction-write-bytes", "33554432"),
                Map.entry("max-replication-peers", "64"),
                Map.entry("max-pending-replica-commits", "1024"),
                Map.entry("max-pending-replica-bytes", "67108864"),
                Map.entry("max-snapshot-bytes", "67108864"),
                Map.entry("replication-sync-timeout", "PT15S"),
                Map.entry("replication-heartbeat-interval", "PT5S"),
                Map.entry("replication-heartbeat-timeout", "PT15S"));
    }

    private static Map<String, String> parseCommandLine(String[] args) {
        Map<String, String> parsed = new HashMap<>();
        for (String argument : args) {
            if (argument == null || !argument.startsWith("--")
                    || !argument.contains("=")) {
                throw new ConfigurationException("Expected --name=value argument");
            }
            int separator = argument.indexOf('=');
            String key = argument.substring(2, separator).trim();
            String value = argument.substring(separator + 1).trim();
            if ((!key.equals("config") && !OPTIONS.contains(key))
                    || value.isEmpty()
                    || parsed.putIfAbsent(key, value) != null) {
                throw new ConfigurationException(
                        "Invalid or duplicate command-line option: " + key);
            }
        }
        return parsed;
    }

    private static Path configurationPath(
            String commandLineValue, String environmentValue) {
        String selected = commandLineValue != null
                ? commandLineValue
                : environmentValue;
        return selected == null || selected.isBlank()
                ? null
                : normalizeFile(Path.of(selected));
    }

    private static Map<String, String> readProperties(Path path) {
        try {
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new ConfigurationException(
                        "Configuration file must be a regular file: " + path);
            }
            if (Files.size(path) > MAX_CONFIGURATION_FILE_BYTES) {
                throw new ConfigurationException(
                        "Configuration file exceeds 1 MiB: " + path);
            }
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(
                    path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            Map<String, String> result = new HashMap<>();
            for (String key : properties.stringPropertyNames()) {
                if (!FILE_OPTIONS.containsKey(key)) {
                    throw new ConfigurationException(
                            "Unknown configuration property: " + key);
                }
                result.put(key, properties.getProperty(key));
            }
            return result;
        } catch (IOException exception) {
            throw new ConfigurationException(
                    "Could not read configuration file: " + path, exception);
        }
    }

    private static void applyFile(
            Map<String, String> values, Map<String, String> file) {
        file.forEach((key, value) ->
                copy(values, FILE_OPTIONS.get(key), value));
    }

    private static void applyEnvironment(
            Map<String, String> values, Map<String, String> environment) {
        OPTIONS.forEach(option -> copy(
                values,
                option,
                environment.get("TEMPOKV_" + option
                        .replace('-', '_')
                        .toUpperCase(Locale.ROOT))));
        copy(values, "authentication-enabled",
                environment.get("TEMPOKV_SECURITY_AUTHENTICATION_ENABLED"));
        copy(values, "authentication-username",
                environment.get("TEMPOKV_SECURITY_AUTHENTICATION_USERNAME"));
        copy(values, "authentication-password",
                environment.get("TEMPOKV_SECURITY_AUTHENTICATION_PASSWORD"));
    }

    private static void applyCli(
            Map<String, String> values, Map<String, String> cli) {
        cli.forEach(values::put);
    }

    private static void copy(
            Map<String, String> target, String key, String value) {
        if (key != null && value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    private static int parsePort(String value, String name) {
        return parseInt(value, name);
    }

    private static int parseInt(Map<String, String> values, String name) {
        return parseInt(values.get(name), name);
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new ConfigurationException(
                    "Invalid " + name + " value", exception);
        }
    }

    private static long parseLong(Map<String, String> values, String name) {
        try {
            return Long.parseLong(values.get(name));
        } catch (NumberFormatException exception) {
            throw new ConfigurationException(
                    "Invalid " + name + " value", exception);
        }
    }

    private static Duration parseDuration(String value, String name) {
        try {
            return Duration.parse(value);
        } catch (RuntimeException exception) {
            throw new ConfigurationException(
                    "Invalid ISO-8601 duration for " + name, exception);
        }
    }

    private static boolean parseBoolean(String value, String name) {
        if ("true".equalsIgnoreCase(value)
                || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        throw new ConfigurationException("Invalid boolean for " + name);
    }

    private static NodeRole parseNodeRole(String value) {
        try {
            return NodeRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new ConfigurationException("Invalid node-role", exception);
        }
    }

    private static void validateTransport(
            String address, boolean allowInsecureRemoteTransport) {
        try {
            InetAddress resolved = InetAddress.getByName(address);
            if (!resolved.isLoopbackAddress() && !allowInsecureRemoteTransport) {
                throw new ConfigurationException(
                        "allow-insecure-remote-transport must be true "
                                + "for a non-loopback bind-address");
            }
        } catch (UnknownHostException exception) {
            throw new ConfigurationException("Invalid bind-address", exception);
        }
    }

    private static void validateReplicationSecret(
            boolean enabled, String secret, int maximumBytes) {
        if (!enabled) return;
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 16
                || bytes > Math.min(maximumBytes, 4_096)
                || secret.codePoints().distinct().limit(3).count() < 3
                || REJECTED_SECRETS.contains(secret.toLowerCase(Locale.ROOT))) {
            throw new ConfigurationException(
                    "replication-token must be an explicit non-trivial secret "
                            + "between 16 and max-credential-bytes");
        }
    }

    private static void validateAuthentication(
            boolean enabled,
            String username,
            String password,
            OperationalLimits limits) {
        if (!enabled) {
            if (!username.isEmpty() || !password.isEmpty()) {
                throw new ConfigurationException(
                        "authentication credentials require authentication-enabled=true");
            }
            return;
        }
        if (username.isEmpty() || password.isEmpty()) {
            throw new ConfigurationException(
                    "authentication-enabled requires explicit username and password");
        }
        if (username.getBytes(StandardCharsets.UTF_8).length
                        > limits.maxUsernameBytes()
                || password.getBytes(StandardCharsets.UTF_8).length
                        > limits.maxCredentialBytes()) {
            throw new ConfigurationException(
                    "authentication credentials exceed configured limits");
        }
    }

    private static void validateBindablePort(int port, String name) {
        if (port < 0 || port > 65_535) {
            throw new ConfigurationException(
                    name + " must be between 0 and 65535");
        }
    }

    private static void validatePort(int port, String name) {
        if (port < 1 || port > 65_535) {
            throw new ConfigurationException(
                    name + " must be between 1 and 65535");
        }
    }

    private static boolean sameExplicitPort(int first, int second) {
        return first != 0 && first == second;
    }

    private static Path normalizeDirectory(Path path) {
        Path normalized = Objects.requireNonNull(
                path, "data-dir").toAbsolutePath().normalize();
        if (normalized.getNameCount() == 0) {
            throw new ConfigurationException(
                    "data-dir must not be the filesystem root");
        }
        return normalized;
    }

    private static Path normalizeFile(Path path) {
        return Objects.requireNonNull(
                path, "config path").toAbsolutePath().normalize();
    }

    private static String requireText(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized.isEmpty()) {
            throw new ConfigurationException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    /** Avoids exposing configured credentials when configuration is logged accidentally. */
    @Override
    public String toString() {
        return "ServerConfiguration[bindAddress=" + bindAddress
                + ", respPort=" + respPort
                + ", sqlPort=" + sqlPort
                + ", replicationPort=" + replicationPort
                + ", dataDirectory=" + dataDirectory
                + ", nodeRole=" + nodeRole
                + ", replicationEnabled=" + replicationEnabled
                + ", nodeId=" + nodeId
                + ", primaryHost=" + primaryHost
                + ", primaryReplicationPort=" + primaryReplicationPort
                + ", replicationToken=[REDACTED]"
                + ", historyRetention=" + historyRetention
                + ", persistenceEnabled=" + persistenceEnabled
                + ", authenticationEnabled=" + authenticationEnabled
                + ", authenticationUsername=[REDACTED]"
                + ", authenticationPassword=[REDACTED]"
                + ", limits=" + limits + "]";
    }

    /** Centralizes bounded operational controls propagated into runtime components. */
    public record OperationalLimits(
            int maxConnectionsPerProtocol,
            int maxRespArrayElements,
            int maxCommandBytes,
            int maxUsernameBytes,
            int maxCredentialBytes,
            int maxTransactionMutations,
            long maxTransactionWriteBytes,
            int maxReplicationPeers,
            int maxPendingReplicaCommits,
            long maxPendingReplicaBytes,
            long maxSnapshotBytes,
            Duration replicationSyncTimeout,
            Duration replicationHeartbeatInterval,
            Duration replicationHeartbeatTimeout) {
        /** Rejects non-positive, exhausting, overflowing, and incoherent limits. */
        public OperationalLimits {
            bounded(maxConnectionsPerProtocol, 1, 100_000,
                    "max-connections-per-protocol");
            bounded(maxRespArrayElements, 1, 65_536,
                    "max-resp-array-elements");
            bounded(maxCommandBytes, 1_024, 64 * 1_048_576,
                    "max-command-bytes");
            bounded(maxUsernameBytes, 1, 4_096, "max-username-bytes");
            bounded(maxCredentialBytes, 8, 1_048_576,
                    "max-credential-bytes");
            bounded(maxTransactionMutations, 1, 100_000,
                    "max-transaction-mutations");
            bounded(maxTransactionWriteBytes, 1_024, 256L * 1_048_576,
                    "max-transaction-write-bytes");
            bounded(maxReplicationPeers, 1, 1_024,
                    "max-replication-peers");
            bounded(maxPendingReplicaCommits, 1, 100_000,
                    "max-pending-replica-commits");
            bounded(maxPendingReplicaBytes, 1_024, 512L * 1_048_576,
                    "max-pending-replica-bytes");
            bounded(maxSnapshotBytes, 1_024, 128L * 1_048_576,
                    "max-snapshot-bytes");
            replicationSyncTimeout = boundedDuration(
                    replicationSyncTimeout, Duration.ofMillis(100),
                    Duration.ofMinutes(10), "replication-sync-timeout");
            replicationHeartbeatInterval = boundedDuration(
                    replicationHeartbeatInterval, Duration.ofMillis(50),
                    Duration.ofMinutes(1), "replication-heartbeat-interval");
            replicationHeartbeatTimeout = boundedDuration(
                    replicationHeartbeatTimeout, Duration.ofMillis(100),
                    Duration.ofMinutes(10), "replication-heartbeat-timeout");
            if (replicationHeartbeatTimeout.compareTo(
                    replicationHeartbeatInterval.multipliedBy(2)) < 0) {
                throw new ConfigurationException(
                        "replication-heartbeat-timeout must be at least twice "
                                + "replication-heartbeat-interval");
            }
            if (maxPendingReplicaBytes < maxCommandBytes) {
                throw new ConfigurationException(
                        "max-pending-replica-bytes must be at least max-command-bytes");
            }
        }

        /** Returns the hardening defaults that predate external configuration. */
        public static OperationalLimits defaults() {
            return new OperationalLimits(
                    4_096,
                    1_024,
                    16 * 1_048_576,
                    128,
                    4_096,
                    4_096,
                    32L * 1_048_576,
                    64,
                    1_024,
                    64L * 1_048_576,
                    64L * 1_048_576,
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(15));
        }

        private static void bounded(
                long value, long minimum, long maximum, String name) {
            if (value < minimum || value > maximum) {
                throw new ConfigurationException(
                        name + " must be between " + minimum + " and " + maximum);
            }
        }

        private static Duration boundedDuration(
                Duration value, Duration minimum, Duration maximum, String name) {
            Duration duration = Objects.requireNonNull(value, name);
            if (duration.compareTo(minimum) < 0
                    || duration.compareTo(maximum) > 0) {
                throw new ConfigurationException(
                        name + " must be between " + minimum + " and " + maximum);
            }
            return duration;
        }
    }

    /** Identifies the replication role configured for this node. */
    public enum NodeRole {
        PRIMARY,
        REPLICA
    }
}
