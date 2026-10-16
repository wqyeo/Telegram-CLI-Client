package com.gitlab.wqyeo.telegramcli.cli;

import com.gitlab.wqyeo.telegramcli.session.*;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

/** Interactive operator-local storage only, deliberately not a login/unlocked-session command. */
public final class VaultCommands {
    private VaultCommands() {}
    abstract static class LocalVault implements Callable<Integer> {
        @ParentCommand private RootCommand parent;
        @Spec private CommandSpec spec;
        @Option(names = "--config-workspace", required = true, paramLabel = "DIRECTORY",
                description = "Existing private absolute configuration workspace; never created/repaired.")
        private Path configWorkspace;
        @Option(names = "--data-workspace", required = true, paramLabel = "DIRECTORY",
                description = "Existing, disjoint private data workspace containing the initialized profile.")
        private Path dataWorkspace;
        @Option(names = "--profile", required = true, paramLabel = "NAME", description = "Explicit existing profile; initialize separately.")
        private String profile;
        private final boolean creating;
        LocalVault(boolean creating) { this.creating = creating; }
        @Override public Integer call() {
            parent.config();
            try {
                var setup = new CredentialSetup(configWorkspace, dataWorkspace, profile);
                if (creating) { setup.create(parent.credentialInput()); }
                else { setup.check(parent.credentialInput()); }
                spec.commandLine().getOut().println(creating
                        ? "Encrypted vault created with a fresh independent database key. No account client or account database created."
                        : "Vault authenticated; unlocked material disposed. No account client or account database opened.");
                return 0;
            } catch (CredentialException failure) {
                spec.commandLine().getErr().println(failure.getMessage());
                return failure.code() == CredentialException.Code.INVALID_INPUT ? 2 : 1;
            } catch (SetupException failure) {
                spec.commandLine().getErr().println(failure.getMessage());
                return switch (failure.code()) {
                    case CANCELLED -> 130;
                    case CONSOLE_REQUIRED, INVALID_INPUT -> 2;
                    case INPUT_UNAVAILABLE -> 1;
                };
            }
        }
    }
    @Command(name = "vault-create", mixinStandardHelpOptions = true,
            description = "Explicit local encrypted vault creation, NEVER login. Refuses existing vaults. Hidden interactive input only.",
            footer = {"Never supply plaintext credentials through argv, environment, input files or logs. No JSON/noninteractive input mode.",
                    "Java Console buffers are not hard-bounded or perfectly erased; accepted input is validated and owned arrays wiped.",
                    "No automatic retry, repair or reset. A failed write can leave a private vault; it is never deleted/replaced automatically."})
    public static final class Create extends LocalVault { public Create() { super(true); } }
    @Command(name = "vault-check", mixinStandardHelpOptions = true,
            description = "Explicit local vault unlock-check, NEVER login. Immediately disposes unlocked material; no persistent unlocked session.",
            footer = {"Hidden interactive Java Console input only. No credential argv/env/files/JSON input.",
                    "Accepted input bounds and best-effort wiping do not guarantee bounds/erasure of JDK internal buffers.",
                    "Wrong passphrase/corruption never resets a vault/profile or starts reauthorization."})
    public static final class Check extends LocalVault { public Check() { super(false); } }
}
