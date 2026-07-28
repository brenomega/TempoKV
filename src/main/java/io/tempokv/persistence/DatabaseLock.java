package io.tempokv.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Owns the exclusive lock that prevents concurrent writers for one data directory.
 */
public final class DatabaseLock implements AutoCloseable {
    private static final String LOCK_FILE_NAME = ".tempokv.lock";

    private final FileSystemAdapter fileSystem;
    private final Path dataDirectory;
    private FileSystemAdapter.LockedFile lockedFile;

    /** Creates an unopened lock for the supplied data directory. */
    public DatabaseLock(FileSystemAdapter fileSystem, Path dataDirectory) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
    }

    /** Acquires the data-directory lock once for this instance. */
    public synchronized void acquire() throws IOException {
        if (isHeld()) {
            return;
        }
        if (lockedFile != null) {
            close();
        }
        fileSystem.createDirectories(dataDirectory);
        lockedFile = fileSystem.acquireExclusiveLock(dataDirectory.resolve(LOCK_FILE_NAME));
    }

    /** Returns whether this instance currently owns a valid exclusive lock. */
    public synchronized boolean isHeld() {
        return lockedFile != null && lockedFile.isValid();
    }

    /** Releases the lock and its underlying channel. */
    @Override
    public synchronized void close() throws IOException {
        if (lockedFile == null) {
            return;
        }
        FileSystemAdapter.LockedFile current = lockedFile;
        lockedFile = null;
        current.close();
    }
}
