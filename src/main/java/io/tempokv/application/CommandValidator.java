package io.tempokv.application;

import io.tempokv.server.Session;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Validates commands before a handler is selected. */
public final class CommandValidator {
    private static final int MAX_KEY_BYTES = 1_048_576;
    private static final int MAX_VALUE_BYTES = 16 * 1_048_576;

    /** Enforces common names and bounded key/value arguments before dispatch. */
    public void validate(Command command, Session session) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(session, "session");
        if (command.name().isBlank()) {
            throw new IllegalArgumentException("Command name must not be blank");
        }
        switch (command) {
            case AdminCommand ignored -> { }
            case KeyValueCommand keyValue -> {
                validateKey(keyValue.key());
                if (keyValue.value() != null
                        && keyValue.value().length > MAX_VALUE_BYTES) {
                    throw new IllegalArgumentException("Value exceeds 16 MiB");
                }
            }
            case TemporalCommand temporal -> validateKey(temporal.key());
        }
    }

    private static void validateKey(String key) {
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("Key exceeds 1 MiB");
        }
    }
}
