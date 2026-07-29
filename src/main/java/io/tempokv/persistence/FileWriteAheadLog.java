package io.tempokv.persistence;

import io.tempokv.transaction.CommitRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Implements a size-bounded segmented WAL with ordered replay and recoverable torn-tail handling.
 */
public final class FileWriteAheadLog implements WriteAheadLog {
    private static final String SUFFIX = ".wal";
    private static final long DEFAULT_SEGMENT_BYTES = 16L * 1024 * 1024;
    private final Path directory;
    private final FileSystemAdapter fileSystem;
    private final FsyncPolicy fsyncPolicy;
    private final WalRecordCodec codec = new WalRecordCodec();
    private final long maxSegmentBytes;
    private Path activeSegment;
    private long activeSize;
    private long nextSegmentId;
    private long lastVersion;
    private IOException appendFailure;

    /** Opens the WAL with the production 16 MiB segment limit. */
    public FileWriteAheadLog(
            Path dataDirectory,
            FileSystemAdapter fileSystem,
            FsyncPolicy fsyncPolicy) throws IOException {
        this(dataDirectory, fileSystem, fsyncPolicy, DEFAULT_SEGMENT_BYTES);
    }

    /** Opens the WAL with an explicit segment size for deterministic rollover tests. */
    public FileWriteAheadLog(
            Path dataDirectory,
            FileSystemAdapter fileSystem,
            FsyncPolicy fsyncPolicy,
            long maxSegmentBytes) throws IOException {
        if (maxSegmentBytes < 256) {
            throw new IllegalArgumentException("WAL segment size must be at least 256 bytes");
        }
        this.directory = Objects.requireNonNull(dataDirectory, "dataDirectory").resolve("wal");
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        this.fsyncPolicy = Objects.requireNonNull(fsyncPolicy, "fsyncPolicy");
        this.maxSegmentBytes = maxSegmentBytes;
        fileSystem.createDirectories(directory);
        initialize();
    }

    /** Appends every encoded byte, rolls the active segment, and applies the fsync policy. */
    @Override
    public synchronized void append(CommitRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        if (appendFailure != null) {
            throw new IOException("WAL is unavailable after an append failure", appendFailure);
        }
        if (record.version() <= lastVersion) {
            throw new IOException("WAL commit versions must be strictly increasing");
        }
        byte[] encoded = codec.encode(record);
        if (activeSegment == null || (activeSize > 0 && activeSize + encoded.length > maxSegmentBytes)) {
            activeSegment = segment(nextSegmentId++);
            activeSize = 0;
        }
        try {
            try (FileChannel channel = fileSystem.open(
                    activeSegment,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                writeFully(channel, ByteBuffer.wrap(encoded));
                if (fsyncPolicy == FsyncPolicy.ALWAYS) fileSystem.force(channel);
            }
        } catch (IOException failure) {
            appendFailure = failure;
            throw failure;
        }
        activeSize += encoded.length;
        lastVersion = record.version();
    }

    /** Replays ordered complete records segment by segment without materializing the entire WAL. */
    @Override
    public synchronized void replay(Consumer<CommitRecord> consumer) throws IOException {
        Objects.requireNonNull(consumer, "consumer");
        long previous = 0;
        for (Path segment : segments()) {
            SegmentScan scan = scan(segment, record -> {
                consumer.accept(record);
            }, previous);
            previous = scan.lastVersion();
        }
    }

    /**
     * Rewrites partially covered segments and deletes fully covered segments only after replacements
     * have been forced and atomically published.
     */
    @Override
    public synchronized void compactThrough(long version) throws IOException {
        if (version < 0) throw new IllegalArgumentException("Compaction version must not be negative");
        for (Path segment : segments()) {
            List<CommitRecord> records = readSegment(segment);
            List<CommitRecord> retained =
                    records.stream().filter(record -> record.version() > version).toList();
            if (retained.size() == records.size()) continue;
            if (retained.isEmpty()) {
                fileSystem.deleteIfExists(segment);
                fileSystem.forceDirectory(directory);
                continue;
            }
            Path temporary = segment.resolveSibling(segment.getFileName() + ".tmp");
            fileSystem.deleteIfExists(temporary);
            try (FileChannel channel = fileSystem.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                for (CommitRecord record : retained) {
                    writeFully(channel, ByteBuffer.wrap(codec.encode(record)));
                }
                fileSystem.force(channel);
            }
            fileSystem.moveAtomically(temporary, segment);
        }
        initialize();
    }

    /** Returns the total byte size currently occupied by WAL segments. */
    public synchronized long sizeBytes() throws IOException {
        long bytes = 0;
        for (Path segment : segments()) bytes += fileSystem.size(segment);
        return bytes;
    }

    /** No file channel remains open between operations. */
    @Override
    public void close() { }

    private void initialize() throws IOException {
        List<Path> segments = segments();
        lastVersion = 0;
        nextSegmentId = 0;
        activeSegment = null;
        activeSize = 0;
        for (int index = 0; index < segments.size(); index++) {
            Path segment = segments.get(index);
            SegmentScan scan = scan(segment, ignored -> { }, lastVersion);
            if (scan.completeBytes() < fileSystem.size(segment)) {
                if (index != segments.size() - 1) {
                    throw new IOException(
                            "Incomplete WAL record before the final segment");
                }
                try (FileChannel channel = fileSystem.open(segment, StandardOpenOption.WRITE)) {
                    channel.truncate(scan.completeBytes());
                    fileSystem.force(channel);
                }
            }
            lastVersion = scan.lastVersion();
            nextSegmentId = Math.max(nextSegmentId, parseSegmentId(segment) + 1);
            activeSegment = segment;
            activeSize = scan.completeBytes();
        }
    }

    private SegmentScan scan(
            Path segment,
            Consumer<CommitRecord> consumer,
            long previousVersion) throws IOException {
        long segmentBytes = fileSystem.size(segment);
        long maximumBytes = Math.max(maxSegmentBytes, WalRecordCodec.MAX_ENCODED_RECORD_BYTES);
        if (segmentBytes > maximumBytes) {
            throw new IOException("WAL segment exceeds configured maximum size");
        }
        byte[] bytes = fileSystem.readAllBytes(segment);
        int offset = 0;
        long last = previousVersion;
        while (offset < bytes.length) {
            int length = codec.frameLength(bytes, offset);
            if (length == 0) break;
            CommitRecord record =
                    codec.decode(java.util.Arrays.copyOfRange(bytes, offset, offset + length));
            if (record.version() <= last) {
                throw new IOException("WAL commit versions are not strictly ordered");
            }
            consumer.accept(record);
            last = record.version();
            offset += length;
        }
        return new SegmentScan(offset, last);
    }

    private List<CommitRecord> readSegment(Path segment) throws IOException {
        java.util.ArrayList<CommitRecord> records = new java.util.ArrayList<>();
        scan(segment, records::add, 0);
        return List.copyOf(records);
    }

    private List<Path> segments() throws IOException {
        return fileSystem.listRegularFiles(directory, SUFFIX);
    }

    private Path segment(long id) {
        return directory.resolve(String.format("segment-%020d%s", id, SUFFIX));
    }

    private static long parseSegmentId(Path path) throws IOException {
        String name = path.getFileName().toString();
        try {
            return Long.parseLong(name.substring("segment-".length(), name.length() - SUFFIX.length()));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid WAL segment name: " + name, exception);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        int zeroProgressWrites = 0;
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) == 0) {
                if (++zeroProgressWrites == 16) {
                    throw new IOException("File write made no progress");
                }
            } else {
                zeroProgressWrites = 0;
            }
        }
    }

    private record SegmentScan(long completeBytes, long lastVersion) { }
}
