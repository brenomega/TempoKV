package io.tempokv.security;

import io.tempokv.application.CommandDispatcher;
import io.tempokv.application.CommandHandler;
import io.tempokv.application.CommandResult;
import io.tempokv.application.CommandValidator;
import io.tempokv.application.KeyValueCommand;
import io.tempokv.application.TransactionCommand;
import io.tempokv.observability.CommandTracer;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.server.Session;
import io.tempokv.server.RespConnectionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies credential resolution, command/prefix ACLs, and value-free tracing aggregates. */
class SecurityAndTracingTest {
    /** Resolves valid credentials and denies commands outside either configured ACL dimension. */
    @Test
    void authenticatesAndAuthorizesCommandAndPrefix() {
        Session session = new Session();
        Authenticator authenticator =
                Authenticator.users(Map.of("reader", "correct horse"));
        authenticator.authenticate(session);
        assertFalse(authenticator.authenticate(
                session, "reader", bytes("wrong")));
        assertTrue(authenticator.authenticate(
                session, "reader", bytes("correct horse")));

        AccessController acl = AccessController.rules(Map.of(
                "reader",
                new AccessController.Rule(
                        Set.of("GET", "BEGIN"),
                        Set.of("public:"))));
        assertTrue(acl.isAllowed(session, KeyValueCommand.get("public:item")));
        assertFalse(acl.isAllowed(session, KeyValueCommand.get("private:item")));
        assertFalse(acl.isAllowed(
                session, KeyValueCommand.set("public:item", bytes("value"))));
        assertTrue(acl.isAllowed(
                session,
                new TransactionCommand(TransactionCommand.Kind.BEGIN)));
    }

    /** Records name, outcome, and percentiles without placing key or value bytes in metric names. */
    @Test
    void tracingDoesNotExposeCommandArguments() {
        MetricsRegistry metrics = new MetricsRegistry();
        String secretKey = "customer:secret-key";
        String secretValue = "private-value";
        CommandDispatcher dispatcher = new CommandDispatcher(
                new CommandValidator(),
                List.<CommandHandler<? extends io.tempokv.application.Command>>of(
                        new CommandHandler<KeyValueCommand>() {
                            @Override public Class<KeyValueCommand> commandType() {
                                return KeyValueCommand.class;
                            }
                            @Override public CommandResult handle(
                                    KeyValueCommand command, Session session) {
                                return CommandResult.simpleString("OK");
                            }
                        }),
                new CommandTracer(metrics));

        dispatcher.dispatch(
                KeyValueCommand.set(secretKey, bytes(secretValue)),
                new Session());
        metrics.recordLatency("known.latency", Duration.ofNanos(1));
        metrics.recordLatency("known.latency", Duration.ofNanos(2));
        metrics.recordLatency("known.latency", Duration.ofNanos(100));

        String metricNames = metrics.snapshot().toString();
        assertFalse(metricNames.contains(secretKey));
        assertFalse(metricNames.contains(secretValue));
        assertTrue(metricNames.contains("command.set"));
        assertEquals(
                2L,
                metrics.snapshot().latencies().get("known.latency").p50Nanos());
        assertEquals(
                100L,
                metrics.snapshot().latencies().get("known.latency").p95Nanos());
    }

    /** Keeps the bounded ASCII metric namespace intact without per-update regex compilation. */
    @Test
    void rejectsInvalidMetricNames() {
        MetricsRegistry metrics = new MetricsRegistry();
        metrics.incrementCounter("valid.metric-1_name");

        assertThrows(
                IllegalArgumentException.class,
                () -> metrics.incrementCounter("Invalid"));
        assertThrows(
                IllegalArgumentException.class,
                () -> metrics.incrementCounter("invalid metric"));
        assertThrows(
                IllegalArgumentException.class,
                () -> metrics.incrementCounter("métric"));
    }

    /** Resolves RESP AUTH before applying the same identity ACL to subsequent commands. */
    @Test
    void respAuthenticationEstablishesAclIdentity() {
        MetricsRegistry metrics = new MetricsRegistry();
        CommandDispatcher dispatcher = new CommandDispatcher(
                new CommandValidator(),
                List.<CommandHandler<? extends io.tempokv.application.Command>>of(
                        new CommandHandler<KeyValueCommand>() {
                            @Override public Class<KeyValueCommand> commandType() {
                                return KeyValueCommand.class;
                            }
                            @Override public CommandResult handle(
                                    KeyValueCommand command, Session session) {
                                return CommandResult.simpleString("ALLOWED");
                            }
                        }));
        RespConnectionHandler handler = new RespConnectionHandler(
                Authenticator.users(Map.of("reader", "secret")),
                AccessController.rules(Map.of(
                        "reader",
                        new AccessController.Rule(
                                Set.of("GET"),
                                Set.of("public:")))),
                dispatcher,
                metrics);
        List<byte[]> responses = new java.util.ArrayList<>();

        handler.onBytes((
                request("AUTH", "reader", "wrong")
                        + request("GET", "public:key")
                        + request("AUTH", "reader", "secret")
                        + request("GET", "public:key"))
                .getBytes(StandardCharsets.UTF_8), responses::add);

        assertEquals(
                List.of(
                        "-ERR invalid credentials\r\n",
                        "-ERR command is not permitted\r\n",
                        "+OK\r\n",
                        "+ALLOWED\r\n"),
                responses.stream()
                        .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                        .toList());
    }

    /** Bounds credential material before authentication performs defensive copies. */
    @Test
    void respAuthenticationRejectsOversizedCredentials() {
        MetricsRegistry metrics = new MetricsRegistry();
        RespConnectionHandler handler = new RespConnectionHandler(
                Authenticator.users(Map.of("reader", "secret")),
                AccessController.permissive(),
                new CommandDispatcher(
                        new CommandValidator(),
                        List.of(),
                        new CommandTracer(metrics)),
                metrics);
        List<byte[]> responses = new java.util.ArrayList<>();

        handler.onBytes(
                request("AUTH", "reader", "x".repeat(4_097))
                        .getBytes(StandardCharsets.UTF_8),
                responses::add);

        assertEquals(
                "-ERR credentials exceed maximum length\r\n",
                new String(responses.getFirst(), StandardCharsets.UTF_8));
    }

    private static String request(String... values) {
        StringBuilder request =
                new StringBuilder("*").append(values.length).append("\r\n");
        for (String value : values) {
            request.append('$')
                    .append(value.getBytes(StandardCharsets.UTF_8).length)
                    .append("\r\n")
                    .append(value)
                    .append("\r\n");
        }
        return request.toString();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
