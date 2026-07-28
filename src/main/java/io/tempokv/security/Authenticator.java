package io.tempokv.security;

import io.tempokv.server.Session;

/** Associates the default local identity with a newly opened connection in E2. */
@FunctionalInterface
public interface Authenticator {
    /** Authenticates the session before its first command. */
    void authenticate(Session session);

    /** Returns the permissive E2 authenticator. */
    static Authenticator permissive() { return session -> session.authenticate("default"); }
}
