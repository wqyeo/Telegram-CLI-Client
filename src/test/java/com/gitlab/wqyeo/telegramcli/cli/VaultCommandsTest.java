package com.gitlab.wqyeo.telegramcli.cli;

import static org.junit.jupiter.api.Assertions.*;
import com.gitlab.wqyeo.telegramcli.session.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** The production Console factory is NEVER selected by these tests. */
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
@Timeout(60)
class VaultCommandsTest {
    @TempDir Path root;
    private Path config, data;
    private static final String PASSWORD = "PUBLIC_SYNTHETIC_DO_NOT_PRINT";
    private record Result(int code, String out, String err) {}
    private static class Input implements CredentialInput {
        final List<char[]> values = new ArrayList<>(); int reads; boolean closed;
        Input(String... fields) { for (String field : fields) { values.add(field == null ? null : field.toCharArray()); } }
        @Override public char[] read(Prompt prompt) { return values.get(reads++); }
        @Override public void close() { closed = true; }
        void erased() {
            assertTrue(closed);
            for (int i = 0; i < reads; i++) { if (values.get(i) != null) { assertArrayEquals(new char[values.get(i).length], values.get(i)); } }
        }
    }
    @BeforeEach void initialize() throws Exception {
        var mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        config = Files.createDirectory(root.resolve("config"), mode); data = Files.createDirectory(root.resolve("data"), mode);
        ProfileStore.initialize(data, "work");
    }
    private CredentialInput.Factory forbidden() { return () -> { fail("Input must not be selected"); return null; }; }
    private Input creation() { return new Input("1", "0".repeat(32), PASSWORD, PASSWORD); }
    private Result run(CredentialInput.Factory factory, String... args) {
        var out = new StringWriter(); var err = new StringWriter();
        var cli = new CommandLine(new RootCommand(factory)); cli.setOut(new PrintWriter(out, true)); cli.setErr(new PrintWriter(err, true));
        cli.setColorScheme(new CommandLine.Help.ColorScheme.Builder().ansi(CommandLine.Help.Ansi.OFF).build());
        int code = cli.execute(args); return new Result(code, out.toString(), err.toString());
    }
    private Result command(CredentialInput.Factory factory, String name, String... extra) {
        var args = new ArrayList<>(List.of(name, "--config-workspace", config.toString(), "--data-workspace", data.toString(), "--profile", "work"));
        args.addAll(List.of(extra)); return run(factory, args.toArray(String[]::new));
    }
    private void safe(Result result) {
        String output = result.out() + result.err(); assertFalse(output.contains(PASSWORD)); assertFalse(output.contains("Exception"));
        assertFalse(output.contains(root.toString()));
    }

    @Test void helpRequiredOptionsAndUnsupportedJsonNeverSelectInput() {
        for (String name : List.of("vault-create", "vault-check")) {
            var help = run(forbidden(), name, "--help"); assertEquals(0, help.code()); assertEquals("", help.err());
            assertTrue(help.out().contains("NEVER login")); assertTrue(help.out().contains("--config-workspace"));
            assertEquals(2, run(forbidden(), name).code()); assertEquals(2, command(forbidden(), name, "--json").code());
        }
        assertEquals(0, run(forbidden(), "--help").code());
        assertEquals(0, run(forbidden(), "diagnostics", "--json").code());
        assertEquals(0, run(forbidden(), "demo", "--json").code());
    }

    @Test void explicitCreateAndUnlockCheckPrintOnlyFixedLocalResults() throws Exception {
        Input create = creation(); Result created = command(() -> create, "vault-create");
        assertEquals(0, created.code()); assertEquals("", created.err()); assertTrue(created.out().contains("No account client or account database created"));
        safe(created); create.erased(); byte[] before = Files.readAllBytes(config.resolve("work.credentials"));
        Input check = new Input(PASSWORD); Result checked = command(() -> check, "vault-check");
        assertEquals(0, checked.code()); assertEquals("", checked.err()); assertTrue(checked.out().contains("unlocked material disposed"));
        safe(checked); check.erased(); assertArrayEquals(before, Files.readAllBytes(config.resolve("work.credentials")));
        try (var files = Files.list(data.resolve("work"))) {
            assertEquals(Set.of("profile.lock", "profile.json"), files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test void missingOrExistingVaultRefusesBeforeInputAndDoesNotExposePaths() throws Exception {
        Result missing = command(forbidden(), "vault-check"); assertEquals(1, missing.code()); safe(missing);
        Input create = creation(); assertEquals(0, command(() -> create, "vault-create").code()); create.erased();
        byte[] before = Files.readAllBytes(config.resolve("work.credentials"));
        Result duplicate = command(forbidden(), "vault-create"); assertEquals(1, duplicate.code()); safe(duplicate);
        assertTrue(duplicate.err().contains("ALREADY_EXISTS")); assertArrayEquals(before, Files.readAllBytes(config.resolve("work.credentials")));
        Result badRoot = run(forbidden(), "vault-create", "--config-workspace", root.resolve("missing").toString(), "--data-workspace", data.toString(), "--profile", "work");
        assertEquals(1, badRoot.code()); safe(badRoot); assertFalse(Files.exists(root.resolve("missing")));
    }

    @Test void unavailableConsoleIsSimulatedWithoutInvokingTheRealFactoryAndEofIsCancellation() {
        var unavailable = command(() -> { throw new SetupException(SetupException.Code.CONSOLE_REQUIRED); }, "vault-create");
        assertEquals(2, unavailable.code()); assertTrue(unavailable.err().contains("CONSOLE_REQUIRED")); safe(unavailable);
        Input eof = new Input((String) null); var cancelled = command(() -> eof, "vault-create");
        assertEquals(130, cancelled.code()); assertTrue(cancelled.err().contains("CANCELLED")); eof.erased(); safe(cancelled);
        assertFalse(Files.exists(config.resolve("work.credentials")));
    }

    @Test void runtimeProbeExercisesVaultCliWithInjectedPublicInputOnly() throws Exception {
        CredentialRuntimeProbe.main(new String[0]);
    }

    @Test void wrongPassphraseAndProviderFailureAreFixedWithoutRetryOrReset() throws Exception {
        Input create = creation(); assertEquals(0, command(() -> create, "vault-create").code()); create.erased();
        byte[] before = Files.readAllBytes(config.resolve("work.credentials"));
        Input wrong = new Input("WRONG_PUBLIC_SYNTHETIC"); var failed = command(() -> wrong, "vault-check");
        assertEquals(1, failed.code()); assertTrue(failed.err().contains("UNLOCK_FAILED")); safe(failed); wrong.erased();
        assertArrayEquals(before, Files.readAllBytes(config.resolve("work.credentials")));
        AtomicInteger calls = new AtomicInteger();
        var provider = command(() -> { calls.incrementAndGet(); throw new IllegalStateException(PASSWORD); }, "vault-check");
        assertEquals(1, provider.code()); assertEquals(1, calls.get()); safe(provider); assertTrue(provider.err().contains("INPUT_UNAVAILABLE"));
        assertArrayEquals(before, Files.readAllBytes(config.resolve("work.credentials")));
    }
}
