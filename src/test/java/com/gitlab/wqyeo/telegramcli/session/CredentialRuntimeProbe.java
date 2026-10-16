package com.gitlab.wqyeo.telegramcli.session;

import com.gitlab.wqyeo.telegramcli.cli.RootCommand;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import picocli.CommandLine;

/** Explicit public-fixture JAR/jlink probe; NEVER selects a genuine input factory. */
public final class CredentialRuntimeProbe {
    private CredentialRuntimeProbe() {}
    public static void main(String[] args) throws Exception {
        char[] passphrase = "SYNTHETIC-ONLY-runtime-unlock".toCharArray();
        byte[] hash = "00000000000000000000000000000000".getBytes(StandardCharsets.US_ASCII);
        byte[] original = null, decoded = null;
        try (var material = CredentialMaterial.generate(1, hash)) {
            byte[] envelope = CredentialEnvelope.seal("synthetic", passphrase, material);
            try (var unlocked = CredentialEnvelope.open("synthetic", passphrase, envelope)) {
                original = material.encode(); decoded = unlocked.encode();
                if (!Arrays.equals(original, decoded)) { throw new IllegalStateException("Synthetic runtime check failed."); }
            }
        } finally {
            Arrays.fill(passphrase, '\0'); Arrays.fill(hash, (byte) 0);
            if (original != null) { Arrays.fill(original, (byte) 0); }
            if (decoded != null) { Arrays.fill(decoded, (byte) 0); }
        }
        checkVaultCommands();
        System.out.println("{\"schemaVersion\":1,\"mode\":\"synthetic-envelope-runtime-check\",\"status\":\"ok\",\"vaultCliChecked\":true,\"injectedPublicInputOnly\":true,\"accountDatabaseCreated\":false}");
    }
    private static void checkVaultCommands() throws Exception {
        Path root = Files.createTempDirectory("tgc-public-vault-runtime-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        byte[] before = null; boolean passed = false;
        try {
            var mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
            Path config = Files.createDirectory(root.resolve("config"), mode), data = Files.createDirectory(root.resolve("data"), mode);
            ProfileStore.initialize(data, "synthetic");
            CredentialInput.Factory forbidden = () -> { throw new AssertionError("Synthetic input must not be selected."); };
            runVault(forbidden, "vault-check", config, data, 1);
            String password = "PUBLIC_SYNTHETIC_RUNTIME_ONLY";
            var create = new PublicInput("1", "0".repeat(32), password, password);
            runVault(() -> create, "vault-create", config, data, 0); create.erased();
            Path vault = config.resolve("synthetic.credentials"); before = Files.readAllBytes(vault);
            require(before.length == 136);
            require(Files.getPosixFilePermissions(vault).equals(PosixFilePermissions.fromString("rw-------")));
            runVault(forbidden, "vault-create", config, data, 1);
            var check = new PublicInput(password);
            runVault(() -> check, "vault-check", config, data, 0); check.erased();
            var wrong = new PublicInput("WRONG_PUBLIC_SYNTHETIC_RUNTIME");
            runVault(() -> wrong, "vault-check", config, data, 1); wrong.erased();
            require(Arrays.equals(before, Files.readAllBytes(vault)));
            try (var files = Files.list(data.resolve("synthetic"))) {
                require(files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet())
                        .equals(Set.of("profile.json", "profile.lock")));
            }
            try (var files = Files.list(config)) { require(files.count() == 1); }
            passed = true;
        } finally {
            if (before != null) { Arrays.fill(before, (byte) 0); }
            if (passed) {
                // Only this successfully verified, freshly allocated PUBLIC fixture. Failed fixtures retained.
                try (var files = Files.walk(root)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) { Files.delete(path); } }
            }
        }
    }
    private static void runVault(CredentialInput.Factory factory, String command, Path config, Path data, int expected) {
        var out = new StringWriter(); var err = new StringWriter();
        var cli = new CommandLine(new RootCommand(factory)); // NEVER the default/genuine Console constructor.
        cli.setOut(new PrintWriter(out, true)); cli.setErr(new PrintWriter(err, true));
        require(cli.execute(command, "--config-workspace", config.toString(), "--data-workspace", data.toString(), "--profile", "synthetic") == expected);
        String result = out.toString() + err;
        require(!result.contains("PUBLIC_SYNTHETIC") && !result.contains(config.toString()) && !result.contains(data.toString()));
        require(!result.contains("Exception"));
    }
    private static final class PublicInput implements CredentialInput {
        private final List<char[]> fields;
        private int reads;
        private boolean closed;
        PublicInput(String... fields) { this.fields = Arrays.stream(fields).map(String::toCharArray).toList(); }
        @Override public char[] read(Prompt prompt) { require(reads < fields.size()); return fields.get(reads++); }
        @Override public void close() { closed = true; }
        void erased() {
            require(closed && reads == fields.size());
            for (char[] field : fields) { for (char value : field) { require(value == '\0'); } }
        }
    }
    private static void require(boolean condition) { if (!condition) { throw new IllegalStateException("Synthetic runtime check failed."); } }
}
