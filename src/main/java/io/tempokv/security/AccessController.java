package io.tempokv.security;

import io.tempokv.application.Command;
import io.tempokv.server.Session;

/** Authorizes a session to execute a command. */
@FunctionalInterface
public interface AccessController {
    /** Returns whether a session may execute the command. */
    boolean isAllowed(Session session, Command command);

    /** Returns the permissive E2 authorization policy. */
    static AccessController permissive() { return (session, command) -> true; }
}
