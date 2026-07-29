package io.tempokv.persistence;

import io.tempokv.transaction.CommitRecord;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Durable append and replay boundary for ordered commit records. */
public interface WriteAheadLog extends AutoCloseable {
    /** Appends a commit and honors the configured durability policy before returning. */
    void append(CommitRecord record) throws IOException;

    /** Streams valid records in order, ignoring only an incomplete final record. */
    void replay(Consumer<CommitRecord> consumer) throws IOException;

    /** Materializes replay records for diagnostics and bounded tests. */
    default List<CommitRecord> replay() throws IOException {
        List<CommitRecord> records = new ArrayList<>();
        replay(records::add);
        return List.copyOf(records);
    }

    /** Deletes WAL data covered by a validated durable snapshot. */
    void compactThrough(long version) throws IOException;

    /** Releases any open WAL channel. */
    @Override void close() throws IOException;
}
