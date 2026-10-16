package com.gitlab.wqyeo.telegramcli.session;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static com.gitlab.wqyeo.telegramcli.session.SessionException.Code.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Linux-only, explicit storage primitives. Never called by existing commands. */
public final class ProfileStore {
    private static final Path METADATA = Path.of("profile.json");
    private static final Path LOCK = Path.of("profile.lock");
    private static final byte[] SCHEMA = "{\"schemaVersion\":1}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final Set<PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
    // Candidates are bounded local filesystems, not a claim of validation on each one.
    private static final Set<String> LOCAL = Set.of("ext2", "ext3", "ext4", "xfs", "btrfs", "tmpfs", "overlay");
    private static final Set<Object> HELD = new HashSet<>();

    private ProfileStore() {}

    /** Workspace must already exist, be private, and have a trusted ancestor chain. */
    public static void initialize(Path workspace, String name) throws SessionException {
        try {
            validateName(name);
            try (var root = workspace(workspace)) {
                Path profile = workspace.resolve(name);
                // No createDirectories, chmod, repair, implicit migration or recursive deletion.
                Files.createDirectory(profile, PosixFilePermissions.asFileAttribute(DIRECTORY));
                try (var directory = root.newDirectoryStream(Path.of(name), NOFOLLOW_LINKS)) {
                    checkDirectory(directory);
                    create(directory, METADATA, SCHEMA);
                    create(directory, LOCK, new byte[0]);
                }
            }
        } catch (SessionException exception) { throw exception; }
        catch (IOException | RuntimeException exception) { throw new SessionException(PROFILE_INVALID); }
    }

    public static Lease acquire(Path workspace, String name) throws SessionException {
        try {
            validateName(name);
            try (var root = workspace(workspace);
                 var directory = root.newDirectoryStream(Path.of(name), NOFOLLOW_LINKS)) {
                checkDirectory(directory);
                var metadata = checkFile(directory, METADATA);
                if (metadata.size() != SCHEMA.length) { throw new SessionException(PROFILE_INVALID); }
                try (var channel = directory.newByteChannel(METADATA, Set.of(StandardOpenOption.READ, NOFOLLOW_LINKS))) {
                    var data = ByteBuffer.allocate(SCHEMA.length + 1);
                    while (data.hasRemaining() && channel.read(data) != -1) { /* bounded */ }
                    if (data.position() != SCHEMA.length || !Arrays.equals(SCHEMA, Arrays.copyOf(data.array(), data.position()))) {
                        throw new SessionException(PROFILE_INVALID);
                    }
                }
                var attributes = checkFile(directory, LOCK);
                if (attributes.size() != 0 || attributes.fileKey() == null) { throw new SessionException(PROFILE_INVALID); }
                Object key = attributes.fileKey();
                synchronized (HELD) {
                    // Opening then closing a second channel can release an existing POSIX lock!
                    if (HELD.contains(key)) { throw new SessionException(PROFILE_BUSY); }
                    var raw = directory.newByteChannel(LOCK, Set.of(StandardOpenOption.WRITE, NOFOLLOW_LINKS));
                    if (!(raw instanceof FileChannel channel)) {
                        raw.close();
                        throw new SessionException(STORAGE_UNSUPPORTED);
                    }
                    try {
                        if (!key.equals(checkFile(directory, LOCK).fileKey())) { throw new SessionException(PROFILE_INVALID); }
                        FileLock lock = channel.tryLock();
                        if (lock == null) { throw new SessionException(PROFILE_BUSY); }
                        HELD.add(key);
                        return new Lease(key, channel, lock);
                    } catch (OverlappingFileLockException exception) {
                        channel.close();
                        throw new SessionException(PROFILE_BUSY);
                    } catch (IOException | RuntimeException exception) {
                        channel.close();
                        throw exception;
                    }
                }
            }
        } catch (SessionException exception) { throw exception; }
        catch (IOException | RuntimeException exception) { throw new SessionException(PROFILE_INVALID); }
    }

    private static void validateName(String name) throws SessionException {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new SessionException(PROFILE_INVALID);
        }
    }

    private static SecureDirectoryStream<Path> workspace(Path path) throws IOException {
        if (!System.getProperty("os.name").equalsIgnoreCase("linux")) { throw new SessionException(STORAGE_UNSUPPORTED); }
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) { throw new SessionException(PROFILE_INVALID); }
        int uid = (int) Files.getAttribute(Path.of("/proc/self/status"), "unix:uid");
        // Refuse symlink ancestors and writable non-sticky ancestors. Root-owned sticky
        // temporary directories are allowed, but the workspace itself must be private.
        Path current = path.getRoot();
        for (Path component : path) {
            current = current.resolve(component);
            var attributes = Files.readAttributes(current, "unix:uid,mode", NOFOLLOW_LINKS);
            int owner = (int) attributes.get("uid");
            int mode = (int) attributes.get("mode");
            if ((mode & 0170000) != 0040000 || (owner != uid && owner != 0)
                    || ((mode & 0022) != 0 && !(owner == 0 && (mode & 01000) != 0))) {
                throw new SessionException(PROFILE_INVALID);
            }
        }
        if (!LOCAL.contains(Files.getFileStore(path).type())) { throw new SessionException(STORAGE_UNSUPPORTED); }
        var before = Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
        if (!before.permissions().equals(DIRECTORY) || !before.owner().equals(Files.getOwner(Path.of("/proc/self/status")))) {
            throw new SessionException(PROFILE_INVALID);
        }
        var stream = Files.newDirectoryStream(path);
        if (!(stream instanceof SecureDirectoryStream<Path> secure)) {
            stream.close();
            throw new SessionException(STORAGE_UNSUPPORTED);
        }
        try {
            checkDirectory(secure);
            if (before.fileKey() == null || !before.fileKey().equals(secure.getFileAttributeView(PosixFileAttributeView.class).readAttributes().fileKey())) {
                throw new SessionException(PROFILE_INVALID);
            }
            return secure;
        } catch (IOException | RuntimeException exception) { secure.close(); throw exception; }
    }

    private static void checkDirectory(SecureDirectoryStream<Path> directory) throws IOException {
        var view = directory.getFileAttributeView(PosixFileAttributeView.class);
        if (view == null) { throw new SessionException(STORAGE_UNSUPPORTED); }
        var attributes = view.readAttributes();
        if (!attributes.isDirectory() || !attributes.permissions().equals(DIRECTORY)
                || !attributes.owner().equals(Files.getOwner(Path.of("/proc/self/status")))) {
            throw new SessionException(PROFILE_INVALID);
        }
    }

    private static PosixFileAttributes checkFile(SecureDirectoryStream<Path> directory, Path name) throws IOException {
        var attributes = directory.getFileAttributeView(name, PosixFileAttributeView.class, NOFOLLOW_LINKS).readAttributes();
        if (!attributes.isRegularFile() || !attributes.permissions().equals(FILE)
                || !attributes.owner().equals(Files.getOwner(Path.of("/proc/self/status")))) {
            throw new SessionException(PROFILE_INVALID);
        }
        return attributes;
    }

    private static void create(SecureDirectoryStream<Path> directory, Path name, byte[] data) throws IOException {
        try (var channel = directory.newByteChannel(name,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(FILE))) {
            var buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) { channel.write(buffer); }
        }
    }

    /** Stable inode and one channel; the database-owning worker must hold this lease. */
    public static final class Lease implements AutoCloseable {
        private final Object key;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;
        private Lease(Object key, FileChannel channel, FileLock lock) {
            this.key = key; this.channel = channel; this.lock = lock;
        }
        @Override public void close() throws SessionException {
            synchronized (HELD) {
                if (closed) { return; }
                try {
                    lock.release();
                    channel.close();
                    closed = true;
                    HELD.remove(key);
                } catch (IOException exception) { throw new SessionException(CLOSE_FAILED); }
            }
        }
    }
}
