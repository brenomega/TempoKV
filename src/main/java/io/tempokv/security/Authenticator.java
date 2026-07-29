package io.tempokv.security;

import io.tempokv.server.Session;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** Resolves a session identity from local defaults or explicit protocol credentials. */
@FunctionalInterface
public interface Authenticator {
    /** Authenticates the session before its first command. */
    void authenticate(Session session);

    /**
     * Resolves explicit protocol credentials, returning false without changing the session on
     * failure.
     */
    default boolean authenticate(
            Session session, String username, byte[] password) {
        return false;
    }

    /** Returns the permissive local-development authenticator. */
    static Authenticator permissive() { return session -> session.authenticate("default"); }

    /**
     * Creates a credential authenticator whose initial connection remains anonymous.
     *
     * <p>Password comparison is constant-time and stored credential bytes are defensively copied.
     * Protocol adapters may call the explicit overload when they support an authentication
     * handshake.</p>
     */
    static Authenticator users(Map<String, String> credentials) {
        Map<String, byte[]> expected = Objects.requireNonNull(
                        credentials, "credentials")
                .entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> requireUsername(entry.getKey()),
                        entry -> Objects.requireNonNull(entry.getValue(), "password")
                                .getBytes(StandardCharsets.UTF_8)));
        return new Authenticator() {
            @Override public void authenticate(Session session) {
                Objects.requireNonNull(session, "session");
            }

            @Override public boolean authenticate(
                    Session session, String username, byte[] password) {
                Objects.requireNonNull(session, "session");
                byte[] supplied = Arrays.copyOf(
                        Objects.requireNonNull(password, "password"),
                        password.length);
                byte[] credential = expected.get(username);
                boolean accepted = credential != null
                        && MessageDigest.isEqual(credential, supplied);
                Arrays.fill(supplied, (byte) 0);
                if (accepted) session.authenticate(username);
                return accepted;
            }
        };
    }

    private static String requireUsername(String value) {
        String username = Objects.requireNonNull(value, "username").trim();
        if (username.isEmpty()) throw new IllegalArgumentException("Username must not be blank");
        return username;
    }
}
