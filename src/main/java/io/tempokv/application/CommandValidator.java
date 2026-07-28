package io.tempokv.application;

import io.tempokv.server.Session;
import java.util.Objects;

/** Validates commands before a handler is selected. */
public final class CommandValidator {
    /** Rejects commands that are not usable by the administrative pipeline. */
    public void validate(Command command, Session session) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(session, "session");
        if (command.name().isBlank()) {
            throw new IllegalArgumentException("Command name must not be blank");
        }
    }
}
