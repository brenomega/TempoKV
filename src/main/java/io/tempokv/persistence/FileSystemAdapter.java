package io.tempokv.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.OpenOption;
import java.util.List;
import java.util.Objects;

/**
 * Isolates filesystem operations needed by TempoKV infrastructure.
 */
public class FileSystemAdapter {

    /** Returns whether a path exists without following a final symbolic link. */
    public boolean exists(Path path) {
        return Files.exists(normalize(path, "path"), LinkOption.NOFOLLOW_LINKS);
    }

    /** Returns the size of a regular file. */
    public long size(Path path) throws IOException {
        return Files.size(normalize(path, "path"));
    }

    /** Reads a complete bounded infrastructure artifact. */
    public byte[] readAllBytes(Path path) throws IOException {
        return Files.readAllBytes(normalize(path, "path"));
    }

    /** Opens a file channel so tests can inject append, sync, and close failures. */
    public FileChannel open(Path path, OpenOption... options) throws IOException {
        return FileChannel.open(normalize(path, "path"), options);
    }

    /** Lists regular direct children with the requested suffix in lexical order. */
    public List<Path> listRegularFiles(Path directory, String suffix) throws IOException {
        Path normalized = normalize(directory, "directory");
        if (!exists(normalized)) return List.of();
        try (var paths = Files.list(normalized)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    /** Deletes one exact infrastructure file when it exists. */
    public boolean deleteIfExists(Path path) throws IOException {
        return Files.deleteIfExists(normalize(path, "path"));
    }

    /** Creates a real directory without accepting a symbolic link as its final path. */
    public void createDirectories(Path directory) throws IOException {
        Path normalized = normalize(directory, "directory");
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectory(normalized, "Directory");
            return;
        }

        Files.createDirectories(normalized);
        requireDirectory(normalized, "Created path");
    }

    /** Acquires an exclusive operating-system lock and returns its closeable handle. */
    public LockedFile acquireExclusiveLock(Path lockFile) throws IOException {
        Path normalized = normalize(lockFile, "lock file");
        Path parent = normalized.getParent();
        if (parent != null) {
            createDirectories(parent);
        }

        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(normalized)
                    || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Lock file must be a regular file: " + normalized);
            }
        }

        FileChannel channel = FileChannel.open(
                normalized,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("Data directory is already locked: " + normalized);
            }
            return new LockedFile(channel, lock);
        } catch (OverlappingFileLockException exception) {
            IOException failure = new IOException("Data directory is already locked: " + normalized, exception);
            closeAfterFailure(channel, failure);
            throw failure;
        } catch (IOException exception) {
            closeAfterFailure(channel, exception);
            throw exception;
        } catch (RuntimeException exception) {
            closeAfterFailure(channel, exception);
            throw exception;
        }
    }

    /** Moves a completed temporary file atomically into its final location. */
    public void moveAtomically(Path source, Path target) throws IOException {
        Path normalizedSource = normalize(source, "source");
        Path normalizedTarget = normalize(target, "target");
        if (!Files.isRegularFile(normalizedSource, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalizedSource)) {
            throw new IOException("Source must be a regular file: " + normalizedSource);
        }
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(normalizedTarget)
                || !Files.isRegularFile(normalizedTarget, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Target must be a regular file: " + normalizedTarget);
        }

        Path parent = normalizedTarget.getParent();
        if (parent != null) {
            createDirectories(parent);
        }
        try {
            Files.move(normalizedSource, normalizedTarget,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic move is not supported between "
                    + normalizedSource + " and " + normalizedTarget, exception);
        }
        forceDirectory(parent);
    }

    /** Forces file content and metadata to stable storage. */
    public void force(FileChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        if (!channel.isOpen()) {
            throw new IOException("Cannot force a closed file channel");
        }
        channel.force(true);
    }

    /** Forces directory metadata after publishing or deleting durable artifacts. */
    public void forceDirectory(Path directory) throws IOException {
        if (directory == null) return;
        try (FileChannel channel = FileChannel.open(normalize(directory, "directory"), StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    /** Normalizes a required path before it reaches the filesystem. */
    private static Path normalize(Path path, String name) {
        Objects.requireNonNull(path, name);
        return path.toAbsolutePath().normalize();
    }

    /** Verifies that the final path is a real directory. */
    private static void requireDirectory(Path path, String description) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " must be a non-symbolic directory: " + path);
        }
    }

    /** Preserves the original failure while releasing an opened channel. */
    private static void closeAfterFailure(FileChannel channel, Throwable failure) {
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /** Owns a file channel and its exclusive lock until it is closed. */
    public static final class LockedFile implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private LockedFile(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        /** Returns whether the operating-system lock remains valid. */
        public boolean isValid() {
            return lock.isValid() && channel.isOpen();
        }

        /** Releases the lock and always attempts to close its channel. */
        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
