package io.tempokv.server;

import io.tempokv.transaction.TransactionContext;
import java.util.Objects;
import java.util.Optional;

/** Holds the logical identity and future execution context of one client connection. */
public final class Session implements AutoCloseable {
    private volatile String identity;
    private TransactionContext transaction;
    private Runnable transactionCleanup;

    /** Associates an authenticated identity with this connection. */
    public void authenticate(String authenticatedIdentity) { identity = authenticatedIdentity; }

    /** Returns the identity assigned by the authenticator, when available. */
    public Optional<String> identity() { return Optional.ofNullable(identity); }

    /** Returns this connection's active transaction context, when one exists. */
    public synchronized Optional<TransactionContext> transaction() {
        return Optional.ofNullable(transaction);
    }

    /** Attaches a newly opened context and its disconnect cleanup to this session. */
    public synchronized void attachTransaction(
            TransactionContext context, Runnable cleanup) {
        if (transaction != null) throw new IllegalStateException("Transaction already attached");
        transaction = Objects.requireNonNull(context, "context");
        transactionCleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    /** Detaches the expected terminal context without running disconnect cleanup. */
    public synchronized void detachTransaction(TransactionContext expected) {
        if (transaction != expected) throw new IllegalStateException("Unexpected transaction context");
        transaction = null;
        transactionCleanup = null;
    }

    /** Aborts any still-active transaction when its client connection closes. */
    @Override public void close() {
        Runnable cleanup;
        synchronized (this) {
            cleanup = transactionCleanup;
        }
        if (cleanup != null) cleanup.run();
    }
}
