package io.tempokv.bootstrap;

import java.io.IOException;
import java.io.Reader;
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

/**
 * Defines the immutable, validated configuration required to start one TempoKV node.
 */
public record ServerConfiguration(
        int respPort,
        int sqlPort,
        int replicationPort,
        Path dataDirectory,
        NodeRole nodeRole,
        String nodeId,
        String primaryHost,
        int primaryReplicationPort,
        String replicationToken,
        Duration historyRetention,
        boolean persistenceEnabled,
        boolean authenticationEnabled) {
    private static final int DEFAULT_RESP_PORT = 6379;
    private static final int DEFAULT_SQL_PORT = 6380;
    private static final int DEFAULT_REPLICATION_PORT = 6381;
    private static final Path DEFAULT_DATA_DIRECTORY = Path.of("data");
    private static final Duration DEFAULT_HISTORY_RETENTION = Duration.ofDays(30);
    private static final long MAX_CONFIGURATION_FILE_BYTES = 1_048_576L;

    private static final Set<String> FILE_KEYS = Set.of(
            "tempokv.resp.port",
            "tempokv.sql.port",
            "tempokv.replication.port",
            "tempokv.data.dir",
            "tempokv.node.role",
            "tempokv.node.id",
            "tempokv.primary.host",
            "tempokv.primary.replication.port",
            "tempokv.replication.token",
            "tempokv.history.retention",
            "tempokv.persistence.enabled",
            "tempokv.security.authentication.enabled");

    /** Validates and normalizes all configuration fields. */
    public ServerConfiguration {
        validateBindablePort(respPort, "respPort");
        validateBindablePort(sqlPort, "sqlPort");
        validatePort(replicationPort, "replicationPort");
        validatePort(primaryReplicationPort, "primaryReplicationPort");
        if (sameExplicitPort(respPort, sqlPort)
                || sameExplicitPort(respPort, replicationPort)
                || sameExplicitPort(sqlPort, replicationPort)) {
            throw new ConfigurationException("RESP, SQL and replication ports must be different");
        }
        dataDirectory = normalizeDirectory(dataDirectory);
        nodeRole = Objects.requireNonNull(nodeRole, "nodeRole");
        nodeId = requireText(nodeId, "nodeId");
        primaryHost = requireText(primaryHost, "primaryHost");
        replicationToken = requireText(replicationToken, "replicationToken");
        if (nodeId.getBytes(StandardCharsets.UTF_8).length > 128) {
            throw new ConfigurationException("nodeId exceeds 128 UTF-8 bytes");
        }
        if (replicationToken.getBytes(StandardCharsets.UTF_8).length > 4_096) {
            throw new ConfigurationException(
                    "replicationToken exceeds 4096 UTF-8 bytes");
        }
        historyRetention = Objects.requireNonNull(historyRetention, "historyRetention");
        if (historyRetention.isZero() || historyRetention.isNegative()) {
            throw new ConfigurationException("History retention must be positive");
        }
    }

    /** Preserves the E7 constructor shape with local replication defaults. */
    public ServerConfiguration(
            int respPort,
            int sqlPort,
            Path dataDirectory,
            NodeRole nodeRole,
            Duration historyRetention,
            boolean persistenceEnabled,
            boolean authenticationEnabled) {
        this(
                respPort,
                sqlPort,
                DEFAULT_REPLICATION_PORT,
                dataDirectory,
                nodeRole,
                "tempokv-node",
                "127.0.0.1",
                DEFAULT_REPLICATION_PORT,
                "tempokv-local",
                historyRetention,
                persistenceEnabled,
                authenticationEnabled);
    }

    /** Loads configuration using CLI, environment, file, and default precedence. */
    public static ServerConfiguration load(String[] args, Map<String, String> environment) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(environment, "environment");

        Map<String, String> cli = parseCommandLine(args);
        Path configPath = configurationPath(cli.remove("config"), environment.get("TEMPOKV_CONFIG"));
        Map<String, String> file = configPath == null ? Map.of() : readProperties(configPath);
        Map<String, String> values = new HashMap<>();
        values.putAll(defaultValues());
        applyFile(values, file);
        applyEnvironment(values, environment);
        applyCli(values, cli);

        return new ServerConfiguration(
                parsePort(values.get("resp-port"), "resp-port"),
                parsePort(values.get("sql-port"), "sql-port"),
                parsePort(values.get("replication-port"), "replication-port"),
                Path.of(values.get("data-dir")),
                parseNodeRole(values.get("node-role")),
                values.get("node-id"),
                values.get("primary-host"),
                parsePort(
                        values.get("primary-replication-port"),
                        "primary-replication-port"),
                values.get("replication-token"),
                parseDuration(values.get("history-retention"), "history-retention"),
                parseBoolean(values.get("persistence-enabled"), "persistence-enabled"),
                parseBoolean(values.get("authentication-enabled"), "authentication-enabled"));
    }

    /** Loads configuration from the current process environment. */
    public static ServerConfiguration load(String[] args) {
        return load(args, System.getenv());
    }

    /** Returns the lowest-precedence configuration values. */
    private static Map<String, String> defaultValues() {
        return Map.ofEntries(
                Map.entry("resp-port", Integer.toString(DEFAULT_RESP_PORT)),
                Map.entry("sql-port", Integer.toString(DEFAULT_SQL_PORT)),
                Map.entry("replication-port", Integer.toString(DEFAULT_REPLICATION_PORT)),
                Map.entry("data-dir", DEFAULT_DATA_DIRECTORY.toString()),
                Map.entry("node-role", NodeRole.PRIMARY.name()),
                Map.entry("node-id", "tempokv-node"),
                Map.entry("primary-host", "127.0.0.1"),
                Map.entry("primary-replication-port", Integer.toString(DEFAULT_REPLICATION_PORT)),
                Map.entry("replication-token", "tempokv-local"),
                Map.entry("history-retention", DEFAULT_HISTORY_RETENTION.toString()),
                Map.entry("persistence-enabled", "false"),
                Map.entry("authentication-enabled", "false"));
    }

    /** Parses strict long-form command-line options. */
    private static Map<String, String> parseCommandLine(String[] args) {
        Map<String, String> parsed = new HashMap<>();
        for (String argument : args) {
            if (argument == null || !argument.startsWith("--") || !argument.contains("=")) {
                throw new ConfigurationException("Expected --name=value argument");
            }
            int separator = argument.indexOf('=');
            String key = argument.substring(2, separator).trim();
            String value = argument.substring(separator + 1).trim();
            if (!isCliKey(key) || value.isEmpty() || parsed.putIfAbsent(key, value) != null) {
                throw new ConfigurationException("Invalid or duplicate command-line option: " + argument);
            }
        }
        return parsed;
    }

    /** Checks whether an option is supported by the current bootstrap stage. */
    private static boolean isCliKey(String key) {
        return key.equals("config") || defaultValues().containsKey(key);
    }

    /** Selects the optional properties file using CLI-over-environment precedence. */
    private static Path configurationPath(String commandLineValue, String environmentValue) {
        String selected = commandLineValue != null ? commandLineValue : environmentValue;
        return selected == null || selected.isBlank() ? null : normalizeFile(Path.of(selected));
    }

    /** Reads a bounded UTF-8 properties file and rejects unknown keys. */
    private static Map<String, String> readProperties(Path path) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new ConfigurationException("Configuration file must be a regular file: " + path);
            }
            if (Files.size(path) > MAX_CONFIGURATION_FILE_BYTES) {
                throw new ConfigurationException("Configuration file exceeds 1 MiB: " + path);
            }
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            Map<String, String> result = new HashMap<>();
            for (String key : properties.stringPropertyNames()) {
                if (!FILE_KEYS.contains(key)) {
                    throw new ConfigurationException("Unknown configuration property: " + key);
                }
                result.put(key, properties.getProperty(key));
            }
            return result;
        } catch (IOException exception) {
            throw new ConfigurationException("Could not read configuration file: " + path, exception);
        }
    }

    /** Merges supported file values into the selected configuration. */
    private static void applyFile(Map<String, String> values, Map<String, String> file) {
        copyIfPresent(file, "tempokv.resp.port", values, "resp-port");
        copyIfPresent(file, "tempokv.sql.port", values, "sql-port");
        copyIfPresent(file, "tempokv.replication.port", values, "replication-port");
        copyIfPresent(file, "tempokv.data.dir", values, "data-dir");
        copyIfPresent(file, "tempokv.node.role", values, "node-role");
        copyIfPresent(file, "tempokv.node.id", values, "node-id");
        copyIfPresent(file, "tempokv.primary.host", values, "primary-host");
        copyIfPresent(file, "tempokv.primary.replication.port", values, "primary-replication-port");
        copyIfPresent(file, "tempokv.replication.token", values, "replication-token");
        copyIfPresent(file, "tempokv.history.retention", values, "history-retention");
        copyIfPresent(file, "tempokv.persistence.enabled", values, "persistence-enabled");
        copyIfPresent(file, "tempokv.security.authentication.enabled", values, "authentication-enabled");
    }

    /** Merges supported environment variables into the selected configuration. */
    private static void applyEnvironment(Map<String, String> values, Map<String, String> environment) {
        copyIfPresent(environment, "TEMPOKV_RESP_PORT", values, "resp-port");
        copyIfPresent(environment, "TEMPOKV_SQL_PORT", values, "sql-port");
        copyIfPresent(environment, "TEMPOKV_REPLICATION_PORT", values, "replication-port");
        copyIfPresent(environment, "TEMPOKV_DATA_DIR", values, "data-dir");
        copyIfPresent(environment, "TEMPOKV_NODE_ROLE", values, "node-role");
        copyIfPresent(environment, "TEMPOKV_NODE_ID", values, "node-id");
        copyIfPresent(environment, "TEMPOKV_PRIMARY_HOST", values, "primary-host");
        copyIfPresent(environment, "TEMPOKV_PRIMARY_REPLICATION_PORT", values, "primary-replication-port");
        copyIfPresent(environment, "TEMPOKV_REPLICATION_TOKEN", values, "replication-token");
        copyIfPresent(environment, "TEMPOKV_HISTORY_RETENTION", values, "history-retention");
        copyIfPresent(environment, "TEMPOKV_PERSISTENCE_ENABLED", values, "persistence-enabled");
        copyIfPresent(environment, "TEMPOKV_AUTHENTICATION_ENABLED", values, "authentication-enabled");
    }

    /** Merges highest-precedence command-line options into the selected configuration. */
    private static void applyCli(Map<String, String> values, Map<String, String> cli) {
        cli.forEach(values::put);
    }

    /** Copies a non-blank value between configuration source maps. */
    private static void copyIfPresent(Map<String, String> source, String sourceKey, Map<String, String> target, String targetKey) {
        String value = source.get(sourceKey);
        if (value != null && !value.isBlank()) {
            target.put(targetKey, value.trim());
        }
    }

    /** Parses a configured TCP port. */
    private static int parsePort(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new ConfigurationException("Invalid " + name + " value: " + value, exception);
        }
    }

    /** Parses an ISO-8601 duration option. */
    private static Duration parseDuration(String value, String name) {
        try {
            return Duration.parse(value);
        } catch (RuntimeException exception) {
            throw new ConfigurationException("Invalid ISO-8601 duration for " + name + ": " + value, exception);
        }
    }

    /** Parses a strict boolean option. */
    private static boolean parseBoolean(String value, String name) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        throw new ConfigurationException("Invalid boolean for " + name + ": " + value);
    }

    /** Parses a case-insensitive replication role. */
    private static NodeRole parseNodeRole(String value) {
        try {
            return NodeRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new ConfigurationException("Invalid node role: " + value, exception);
        }
    }

    /** Validates a TCP port that the local server can bind, including port zero. */
    private static void validateBindablePort(int port, String name) {
        if (port < 0 || port > 65_535) {
            throw new ConfigurationException(name + " must be between 0 and 65535");
        }
    }

    /** Validates the usable TCP port range for an explicitly addressed endpoint. */
    private static void validatePort(int port, String name) {
        if (port < 1 || port > 65_535) {
            throw new ConfigurationException(name + " must be between 1 and 65535");
        }
    }

    /** Port zero requests an ephemeral bind, so it cannot collide with another endpoint. */
    private static boolean sameExplicitPort(int first, int second) {
        return first != 0 && first == second;
    }

    /** Converts the configured data directory into a stable absolute path. */
    private static Path normalizeDirectory(Path path) {
        Path normalized = Objects.requireNonNull(path, "dataDirectory").toAbsolutePath().normalize();
        if (normalized.getNameCount() == 0) {
            throw new ConfigurationException("Data directory must not be the filesystem root");
        }
        return normalized;
    }

    /** Converts the optional configuration file into a stable absolute path. */
    private static Path normalizeFile(Path path) {
        return Objects.requireNonNull(path, "config path").toAbsolutePath().normalize();
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new ConfigurationException(field + " must not be blank");
        }
        return normalized;
    }

    /** Identifies the future replication role configured for this node. */
    public enum NodeRole {
        PRIMARY,
        REPLICA
    }
}
