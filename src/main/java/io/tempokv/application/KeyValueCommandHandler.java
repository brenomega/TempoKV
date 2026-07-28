package io.tempokv.application;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.server.Session;
import io.tempokv.storage.StorageEngine;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.Mutation;
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

    /** Creates a handler whose clock is shared by reads, TTL calculation, and commits. */
    public KeyValueCommandHandler(StorageEngine storage, CommitCoordinator commits, Clock clock, MetricsRegistry metrics) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** Returns the category served by this handler. */
    @Override public Class<KeyValueCommand> commandType() { return KeyValueCommand.class; }

    /** Reads the visible MVCC head or converts each mutation into a versioned commit. */
    @Override public CommandResult handle(KeyValueCommand command, Session session) {
        Instant now = Instant.now(clock);
        return switch (command.kind()) {
            case GET -> get(command, now);
            case TTL -> ttl(command, now);
            case SET -> { commits.commit(List.of(Mutation.put(command.key(), command.value()))); metrics.incrementCounter("commands.set"); yield CommandResult.simpleString("OK"); }
            case DEL -> del(command, now);
            case EXPIRE -> expire(command, now);
        };
    }

    private CommandResult get(KeyValueCommand command, Instant now) {
        metrics.incrementCounter("commands.get");
        return storage.get(command.key(), now).<CommandResult>map(value -> new CommandResult.BulkString(value.value()))
                .orElseGet(CommandResult.NullValue::new);
    }

    private CommandResult ttl(KeyValueCommand command, Instant now) {
        metrics.incrementCounter("commands.ttl");
        return new CommandResult.IntegerValue(storage.ttl(command.key(), now));
    }

    private CommandResult del(KeyValueCommand command, Instant now) {
        if (storage.get(command.key(), now).isEmpty()) return new CommandResult.IntegerValue(0);
        commits.commit(List.of(Mutation.tombstone(command.key())));
        metrics.incrementCounter("commands.del");
        return new CommandResult.IntegerValue(1);
    }

    private CommandResult expire(KeyValueCommand command, Instant now) {
        if (storage.get(command.key(), now).isEmpty()) return new CommandResult.IntegerValue(0);
        commits.commit(List.of(Mutation.expire(command.key(), now.plusSeconds(command.expirationSeconds()))));
        metrics.incrementCounter("commands.expire");
        return new CommandResult.IntegerValue(1);
    }
}
