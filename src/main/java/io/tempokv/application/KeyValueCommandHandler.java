package io.tempokv.application;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.server.Session;
import io.tempokv.storage.StorageEngine;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.Mutation;
import io.tempokv.transaction.TransactionManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Executes current-state key-value commands through the MVCC storage and commit pipeline. */
public final class KeyValueCommandHandler implements CommandHandler<KeyValueCommand> {
    private final StorageEngine storage;
    private final CommitCoordinator commits;
    private final Clock clock;
    private final MetricsRegistry metrics;
    private final TransactionManager transactions;

    /** Creates a handler whose clock is shared by reads, TTL calculation, and commits. */
    public KeyValueCommandHandler(StorageEngine storage, CommitCoordinator commits, Clock clock, MetricsRegistry metrics) {
        this(storage, commits, clock, metrics, null);
    }

    /** Creates a handler that stages writes and serves snapshot reads for active transactions. */
    public KeyValueCommandHandler(
            StorageEngine storage,
            CommitCoordinator commits,
            Clock clock,
            MetricsRegistry metrics,
            TransactionManager transactions) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.transactions = transactions;
    }

    /** Returns the category served by this handler. */
    @Override public Class<KeyValueCommand> commandType() { return KeyValueCommand.class; }

    /** Reads the visible MVCC head or converts each mutation into a versioned commit. */
    @Override public CommandResult handle(KeyValueCommand command, Session session) {
        Instant now = Instant.now(clock);
        return switch (command.kind()) {
            case GET -> get(command, session, now);
            case TTL -> ttl(command, session, now);
            case SET -> {
                mutate(session, Mutation.put(command.key(), command.value()));
                metrics.incrementCounter("commands.set");
                yield CommandResult.simpleString("OK");
            }
            case DEL -> del(command, session, now);
            case EXPIRE -> expire(command, session, now);
        };
    }

    private CommandResult get(
            KeyValueCommand command, Session session, Instant now) {
        metrics.incrementCounter("commands.get");
        if (inTransaction(session)) {
            return transactions.get(session, command.key(), now)
                    .<CommandResult>map(value ->
                            new CommandResult.BulkString(value.value()))
                    .orElseGet(CommandResult.NullValue::new);
        }
        return storage.get(command.key(), now).<CommandResult>map(value -> new CommandResult.BulkString(value.value()))
                .orElseGet(CommandResult.NullValue::new);
    }

    private CommandResult ttl(
            KeyValueCommand command, Session session, Instant now) {
        metrics.incrementCounter("commands.ttl");
        long ttl = inTransaction(session)
                ? transactions.ttl(session, command.key(), now)
                : storage.ttl(command.key(), now);
        return new CommandResult.IntegerValue(ttl);
    }

    private CommandResult del(
            KeyValueCommand command, Session session, Instant now) {
        boolean present = inTransaction(session)
                ? transactions.get(session, command.key(), now).isPresent()
                : storage.get(command.key(), now).isPresent();
        if (!present) return new CommandResult.IntegerValue(0);
        mutate(session, Mutation.tombstone(command.key()));
        metrics.incrementCounter("commands.del");
        return new CommandResult.IntegerValue(1);
    }

    private CommandResult expire(
            KeyValueCommand command, Session session, Instant now) {
        boolean present = inTransaction(session)
                ? transactions.get(session, command.key(), now).isPresent()
                : storage.get(command.key(), now).isPresent();
        if (!present) return new CommandResult.IntegerValue(0);
        mutate(session, Mutation.expire(
                command.key(), now.plusSeconds(command.expirationSeconds())));
        metrics.incrementCounter("commands.expire");
        return new CommandResult.IntegerValue(1);
    }

    private boolean inTransaction(Session session) {
        return transactions != null && session.transaction().isPresent();
    }

    private void mutate(Session session, Mutation mutation) {
        if (inTransaction(session)) transactions.stage(session, mutation);
        else commits.commit(List.of(mutation));
    }
}
