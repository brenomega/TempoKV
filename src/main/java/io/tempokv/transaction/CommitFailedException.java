package io.tempokv.transaction;

/**
 * Reports an infrastructure failure that prevented a commit from being safely acknowledged.
 */
public final class CommitFailedException extends RuntimeException {
    /** Wraps the durable-log failure without exposing filesystem details to protocol clients. */
    public CommitFailedException(Throwable cause) {
        super("ERR commit could not be made durable", cause);
    }
}
