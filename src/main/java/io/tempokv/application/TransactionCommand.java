package io.tempokv.application;

import java.util.Objects;

/**
 * Represents transaction lifecycle operations independently from RESP or SQL.
 */
public record TransactionCommand(Kind kind) implements Command {
    /** Lists the supported transaction state transitions. */
    public enum Kind { BEGIN, COMMIT, ROLLBACK }

    /** Requires a concrete transaction operation. */
    public TransactionCommand {
        kind = Objects.requireNonNull(kind, "kind");
    }

    /** Returns the normalized operation used by ACL and tracing. */
    @Override public String name() { return kind.name(); }
}
