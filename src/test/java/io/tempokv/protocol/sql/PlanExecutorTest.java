package io.tempokv.protocol.sql;

import io.tempokv.application.CommandDispatcher;
import io.tempokv.application.CommandHandler;
import io.tempokv.application.CommandResult;
import io.tempokv.application.CommandValidator;
import io.tempokv.application.KeyValueCommand;
import io.tempokv.application.KeyValueCommandHandler;
import io.tempokv.application.TemporalCommandHandler;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.security.AccessController;
import io.tempokv.server.Session;
import io.tempokv.storage.MvccStore;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.VersionGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies that SQL plans reuse the command dispatcher for current and temporal semantics. */
class PlanExecutorTest {
    private static final Instant NOW =
            Instant.parse("2026-07-29T12:00:00Z");

    /** Reuses shared handlers for mutation, historical operators, diff, and append-only restore. */
    @Test
    void executesSqlThroughTheSameCommandsAsResp() {
        Fixture fixture = new Fixture();

        fixture.sql("UPSERT INTO tempokv (key, value) VALUES ('profile', 'first');");
        fixture.sql("UPSERT INTO tempokv (key, value) VALUES ('profile', 'second');");

        SqlResult historical = fixture.sql(
                "SELECT value FROM tempokv AS OF VERSION 1 WHERE key = 'profile';");
        assertArrayEquals(bytes("first"), (byte[]) historical.rows().getFirst().getFirst());

        SqlResult history = fixture.sql(
                "SELECT version, value FROM HISTORY('profile') "
                        + "ORDER BY version ASC LIMIT 2;");
        assertEquals(List.of(1L, 2L),
                history.rows().stream().map(row -> row.getFirst()).toList());

        SqlResult diff = fixture.sql(
                "DIFF 'profile' BETWEEN VERSION 1 AND VERSION 2;");
        assertEquals(0L, diff.rows().getFirst().get(2));
        assertArrayEquals(bytes("second"), (byte[]) diff.rows().getFirst().get(4));

        assertEquals(3L, fixture.sql(
                "RESTORE 'profile' TO VERSION 1;").rows().getFirst().getFirst());
        CommandResult.BulkString current = assertInstanceOf(
                CommandResult.BulkString.class,
                fixture.dispatcher.dispatch(
                        KeyValueCommand.get("profile"),
                        fixture.session));
        assertArrayEquals(bytes("first"), current.value());
    }

    /** Applies authorization to the generated application command before dispatch. */
    @Test
    void rejectsUnauthorizedPlanBeforeExecution() {
        Fixture fixture = new Fixture();
        PlanExecutor denied = new PlanExecutor(
                fixture.dispatcher,
                (session, command) -> false);

        SqlException failure = assertThrows(
                SqlException.class,
                () -> denied.execute(
                        fixture.compiler.compile(
                                "SELECT value FROM tempokv WHERE key = 'profile';"),
                        fixture.session));

        assertEquals(SqlException.Kind.EXECUTION, failure.kind());
        assertEquals("command is not permitted", failure.getMessage());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** Composes the same application pipeline used by both network front ends. */
    private static final class Fixture {
        private final Session session = new Session();
        private final SqlCompiler compiler = new SqlCompiler();
        private final CommandDispatcher dispatcher;
        private final PlanExecutor executor;

        private Fixture() {
            MvccStore storage = new MvccStore();
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            CommitCoordinator commits = new CommitCoordinator(
                    new VersionGenerator(),
                    storage,
                    clock);
            MetricsRegistry metrics = new MetricsRegistry();
            dispatcher = new CommandDispatcher(
                    new CommandValidator(),
                    List.<CommandHandler<? extends io.tempokv.application.Command>>of(
                            new KeyValueCommandHandler(
                                    storage,
                                    commits,
                                    clock,
                                    metrics),
                            new TemporalCommandHandler(
                                    storage,
                                    commits,
                                    metrics)));
            executor = new PlanExecutor(
                    dispatcher,
                    AccessController.permissive());
        }

        private SqlResult sql(String statement) {
            return executor.execute(
                    compiler.compile(statement),
                    session);
        }
    }
}
