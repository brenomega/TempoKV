package io.tempokv.server;

import java.util.Optional;

/** Holds the logical identity and future execution context of one client connection. */
public final class Session {
    private volatile String identity;

    /** Associates an authenticated identity with this connection. */
    public void authenticate(String authenticatedIdentity) { identity = authenticatedIdentity; }

    /** Returns the identity assigned by the authenticator, when available. */
    public Optional<String> identity() { return Optional.ofNullable(identity); }
}
