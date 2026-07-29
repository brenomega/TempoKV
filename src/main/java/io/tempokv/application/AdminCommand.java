package io.tempokv.application;

/**
 * Contains operational commands that do not access user key-value contents.
 */
public record AdminCommand(Kind kind) implements Command {
    /** Supported administrative operations shared by RESP and SQL. */
    public enum Kind { PING, HEALTH, INFO }

    /** Rejects an administrative command without a concrete operation. */
    public AdminCommand {
        kind = java.util.Objects.requireNonNull(kind, "kind");
    }

    /** Returns the normalized protocol command name. */
    @Override
    public String name() {
        return kind.name();
    }
}
