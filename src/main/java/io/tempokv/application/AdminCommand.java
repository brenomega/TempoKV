package io.tempokv.application;

/**
 * Contains operational commands that do not access key-value storage in this stage.
 */
public record AdminCommand(Kind kind) implements Command {
    /** Supported administrative operations for the RESP endpoint. */
    public enum Kind { PING }

    /** Returns the normalized protocol command name. */
    @Override
    public String name() {
        return kind.name();
    }
}
