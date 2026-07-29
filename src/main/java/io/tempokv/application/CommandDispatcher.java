package io.tempokv.application;

import io.tempokv.observability.CommandTracer;
import io.tempokv.server.Session;
import java.util.List;
import java.util.Objects;

/** Routes validated commands to a specialized handler without knowing the protocol. */
public final class CommandDispatcher {
    private final CommandValidator validator;
    private final List<CommandHandler<? extends Command>> handlers;
    private final CommandTracer tracer;

    /** Creates a dispatcher with the handlers available in the current server stage. */
    public CommandDispatcher(CommandValidator validator, List<CommandHandler<? extends Command>> handlers) {
        this(validator, handlers, null);
    }

    /** Creates a dispatcher that traces the complete validated handler execution. */
    public CommandDispatcher(
            CommandValidator validator,
            List<CommandHandler<? extends Command>> handlers,
            CommandTracer tracer) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers"));
        this.tracer = tracer;
    }

    /** Validates and dispatches one command, returning a deterministic unsupported-command error. */
    public CommandResult dispatch(Command command, Session session) {
        if (tracer == null) {
            validator.validate(command, session);
            return dispatchValidated(command, session);
        }
        return tracer.trace(command, () -> {
            validator.validate(command, session);
            return dispatchValidated(command, session);
        });
    }

    private CommandResult dispatchValidated(Command command, Session session) {
        for (CommandHandler<? extends Command> handler : handlers) {
            if (handler.commandType().isInstance(command)) {
                return dispatchTo(handler, command, session);
            }
        }
        return CommandResult.error("ERR unsupported command " + command.name());
    }

    @SuppressWarnings("unchecked")
    private static <C extends Command> CommandResult dispatchTo(
            CommandHandler<? extends Command> handler, Command command, Session session) {
        CommandHandler<C> typedHandler = (CommandHandler<C>) handler;
        return typedHandler.handle((C) command, session);
    }
}
