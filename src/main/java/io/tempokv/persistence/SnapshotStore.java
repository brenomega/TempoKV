package io.tempokv.persistence;

import io.tempokv.storage.StorageSnapshot;
import io.tempokv.storage.TtlIndex;
import io.tempokv.storage.VersionChain;
import io.tempokv.storage.VersionedValue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * Publishes versioned, checksummed snapshots atomically and falls back to the newest valid artifact.
 */
public final class SnapshotStore {
    private static final int MAGIC = 0x544B5350;
    private static final short FORMAT_VERSION = 1;
    private static final String SUFFIX = ".snapshot";
    private final Path directory;
    private final FileSystemAdapter fileSystem;
    private final int maxSnapshotBytes;
    private final long maxEncodedBytes;

    /** Stores snapshots under the node data directory using injectable filesystem operations. */
    public SnapshotStore(Path dataDirectory, FileSystemAdapter fileSystem) {
        this(dataDirectory, fileSystem, 64L * 1_048_576);
    }

    /** Stores snapshots with an explicit bounded payload size. */
    public SnapshotStore(
            Path dataDirectory,
            FileSystemAdapter fileSystem,
            long maxSnapshotBytes) {
        this.directory = Objects.requireNonNull(dataDirectory, "dataDirectory").resolve("snapshots");
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        if (maxSnapshotBytes < 1 || maxSnapshotBytes > Integer.MAX_VALUE - 14L) {
            throw new IllegalArgumentException(
                    "maxSnapshotBytes is outside the supported range");
        }
        this.maxSnapshotBytes = Math.toIntExact(maxSnapshotBytes);
        this.maxEncodedBytes = maxSnapshotBytes + 14L;
    }

    /** Forces a complete temporary artifact before atomically publishing and validating it. */
    public synchronized void save(StorageSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        fileSystem.createDirectories(directory);
        Path target = path(snapshot.version());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        fileSystem.deleteIfExists(temporary);
        try {
            writeTemporary(snapshot, temporary);
            StorageSnapshot validated = decode(readSnapshot(temporary));
            if (validated.version() != snapshot.version()) {
                throw new IOException(
                        "Temporary snapshot cutoff changed during validation");
            }
            fileSystem.moveAtomically(temporary, target);
            pruneOldSnapshots();
        } catch (IOException | RuntimeException failure) {
            try {
                fileSystem.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Returns the newest structurally and cryptographically valid snapshot, ignoring invalid newer
     * artifacts so recovery can fall back to an older snapshot or the complete WAL.
     */
    public synchronized Optional<StorageSnapshot> load() throws IOException {
        List<Path> candidates = snapshotsNewestFirst();
        IOException failures = null;
        for (Path candidate : candidates) {
            try {
                return Optional.of(decode(readSnapshot(candidate)));
            } catch (IOException | RuntimeException exception) {
                IOException failure = exception instanceof IOException io
                        ? io
                        : new IOException("Invalid snapshot " + candidate.getFileName(), exception);
                if (failures == null) failures = failure;
                else failures.addSuppressed(failure);
            }
        }
        if (!candidates.isEmpty()) {
            throw new IOException("No valid snapshot could be loaded", failures);
        }
        return Optional.empty();
    }

    /** Encodes a validated snapshot for transfer over the replication protocol. */
    public synchronized byte[] encodeForTransfer(StorageSnapshot snapshot)
            throws IOException {
        return encode(Objects.requireNonNull(snapshot, "snapshot"));
    }

    /** Decodes and validates a snapshot received from a trusted replication connection. */
    public synchronized StorageSnapshot decodeTransfer(byte[] encoded)
            throws IOException {
        return decode(Objects.requireNonNull(encoded, "encoded"));
    }

    /** Returns the oldest retained valid snapshot cutoff safe for conservative WAL compaction. */
    public synchronized long safeCompactionVersion() throws IOException {
        List<StorageSnapshot> valid = new ArrayList<>();
        for (Path candidate : snapshotsNewestFirst()) {
            try {
                valid.add(decode(readSnapshot(candidate)));
            } catch (IOException | RuntimeException ignored) {
                // Invalid artifacts are deliberately excluded from the compaction watermark.
            }
        }
        if (valid.size() < 2) return 0;
        return valid.getLast().version();
    }

    private byte[] encode(StorageSnapshot snapshot) throws IOException {
        SnapshotBuffer result = new SnapshotBuffer();
        DataOutputStream output = new DataOutputStream(result);
        try {
            output.writeInt(MAGIC);
            output.writeShort(FORMAT_VERSION);
            output.writeInt(0);
            BoundedChecksumOutputStream bounded =
                    new BoundedChecksumOutputStream(result, maxSnapshotBytes);
            DataOutputStream payload = new DataOutputStream(bounded);
            writePayload(payload, snapshot);
            payload.flush();
            output.writeInt((int) bounded.checksum());
            output.flush();
            result.putInt(6, bounded.count());
            return result.toByteArray();
        } catch (IOException failure) {
            throw failure;
        }
    }

    private void writeTemporary(
            StorageSnapshot snapshot, Path temporary) throws IOException {
        try (FileChannel channel = fileSystem.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            DataOutputStream header =
                    new DataOutputStream(Channels.newOutputStream(channel));
            header.writeInt(MAGIC);
            header.writeShort(FORMAT_VERSION);
            header.writeInt(0);
            header.flush();
            BoundedChecksumOutputStream bounded =
                    new BoundedChecksumOutputStream(
                            Channels.newOutputStream(channel),
                            maxSnapshotBytes);
            DataOutputStream payload = new DataOutputStream(bounded);
            writePayload(payload, snapshot);
            payload.flush();
            DataOutputStream trailer =
                    new DataOutputStream(Channels.newOutputStream(channel));
            trailer.writeInt((int) bounded.checksum());
            trailer.flush();
            channel.position(6);
            writeFully(channel, ByteBuffer.allocate(Integer.BYTES)
                    .putInt(bounded.count())
                    .flip());
            fileSystem.force(channel);
        }
    }

    private static void writePayload(
            DataOutputStream output, StorageSnapshot snapshot)
            throws IOException {
        output.writeLong(snapshot.version());
        output.writeInt(snapshot.chains().size());
        for (Map.Entry<String, VersionChain> entry
                : snapshot.chains().entrySet()) {
            writeBytes(
                    output,
                    entry.getKey().getBytes(StandardCharsets.UTF_8));
            StorageSnapshot.HistoryBoundary boundary =
                    snapshot.boundaries().get(entry.getKey());
            output.writeLong(boundary.firstVersion());
            output.writeLong(boundary.firstCommittedAt().toEpochMilli());
            output.writeBoolean(boundary.truncated());
            output.writeInt(entry.getValue().versions().size());
            for (VersionedValue version : entry.getValue().versions()) {
                writeVersion(output, version);
            }
        }
        output.writeInt(snapshot.expirations().size());
        for (TtlIndex.Entry entry : snapshot.expirations()) {
            writeBytes(
                    output, entry.key().getBytes(StandardCharsets.UTF_8));
            output.writeLong(entry.version());
            output.writeLong(entry.expiresAt().toEpochMilli());
        }
    }

    private StorageSnapshot decode(byte[] encoded) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid snapshot magic");
            if (input.readShort() != FORMAT_VERSION) {
                throw new IOException("Unsupported snapshot format version");
            }
            int length = input.readInt();
            if (length < 0 || length > maxSnapshotBytes
                    || input.available() != length + Integer.BYTES) {
                throw new IOException("Invalid snapshot payload length");
            }
            byte[] payload = readExact(input, length);
            int expected = input.readInt();
            CRC32 checksum = new CRC32();
            checksum.update(payload);
            if ((int) checksum.getValue() != expected) {
                throw new IOException("Snapshot checksum mismatch");
            }
            return decodePayload(payload);
        }
    }

    private StorageSnapshot decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            long cutoff = input.readLong();
            int keyCount = boundedCount(input.readInt(), "snapshot key");
            Map<String, VersionChain> chains = new HashMap<>();
            Map<String, StorageSnapshot.HistoryBoundary> boundaries = new HashMap<>();
            for (int keyIndex = 0; keyIndex < keyCount; keyIndex++) {
                String key = new String(readBytes(input), StandardCharsets.UTF_8);
                StorageSnapshot.HistoryBoundary boundary =
                        new StorageSnapshot.HistoryBoundary(
                                input.readLong(),
                                Instant.ofEpochMilli(input.readLong()),
                                input.readBoolean());
                int versionCount = boundedCount(input.readInt(), "snapshot version");
                if (versionCount == 0) throw new IOException("Snapshot chain must not be empty");
                List<VersionedValue> versions = new ArrayList<>(versionCount);
                for (int index = 0; index < versionCount; index++) {
                    versions.add(readVersion(input));
                }
                if (chains.put(key, VersionChain.fromNewestFirst(versions)) != null) {
                    throw new IOException("Duplicate snapshot key");
                }
                boundaries.put(key, boundary);
            }
            int expirationCount = boundedCount(input.readInt(), "snapshot expiration");
            List<TtlIndex.Entry> expirations = new ArrayList<>(expirationCount);
            for (int index = 0; index < expirationCount; index++) {
                expirations.add(new TtlIndex.Entry(
                        new String(readBytes(input), StandardCharsets.UTF_8),
                        input.readLong(),
                        Instant.ofEpochMilli(input.readLong())));
            }
            if (input.available() != 0) throw new IOException("Unexpected snapshot payload bytes");
            return new StorageSnapshot(cutoff, chains, boundaries, expirations);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid snapshot state", exception);
        }
    }

    private void pruneOldSnapshots() throws IOException {
        List<Path> snapshots = snapshotsNewestFirst();
        for (int index = 2; index < snapshots.size(); index++) {
            fileSystem.deleteIfExists(snapshots.get(index));
        }
        fileSystem.forceDirectory(directory);
    }

    private List<Path> snapshotsNewestFirst() throws IOException {
        List<Path> snapshots = new ArrayList<>(fileSystem.listRegularFiles(directory, SUFFIX));
        snapshots.sort(java.util.Comparator.reverseOrder());
        return snapshots;
    }

    private byte[] readSnapshot(Path path) throws IOException {
        if (fileSystem.size(path) > maxEncodedBytes) {
            throw new IOException("Snapshot artifact exceeds maximum encoded size");
        }
        return fileSystem.readAllBytes(path);
    }

    private Path path(long version) {
        return directory.resolve(String.format("snapshot-%020d%s", version, SUFFIX));
    }

    private static void writeVersion(DataOutputStream output, VersionedValue value)
            throws IOException {
        output.writeLong(value.version());
        output.writeBoolean(value.tombstone());
        output.writeLong(value.committedAt().toEpochMilli());
        output.writeBoolean(value.expiresAt() != null);
        if (value.expiresAt() != null) output.writeLong(value.expiresAt().toEpochMilli());
        output.writeBoolean(value.restoredFromVersion() != null);
        if (value.restoredFromVersion() != null) output.writeLong(value.restoredFromVersion());
        output.writeBoolean(value.tombstoneReason() != null);
        if (value.tombstoneReason() != null) output.writeByte(value.tombstoneReason().ordinal());
        writeNullableBytes(output, value.value());
    }

    private VersionedValue readVersion(DataInputStream input) throws IOException {
        long version = input.readLong();
        boolean tombstone = input.readBoolean();
        Instant committedAt = Instant.ofEpochMilli(input.readLong());
        Instant expiresAt =
                input.readBoolean() ? Instant.ofEpochMilli(input.readLong()) : null;
        Long restoredFrom = input.readBoolean() ? input.readLong() : null;
        VersionedValue.TombstoneReason reason = null;
        if (input.readBoolean()) {
            int ordinal = input.readUnsignedByte();
            if (ordinal >= VersionedValue.TombstoneReason.values().length) {
                throw new IOException("Invalid snapshot tombstone reason");
            }
            reason = VersionedValue.TombstoneReason.values()[ordinal];
        }
        return new VersionedValue(
                version,
                readNullableBytes(input),
                tombstone,
                committedAt,
                expiresAt,
                restoredFrom,
                reason);
    }

    private static int boundedCount(int value, String field) throws IOException {
        if (value < 0 || value > 1_000_000) throw new IOException("Invalid " + field + " count");
        return value;
    }

    private static void writeNullableBytes(DataOutputStream output, byte[] value)
            throws IOException {
        output.writeInt(value == null ? -1 : value.length);
        if (value != null) output.write(value);
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private byte[] readNullableBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        return length == -1 ? null : readSized(input, length);
    }

    private byte[] readBytes(DataInputStream input) throws IOException {
        return readSized(input, input.readInt());
    }

    private byte[] readSized(DataInputStream input, int length) throws IOException {
        if (length < 0 || length > maxSnapshotBytes) {
            throw new IOException("Invalid snapshot field length");
        }
        return readExact(input, length);
    }

    private static byte[] readExact(DataInputStream input, int length) throws IOException {
        byte[] value = input.readNBytes(length);
        if (value.length != length) throw new IOException("Truncated snapshot field");
        return value;
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        int zeroProgressWrites = 0;
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) > 0) {
                zeroProgressWrites = 0;
            } else if (++zeroProgressWrites >= 16) {
                throw new IOException("Snapshot write made no progress");
            }
        }
    }

    private static final class BoundedChecksumOutputStream
            extends OutputStream {
        private final OutputStream delegate;
        private final int maximum;
        private final CRC32 checksum = new CRC32();
        private int count;

        private BoundedChecksumOutputStream(
                OutputStream delegate, int maximum) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.maximum = maximum;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
            checksum.update(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length)
                throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
            checksum.update(bytes, offset, length);
            count += length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        private void ensureCapacity(int additional) throws IOException {
            if (additional > maximum - count) {
                throw new IOException(
                        "Snapshot payload exceeds configured limit");
            }
        }

        private int count() {
            return count;
        }

        private long checksum() {
            return checksum.getValue();
        }
    }

    private static final class SnapshotBuffer
            extends ByteArrayOutputStream {
        private void putInt(int offset, int value) {
            buf[offset] = (byte) (value >>> 24);
            buf[offset + 1] = (byte) (value >>> 16);
            buf[offset + 2] = (byte) (value >>> 8);
            buf[offset + 3] = (byte) value;
        }
    }
}
