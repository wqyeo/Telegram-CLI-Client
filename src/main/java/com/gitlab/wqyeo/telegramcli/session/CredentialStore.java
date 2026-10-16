package com.gitlab.wqyeo.telegramcli.session;

import static com.gitlab.wqyeo.telegramcli.session.CredentialException.Code.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Explicit encrypted storage only; not called by existing CLI or any live session. */
public final class CredentialStore {
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rw-------");
    private final Path configWorkspace;
    private final Path dataWorkspace;

    public CredentialStore(Path configWorkspace, Path dataWorkspace) throws CredentialException {
        this.configWorkspace = configWorkspace; this.dataWorkspace = dataWorkspace;
        try (var config = ProfileStore.workspace(configWorkspace); var data = ProfileStore.workspace(dataWorkspace)) {
            if (configWorkspace.startsWith(dataWorkspace) || dataWorkspace.startsWith(configWorkspace)
                    || config.getFileAttributeView(PosixFileAttributeView.class).readAttributes().fileKey()
                    .equals(data.getFileAttributeView(PosixFileAttributeView.class).readAttributes().fileKey())) {
                throw new CredentialException(STORAGE_INVALID);
            }
        } catch (IOException | RuntimeException exception) { throw new CredentialException(STORAGE_INVALID); }
    }

    public void create(String profile, char[] passphrase, CredentialMaterial material) throws CredentialException {
        byte[] encrypted = null;
        try (var lease = ProfileStore.acquire(dataWorkspace, profile);
             var directory = ProfileStore.workspace(configWorkspace)) {
            Path name = filename(profile);
            // Fail existing files before deriving; never replace, truncate or repair them.
            try {
                directory.getFileAttributeView(name, BasicFileAttributeView.class, NOFOLLOW_LINKS).readAttributes();
                throw new CredentialException(ALREADY_EXISTS);
            } catch (NoSuchFileException expected) { /* initial creation only */ }
            encrypted = CredentialEnvelope.seal(profile, passphrase, material);
            try (var channel = directory.newByteChannel(name,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PRIVATE))) {
                var buffer = ByteBuffer.wrap(encrypted);
                while (buffer.hasRemaining()) { channel.write(buffer); }
                if (!(channel instanceof FileChannel file)) { throw new CredentialException(STORAGE_INVALID); }
                file.force(true);
            }
        } catch (CredentialException exception) { throw exception; }
        catch (FileAlreadyExistsException exception) { throw new CredentialException(ALREADY_EXISTS); }
        catch (IOException | RuntimeException exception) { throw new CredentialException(STORAGE_INVALID); }
        finally { if (encrypted != null) { Arrays.fill(encrypted, (byte) 0); } }
    }

    public CredentialMaterial open(String profile, char[] passphrase) throws CredentialException {
        byte[] encrypted = new byte[CredentialEnvelope.ENVELOPE_BYTES];
        try (var directory = ProfileStore.workspace(configWorkspace)) {
            Path name = filename(profile);
            var before = attributes(directory, name);
            var bytes = ByteBuffer.allocate(CredentialEnvelope.ENVELOPE_BYTES + 1);
            try (var channel = directory.newByteChannel(name, Set.of(StandardOpenOption.READ, NOFOLLOW_LINKS))) {
                while (bytes.hasRemaining() && channel.read(bytes) != -1) { /* bounded */ }
            }
            if (bytes.position() != encrypted.length || before.fileKey() == null
                    || !before.fileKey().equals(attributes(directory, name).fileKey())) {
                throw new CredentialException(STORAGE_INVALID);
            }
            bytes.flip(); bytes.get(encrypted);
            return CredentialEnvelope.open(profile, passphrase, encrypted);
        } catch (CredentialException exception) { throw exception; }
        catch (IOException | RuntimeException exception) { throw new CredentialException(STORAGE_INVALID); }
        finally { Arrays.fill(encrypted, (byte) 0); }
    }

    private static Path filename(String profile) throws CredentialException {
        if (profile == null || !profile.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) { throw new CredentialException(INVALID_INPUT); }
        return Path.of(profile + ".credentials");
    }

    private static PosixFileAttributes attributes(SecureDirectoryStream<Path> directory, Path name) throws IOException {
        var attributes = directory.getFileAttributeView(name, PosixFileAttributeView.class, NOFOLLOW_LINKS).readAttributes();
        if (!attributes.isRegularFile() || !attributes.permissions().equals(PRIVATE)
                || !attributes.owner().equals(Files.getOwner(Path.of("/proc/self/status")))
                || attributes.size() != CredentialEnvelope.ENVELOPE_BYTES) { throw new CredentialException(STORAGE_INVALID); }
        return attributes;
    }
}
