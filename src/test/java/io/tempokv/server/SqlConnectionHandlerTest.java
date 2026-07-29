package io.tempokv.server;

import io.tempokv.application.Command;
import io.tempokv.application.CommandDispatcher;
import io.tempokv.application.CommandHandler;
import io.tempokv.application.CommandResult;
import io.tempokv.application.CommandValidator;
import io.tempokv.observability.MetricsRegistry;
import io.tempokv.protocol.sql.PlanExecutor;
import io.tempokv.protocol.sql.SqlCompiler;
import io.tempokv.protocol.sql.SqlResultEncoder;
import io.tempokv.security.AccessController;
import io.tempokv.security.Authenticator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies bounded SQL framing independently from socket scheduling. */
class SqlConnectionHandlerTest {
    /** Discards an entire oversized statement instead of executing a suffix as a new statement. */
    @Test
    void oversizedStatementCannotExecuteTrailingSuffix() {
        SqlConnectionHandler handler = handler();
        List<byte[]> responses = new ArrayList<>();
        byte[] oversized = ("x".repeat(1_048_577) + "PING;")
                .getBytes(StandardCharsets.UTF_8);

        handler.onBytes(oversized, responses::add);

        assertEquals(1, responses.size());
        assertTrue(new String(responses.getFirst(), StandardCharsets.UTF_8)
                .contains("statement exceeds configured limit"));

        handler.onBytes("PING;".getBytes(StandardCharsets.UTF_8), responses::add);

        assertEquals(2, responses.size());
        assertTrue(new String(responses.get(1), StandardCharsets.UTF_8)
                .contains("PONG"));
    }

    private static SqlConnectionHandler handler() {
        MetricsRegistry metrics = new MetricsRegistry();
        CommandHandler<Command> commands = new CommandHandler<>() {
            @Override public Class<Command> commandType() {
                return Command.class;
            }
            @Override public CommandResult handle(Command command, Session session) {
                return CommandResult.simpleString("PONG");
            }
        };
        CommandDispatcher dispatcher = new CommandDispatcher(
                new CommandValidator(),
                List.of(commands),
                null);
        return new SqlConnectionHandler(
                Authenticator.permissive(),
                new SqlCompiler(),
                new PlanExecutor(dispatcher, AccessController.permissive()),
                new SqlResultEncoder(),
                metrics);
    }
}
