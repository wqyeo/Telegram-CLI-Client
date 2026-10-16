package com.gitlab.wqyeo.telegramcli.session;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Explicit container-only probe, not a JUnit test or production entry point. */
public final class ProfilePermissionProbe {
    private static void refused(Path workspace) throws Exception {
        try (var unexpected = ProfileStore.acquire(workspace, "default")) {
            throw new AssertionError("Wrong-owner storage was accepted.");
        } catch (SessionException exception) {
            if (exception.code() != SessionException.Code.PROFILE_INVALID) { throw exception; }
        }
    }
    public static void main(String[] args) throws Exception {
        if (!"1".equals(System.getenv("TGC_PERMISSION_CONTAINER"))
                || (int) Files.getAttribute(Path.of("/proc/self/status"), "unix:uid") != 0) {
            throw new IllegalStateException("Explicit rootless-container invocation required.");
        }
        Path workspace = Files.createTempDirectory(Path.of("/tmp"), "tgc-permissions-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        ProfileStore.initialize(workspace, "default");
        Path profile = workspace.resolve("default");
        Files.setAttribute(profile, "unix:uid", 1000); refused(workspace);
        Files.setAttribute(profile, "unix:uid", 0);
        Path metadata = profile.resolve("profile.json");
        Files.setAttribute(metadata, "unix:uid", 1000); refused(workspace);
        Files.setAttribute(metadata, "unix:uid", 0);
        Path lock = profile.resolve("profile.lock");
        Files.setAttribute(lock, "unix:uid", 1000); refused(workspace);
        Files.setAttribute(lock, "unix:uid", 0);
        Files.setAttribute(workspace, "unix:uid", 1000); refused(workspace);
        Files.setAttribute(workspace, "unix:uid", 0);
        try (var lease = ProfileStore.acquire(workspace, "default")) {
            if (lease == null) { throw new AssertionError(); }
        }
        Path config = Files.createTempDirectory(Path.of("/tmp"), "tgc-vault-permissions-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path vault = config.resolve("default.credentials");
        Files.createFile(vault, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(vault, new byte[CredentialEnvelope.ENVELOPE_BYTES]); // Deliberately invalid synthetic envelope, no key/KDF.
        var credentials = new CredentialStore(config, workspace);
        Files.setAttribute(vault, "unix:uid", 1000); vaultRefused(credentials);
        Files.setAttribute(vault, "unix:uid", 0);
        Files.setAttribute(config, "unix:uid", 1000); vaultRefused(credentials);
        Files.setAttribute(config, "unix:uid", 0);
        try {
            credentials.open("default", "SYNTHETIC_DO_NOT_PRINT".toCharArray());
            throw new AssertionError("Invalid synthetic envelope was accepted.");
        } catch (CredentialException exception) {
            if (exception.code() != CredentialException.Code.ENVELOPE_INVALID) { throw exception; }
        }
        Path ancestor = Files.createDirectory(workspace.resolve("shared"));
        Files.setPosixFilePermissions(ancestor, PosixFilePermissions.fromString("rwxrwx---"));
        Path nested = Files.createDirectory(ancestor.resolve("private"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            ProfileStore.initialize(nested, "default");
            throw new AssertionError("Writable ancestor was accepted.");
        } catch (SessionException exception) {
            if (exception.code() != SessionException.Code.PROFILE_INVALID) { throw exception; }
        }
        System.out.println("{\"schemaVersion\":1,\"status\":\"ok\",\"wrongProfileOwnerRefused\":true,"
                + "\"wrongMetadataOwnerRefused\":true,\"wrongLockOwnerRefused\":true,"
                + "\"wrongWorkspaceOwnerRefused\":true,\"wrongVaultOwnerRefused\":true,\"wrongConfigOwnerRefused\":true,"
                + "\"writableAncestorRefused\":true,\"accountDatabaseCreated\":false}");
    }
    private static void vaultRefused(CredentialStore store) throws Exception {
        try {
            store.open("default", "SYNTHETIC_DO_NOT_PRINT".toCharArray());
            throw new AssertionError("Wrong-owner credential storage was accepted.");
        } catch (CredentialException exception) {
            if (exception.code() != CredentialException.Code.STORAGE_INVALID) { throw exception; }
        }
    }
}
