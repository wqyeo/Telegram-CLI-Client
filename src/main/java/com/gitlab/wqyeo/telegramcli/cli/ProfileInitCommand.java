package com.gitlab.wqyeo.telegramcli.cli;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gitlab.wqyeo.telegramcli.session.ProfileStore;
import com.gitlab.wqyeo.telegramcli.session.SessionException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "profile-init", mixinStandardHelpOptions = true,
        description = "Explicit Linux profile metadata/lock initialization. No credentials, account client or database.")
public final class ProfileInitCommand implements Callable<Integer> {
    @ParentCommand private RootCommand parent;
    @Spec private CommandSpec spec;
    @Option(names = "--workspace", required = true, paramLabel = "DIRECTORY",
            description = "Existing private absolute data workspace; never created or repaired.")
    private Path workspace;
    @Option(names = "--profile", required = true, paramLabel = "NAME", description = "Explicit new profile name; never overwrite.")
    private String profile;
    @Option(names = "--json", description = "Fixed-schema result with no paths or secret values.") private boolean json;

    @Override public Integer call() throws Exception {
        parent.config();
        try {
            ProfileStore.initialize(workspace, profile);
            if (json) {
                spec.commandLine().getOut().println(JsonMapper.builder().build().writeValueAsString(Map.of(
                        "schemaVersion", 1, "mode", "explicit-profile-init", "status", "ok", "accountDatabaseOpened", false)));
            } else { spec.commandLine().getOut().println("Private profile metadata initialized. No credentials, account client or database created."); }
            return 0;
        } catch (SessionException failure) {
            if (json) {
                spec.commandLine().getOut().println(JsonMapper.builder().build().writeValueAsString(Map.of(
                        "schemaVersion", 1, "mode", "explicit-profile-init", "status", "error", "code", failure.code().name())));
            } else { spec.commandLine().getErr().println(failure.getMessage()); }
            return 1;
        }
    }
}
