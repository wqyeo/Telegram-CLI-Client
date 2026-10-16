package com.gitlab.wqyeo.telegramcli.session;

import static org.junit.jupiter.api.Assertions.*;
import static com.gitlab.wqyeo.telegramcli.session.CredentialException.Code.*;
import static com.gitlab.wqyeo.telegramcli.session.CredentialEnvelopeTest.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
class CredentialStoreTest {
    @TempDir Path root;
    private Path config, data;
    private CredentialStore store;
    @BeforeEach void setup() throws Exception {
        var mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        config = Files.createDirectory(root.resolve("config"), mode); data = Files.createDirectory(root.resolve("data"), mode);
        ProfileStore.initialize(data, "work"); store = new CredentialStore(config, data);
    }
    @Test void explicitPrivateSeparateCreateUnlockProviderAndNoOverwrite() throws Exception {
        try (var original = material()) { store.create("work", password(), original); }
        Path file = config.resolve("work.credentials"); byte[] before = Files.readAllBytes(file);
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        assertEquals(136, before.length); assertFalse(Files.exists(data.resolve("work.credentials")));
        try (var provider = UnlockedCredentialProvider.open(store, "work", password())) {
            assertEquals("[REDACTED]", provider.toString());
            try (var key = provider.acquire(SecretProvider.Kind.DATABASE_KEY)) { assertArrayEquals(filled(32, 7), key.use(byte[]::clone)); }
            provider.close();
            assertThrows(SecretProvider.Unavailable.class, () -> provider.acquire(SecretProvider.Kind.API_HASH));
        }
        try (var original = material()) { assertEquals(ALREADY_EXISTS, assertThrows(CredentialException.class, () -> store.create("work", password(), original)).code()); }
        assertArrayEquals(before, Files.readAllBytes(file));
    }
    @Test void noImplicitRootsProfilesOrEnvelopeCreationAndNoCoLocation() throws Exception {
        assertThrows(CredentialException.class, () -> store.open("missing", password()));
        try (var original = material()) { assertThrows(CredentialException.class, () -> store.create("missing", password(), original)); }
        assertFalse(Files.exists(data.resolve("missing"))); assertFalse(Files.exists(config.resolve("missing.credentials")));
        assertThrows(CredentialException.class, () -> new CredentialStore(root.resolve("missing"), data));
        assertThrows(CredentialException.class, () -> new CredentialStore(data, data));
        assertThrows(CredentialException.class, () -> new CredentialStore(root, data));
        assertFalse(Files.exists(root.resolve("missing")));
    }
    @Test void refusesLinksBadTypesModesTruncationAndUnsafeRootsWithoutRepair() throws Exception {
        try (var original = material()) { store.create("work", password(), original); }
        Path file = config.resolve("work.credentials"); byte[] encrypted = Files.readAllBytes(file);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--")); refused();
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(file));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.write(file, new byte[137]); refused(); Files.write(file, new byte[1]); refused(); Files.write(file, encrypted);
        Files.move(file, config.resolve("retained")); Files.createSymbolicLink(file, Path.of("retained")); refused();
        assertTrue(Files.isSymbolicLink(file)); Files.delete(file); Files.createDirectory(file); refused();
        Files.delete(file); Files.write(file, encrypted, StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rwxr-x---")); refused();
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rwx------"));
        assertThrows(CredentialException.class, () -> store.open("../DO_NOT_PRINT", password()));
    }
    @Test void wrongUnlockNeverReplacesEnvelopeOrProfileAndActiveLeaseBlocksProvisioning() throws Exception {
        try (var original = material(); var lease = ProfileStore.acquire(data, "work")) {
            assertEquals(STORAGE_INVALID, assertThrows(CredentialException.class, () -> store.create("work", password(), original)).code());
            assertFalse(Files.exists(config.resolve("work.credentials")));
        }
        try (var original = material()) { store.create("work", password(), original); }
        byte[] before = Files.readAllBytes(config.resolve("work.credentials"));
        assertEquals(UNLOCK_FAILED, assertThrows(CredentialException.class,
                () -> store.open("work", "WRONG_SYNTHETIC_DO_NOT_PRINT".toCharArray())).code());
        assertArrayEquals(before, Files.readAllBytes(config.resolve("work.credentials")));
        assertEquals("{\"schemaVersion\":1}\n", Files.readString(data.resolve("work/profile.json")));
    }
    @Test void concurrentProvisioningHasExactlyOneWinnerWithoutReset() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            Callable<Boolean> create = () -> {
                start.await();
                try (var original = material()) { store.create("work", password(), original); return true; }
                catch (CredentialException expected) { return false; }
            };
            var first = executor.submit(create); var second = executor.submit(create); start.countDown();
            assertNotEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
        try (var opened = store.open("work", password())) { assertNotNull(opened); }
    }
    private void refused() {
        var error = assertThrows(CredentialException.class, () -> store.open("work", password()));
        assertEquals(STORAGE_INVALID, error.code()); assertNull(error.getCause()); assertFalse(error.toString().contains("DO_NOT_PRINT"));
    }
}
