package io.tempokv.application;

import io.tempokv.server.Session;

/** Executes one category of validated application commands. */
public interface CommandHandler<C extends Command> {
    /** Identifies the command category accepted by this handler. */
    Class<C> commandType();

    /** Executes the command without accessing transport resources. */
    CommandResult handle(C command, Session session);
}
