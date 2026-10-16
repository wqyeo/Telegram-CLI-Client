package com.gitlab.wqyeo.telegramcli.session;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Public synthetic values only. Never selects ConsoleCredentialInput or a genuine provider. */
@Timeout(60)
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
class CredentialSetupTest {
    private static final String PASSWORD = "PUBLIC_SYNTHETIC_PASSPHRASE", MARKER = "DO_NOT_PRINT_SYNTHETIC";
    @TempDir Path root;
    private Path config, data, vault;
    private CredentialSetup setup;
    @BeforeEach void initialize() throws Exception {
        var mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        config = Files.createDirectory(root.resolve("config"), mode); data = Files.createDirectory(root.resolve("data"), mode);
        ProfileStore.initialize(data, "work"); vault = config.resolve("work.credentials"); setup = new CredentialSetup(config, data, "work");
    }
    private static class Input implements CredentialInput {
        final List<char[]> values = new ArrayList<>(), returned = new ArrayList<>();
        final List<Prompt> prompts = new ArrayList<>();
        int position; boolean closed, failRead, failClose;
        Input(String... text) { for (String value : text) { values.add(value == null ? null : value.toCharArray()); } }
        @Override public char[] read(Prompt prompt) {
            prompts.add(prompt);
            if (failRead) { throw new IllegalArgumentException(MARKER); }
            char[] value = values.get(position++); returned.add(value); return value;
        }
        @Override public void close() { closed = true; if (failClose) { throw new IllegalStateException(MARKER); } }
        void erased() {
            assertTrue(closed);
            for (char[] value : returned) { if (value != null) { assertArrayEquals(new char[value.length], value); } }
        }
    }
    private Input creation() { return new Input("1", "0".repeat(32), PASSWORD, PASSWORD); }
    private void create() throws Exception { Input input = creation(); setup.create(() -> input); input.erased(); }
    private void safe(Throwable failure) { assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length); assertFalse(failure.toString().contains(MARKER)); }
    private CredentialInput.Factory forbidden() { return () -> { fail("Input must not be selected before preflight"); return null; }; }

    @Test void explicitCreationAndCheckDisposeInputAndNeverCreateAccountFiles() throws Exception {
        Input input = creation(); setup.create(() -> input); input.erased();
        assertEquals(List.of(CredentialInput.Prompt.API_ID, CredentialInput.Prompt.API_HASH,
                CredentialInput.Prompt.PASSPHRASE, CredentialInput.Prompt.CONFIRM), input.prompts);
        assertEquals(136, Files.size(vault)); assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(vault));
        byte[] encrypted = Files.readAllBytes(vault); Input check = new Input(PASSWORD); setup.check(() -> check); check.erased();
        assertEquals(List.of(CredentialInput.Prompt.PASSPHRASE), check.prompts); assertArrayEquals(encrypted, Files.readAllBytes(vault));
        try (var files = Files.list(data.resolve("work"))) {
            assertEquals(Set.of("profile.json", "profile.lock"), files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        assertEquals("[REDACTED local setup]", setup.toString());
    }

    @Test void cancelAtEveryPromptAndConfirmationMismatchNeverPersistOrRetry() throws Exception {
        for (int cancelled = 0; cancelled < 4; cancelled++) {
            Input input = creation(); input.values.set(cancelled, null);
            var failure = assertThrows(SetupException.class, () -> setup.create(() -> input));
            assertEquals(SetupException.Code.CANCELLED, failure.code()); safe(failure); input.erased(); assertFalse(Files.exists(vault));
            assertEquals(cancelled + 1, input.prompts.size());
        }
        Input mismatch = new Input("1", "0".repeat(32), PASSWORD, "DIFFERENT_PUBLIC_SYNTHETIC");
        assertEquals(SetupException.Code.INVALID_INPUT, assertThrows(SetupException.class, () -> setup.create(() -> mismatch)).code());
        mismatch.erased(); assertFalse(Files.exists(vault));
    }

    @Test void acceptedInputBoundsAsciiIdentifiersAndHashAreStrictAndErased() {
        for (String id : new String[] {"", "0", "-1", "+1", "2147483648", "1".repeat(11), "١"}) {
            Input input = new Input(id);
            assertEquals(SetupException.Code.INVALID_INPUT, assertThrows(SetupException.class, () -> setup.create(() -> input)).code()); input.erased();
        }
        for (String hash : new String[] {"0".repeat(31), "0".repeat(33), "A".repeat(32), "g".repeat(32), "你".repeat(32)}) {
            Input input = new Input("1", hash);
            assertEquals(SetupException.Code.INVALID_INPUT, assertThrows(SetupException.class, () -> setup.create(() -> input)).code()); input.erased();
        }
        for (String pass : new String[] {"x".repeat(11), "x".repeat(1025)}) {
            Input input = new Input("1", "0".repeat(32), pass);
            assertEquals(SetupException.Code.INVALID_INPUT, assertThrows(SetupException.class, () -> setup.create(() -> input)).code()); input.erased();
        }
        assertFalse(Files.exists(vault));
    }

    @Test void unicodeIsStrictlyEncodedAndNeverNormalized() throws Exception {
        String invalid = "x".repeat(12) + "\ud800";
        Input invalidInput = new Input("1", "0".repeat(32), invalid, invalid);
        assertEquals(CredentialException.Code.INVALID_INPUT, assertThrows(CredentialException.class, () -> setup.create(() -> invalidInput)).code());
        invalidInput.erased(); assertFalse(Files.exists(vault));
        String pass = "e\u0301🙂".repeat(4); Input valid = new Input("2147483647", "a".repeat(32), pass, pass);
        setup.create(() -> valid); valid.erased();
        Input differentNormalization = new Input("é🙂".repeat(4));
        assertEquals(CredentialException.Code.UNLOCK_FAILED, assertThrows(CredentialException.class, () -> setup.check(() -> differentNormalization)).code());
        differentNormalization.erased();
        Input check = new Input(pass); setup.check(() -> check); check.erased();
    }

    @Test void preflightRefusesMissingUnsafeBusyAndExistingStorageBeforeSelectingInput() throws Exception {
        assertThrows(CredentialException.class, () -> new CredentialSetup(root.resolve("missing"), data, "work").create(forbidden()));
        assertFalse(Files.exists(root.resolve("missing")));
        assertThrows(CredentialException.class, () -> new CredentialSetup(config, data, "missing").create(forbidden()));
        assertFalse(Files.exists(data.resolve("missing")));
        assertThrows(CredentialException.class, () -> new CredentialSetup(data, data, "work").create(forbidden()));
        assertThrows(CredentialException.class, () -> setup.check(forbidden()));
        try (var lease = ProfileStore.acquire(data, "work")) { assertThrows(CredentialException.class, () -> setup.create(forbidden())); }
        create(); assertEquals(CredentialException.Code.ALREADY_EXISTS, assertThrows(CredentialException.class, () -> setup.create(forbidden())).code());
        Files.setPosixFilePermissions(vault, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(CredentialException.class, () -> setup.check(forbidden()));
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(vault));
        Files.setPosixFilePermissions(vault, PosixFilePermissions.fromString("rw-------"));
        Files.move(vault, config.resolve("retained")); Files.createSymbolicLink(vault, Path.of("retained"));
        assertThrows(CredentialException.class, () -> setup.check(forbidden())); assertTrue(Files.isSymbolicLink(vault));
    }

    @Test void wrongUnlockIsFixedAndNeverResetsVaultOrProfile() throws Exception {
        create(); byte[] before = Files.readAllBytes(vault), metadata = Files.readAllBytes(data.resolve("work/profile.json"));
        Input wrong = new Input("WRONG_PUBLIC_SYNTHETIC");
        var failure = assertThrows(CredentialException.class, () -> setup.check(() -> wrong));
        assertEquals(CredentialException.Code.UNLOCK_FAILED, failure.code()); safe(failure); wrong.erased();
        assertArrayEquals(before, Files.readAllBytes(vault)); assertArrayEquals(metadata, Files.readAllBytes(data.resolve("work/profile.json")));
    }

    @Test void factoryReadAndSuppressedCloseErrorsCannotEscapeWithInputSnippets() {
        AtomicInteger opens = new AtomicInteger();
        var factoryFailure = assertThrows(SetupException.class, () -> setup.create(() -> { opens.incrementAndGet(); throw new IllegalStateException(MARKER); }));
        safe(factoryFailure); assertEquals(1, opens.get()); assertEquals(SetupException.Code.INPUT_UNAVAILABLE, factoryFailure.code());
        Input input = creation(); input.failRead = true; input.failClose = true;
        var readFailure = assertThrows(SetupException.class, () -> setup.create(() -> input)); safe(readFailure); input.erased();
        Input cancelled = new Input((String) null); cancelled.failClose = true;
        var cancellation = assertThrows(SetupException.class, () -> setup.create(() -> cancelled));
        assertEquals(SetupException.Code.CANCELLED, cancellation.code()); safe(cancellation); cancelled.erased();
        assertFalse(Files.exists(vault));
    }

    @Test void vaultAppearingAfterPreflightIsRetainedWithoutOverwrite() throws Exception {
        byte[] raced = new byte[136]; Arrays.fill(raced, (byte) 7); Input input = creation();
        var failure = assertThrows(CredentialException.class, () -> setup.create(() -> {
            try {
                Files.createFile(vault, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.write(vault, raced);
            } catch (java.io.IOException unexpected) { throw new AssertionError(unexpected); }
            return input;
        }));
        assertEquals(CredentialException.Code.ALREADY_EXISTS, failure.code()); input.erased(); assertArrayEquals(raced, Files.readAllBytes(vault));
    }

    @Test void checkReacquiresTheProfileAfterHumanInputWithoutBreakingAContendersLease() throws Exception {
        create(); byte[] before = Files.readAllBytes(vault);
        ProfileStore.Lease[] contender = {null}; Input input = new Input(PASSWORD);
        try {
            var failure = assertThrows(CredentialException.class, () -> setup.check(() -> {
                try { contender[0] = ProfileStore.acquire(data, "work"); }
                catch (SessionException unexpected) { throw new AssertionError(unexpected); }
                return input;
            }));
            assertEquals(CredentialException.Code.STORAGE_INVALID, failure.code()); input.erased(); safe(failure);
            assertArrayEquals(before, Files.readAllBytes(vault));
            assertThrows(SessionException.class, () -> ProfileStore.acquire(data, "work"));
        } finally { if (contender[0] != null) { contender[0].close(); } }
        Input check = new Input(PASSWORD); setup.check(() -> check); check.erased();
    }

    @Test void closeFailureAfterSuccessfulWriteDoesNotDeleteOrReplaceTheVault() throws Exception {
        Input input = creation(); input.failClose = true;
        var failure = assertThrows(SetupException.class, () -> setup.create(() -> input)); safe(failure); input.erased();
        assertEquals(SetupException.Code.INPUT_UNAVAILABLE, failure.code()); assertEquals(136, Files.size(vault));
        Input check = new Input(PASSWORD); setup.check(() -> check); check.erased();
    }
}
