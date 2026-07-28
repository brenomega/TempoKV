package io.tempokv.application;

import io.tempokv.observability.MetricsRegistry;
import io.tempokv.server.Session;
import io.tempokv.storage.HistoryGarbageCollector;
import io.tempokv.storage.MvccStore;
import io.tempokv.storage.StorageEngine;
import io.tempokv.storage.VersionedValue;
import io.tempokv.transaction.CommitCoordinator;
import io.tempokv.transaction.Mutation;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Executes historical operations, retention, binary comparison, and append-only restoration. */
public final class TemporalCommandHandler implements CommandHandler<TemporalCommand> {
    private static final long MAX_TEMPORAL_RESPONSE_BYTES = 16L * 1024 * 1024;
    private final StorageEngine storage;
    private final CommitCoordinator commits;
    private final MetricsRegistry metrics;
    private final Clock clock;
    private final HistoryGarbageCollector garbageCollector;

    /** Creates a handler without automatic retention, suitable for focused unit callers. */
    public TemporalCommandHandler(
            StorageEngine storage, CommitCoordinator commits, MetricsRegistry metrics) {
        this(storage, commits, metrics, Clock.systemUTC(), null);
    }

    /** Creates the production handler with explicit clock and retention collector. */
    public TemporalCommandHandler(
            StorageEngine storage,
            CommitCoordinator commits,
            MetricsRegistry metrics,
            Clock clock,
            HistoryGarbageCollector garbageCollector) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.garbageCollector = garbageCollector;
        if (garbageCollector != null && !(storage instanceof MvccStore)) {
            throw new IllegalArgumentException("History collection requires MvccStore");
        }
    }

    /** Returns the temporal command family. */
    @Override public Class<TemporalCommand> commandType() { return TemporalCommand.class; }

    /** Applies configured retention, then executes one temporal operation. */
    @Override public CommandResult handle(TemporalCommand command, Session session) {
        collectRetainedHistory();
        return switch (command.kind()) {
            case GETAT -> getAt(command);
            case HISTORY -> history(command);
            case DIFF -> diff(command);
            case RESTOREAT -> restore(command);
        };
    }

    private CommandResult getAt(TemporalCommand command) {
        metrics.incrementCounter("commands.getat");
        return valueResult(lookup(command.key(), command.selector()), command.selector());
    }

    private CommandResult history(TemporalCommand command) {
        List<VersionedValue> versions =
                storage.history(command.key(), command.offset(), command.limit());
        if (versions.isEmpty()
                && storage.historical(command.key(), Long.MAX_VALUE, null).status()
                == StorageEngine.HistoricalValue.Status.KEY_NOT_FOUND) {
            return CommandResult.error("ERR key does not exist");
        }
        long responseBytes = versions.stream()
                .filter(value -> !value.tombstone())
                .mapToLong(value -> value.value().length)
                .sum();
        if (responseBytes > MAX_TEMPORAL_RESPONSE_BYTES) {
            return CommandResult.error(
                    "ERR history response exceeds 16 MiB");
        }
        metrics.incrementCounter("commands.history");
        return new CommandResult.Array(versions.stream().map(this::historyEntry).toList());
    }

    private CommandResult diff(TemporalCommand command) {
        StorageEngine.HistoricalValue first = lookup(command.key(), command.selector());
        StorageEngine.HistoricalValue second = lookup(command.key(), command.otherSelector());
        if (first.status() == StorageEngine.HistoricalValue.Status.HISTORY_UNAVAILABLE
                || second.status() == StorageEngine.HistoricalValue.Status.HISTORY_UNAVAILABLE) {
            return unavailable();
        }
        HistoricalPoint before = historicalPoint(first, command.selector());
        HistoricalPoint after = historicalPoint(second, command.otherSelector());
        int commonPrefix = commonPrefix(before.value(), after.value());
        metrics.incrementCounter("commands.diff");
        return new CommandResult.Array(List.of(
                CommandResult.simpleString(before.state()),
                CommandResult.simpleString(after.state()),
                new CommandResult.IntegerValue(commonPrefix),
                suffix(before.value(), commonPrefix),
                suffix(after.value(), commonPrefix)));
    }

    private CommandResult restore(TemporalCommand command) {
        StorageEngine.HistoricalValue source = lookup(command.key(), command.selector());
        if (source.status() != StorageEngine.HistoricalValue.Status.FOUND) {
            return valueResult(source, command.selector());
        }
        VersionedValue value = source.value();
        Mutation restoration = value.tombstone()
                ? Mutation.restoreTombstone(command.key(), value.version())
                : Mutation.restorePut(
                        command.key(), value.value(), value.expiresAt(), value.version());
        var record = commits.commit(List.of(restoration));
        metrics.incrementCounter("commands.restoreat");
        return new CommandResult.IntegerValue(record.version());
    }

    private void collectRetainedHistory() {
        if (garbageCollector == null) return;
        int removed = garbageCollector.collect(
                (MvccStore) storage, Instant.now(clock), 0);
        metrics.addCounter("history.versions_collected", removed);
        metrics.setGauge("history.last_collection_removed", removed);
    }

    private StorageEngine.HistoricalValue lookup(
            String key, TemporalCommand.Selector selector) {
        return storage.historical(key, selector.version(), selector.timestamp());
    }

    private CommandResult valueResult(
            StorageEngine.HistoricalValue result, TemporalCommand.Selector selector) {
        return switch (result.status()) {
            case FOUND -> isDeleted(result.value(), selector)
                    ? CommandResult.error("ERR key was deleted at requested point")
                    : new CommandResult.BulkString(result.value().value());
            case KEY_NOT_FOUND -> new CommandResult.NullValue();
            case HISTORY_UNAVAILABLE -> unavailable();
        };
    }

    private HistoricalPoint historicalPoint(
            StorageEngine.HistoricalValue result, TemporalCommand.Selector selector) {
        if (result.status() == StorageEngine.HistoricalValue.Status.KEY_NOT_FOUND) {
            return new HistoricalPoint("MISSING", null);
        }
        if (isDeleted(result.value(), selector)) {
            return new HistoricalPoint("DELETED", null);
        }
        return new HistoricalPoint("VALUE", result.value().value());
    }

    private static boolean isDeleted(
            VersionedValue value, TemporalCommand.Selector selector) {
        return value.tombstone()
                || (selector.timestamp() != null
                && !value.isVisibleAt(selector.timestamp()));
    }

    private CommandResult historyEntry(VersionedValue value) {
        CommandResult state = value.tombstone()
                ? CommandResult.simpleString("TOMBSTONE")
                : new CommandResult.BulkString(value.value());
        return new CommandResult.Array(List.of(
                new CommandResult.IntegerValue(value.version()),
                new CommandResult.IntegerValue(value.committedAt().toEpochMilli()),
                state));
    }

    private static int commonPrefix(byte[] before, byte[] after) {
        if (before == null || after == null) return 0;
        int common = 0;
        while (common < before.length
                && common < after.length
                && before[common] == after[common]) {
            common++;
        }
        return common;
    }

    private static CommandResult suffix(byte[] value, int offset) {
        return value == null
                ? new CommandResult.NullValue()
                : new CommandResult.BulkString(
                        Arrays.copyOfRange(value, Math.min(offset, value.length), value.length));
    }

    private static CommandResult.Error unavailable() {
        return CommandResult.error("ERR historical value is no longer retained");
    }

    /** Captures the state and bytes used for a protocol-independent binary diff. */
    private record HistoricalPoint(String state, byte[] value) {
        private HistoricalPoint {
            state = Objects.requireNonNull(state, "state");
            value = value == null ? null : Arrays.copyOf(value, value.length);
        }
        @Override public byte[] value() {
            return value == null ? null : Arrays.copyOf(value, value.length);
        }
    }
}
