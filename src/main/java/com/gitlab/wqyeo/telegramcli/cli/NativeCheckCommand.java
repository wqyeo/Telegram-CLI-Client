package com.gitlab.wqyeo.telegramcli.cli;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gitlab.wqyeo.telegramcli.telegram.NativeProbe;
import com.gitlab.wqyeo.telegramcli.telegram.NativeProbeException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(name = "native-check", mixinStandardHelpOptions = true,
        description = "Account-free TDLib GetTextEntities probe. No login, client creation, or database.")
public final class NativeCheckCommand implements Callable<Integer> {
    @ParentCommand private RootCommand parent;
    @Spec private CommandSpec spec;
    @Option(names = "--bundle", paramLabel = "DIRECTORY", description = "Locally built, manifest-verified native bundle.")
    private Path bundle;
    @Option(names = "--json", description = "Print a schema-versioned result, including sanitized failures.")
    private boolean json;

    @Override
    public Integer call() throws Exception {
        parent.config();
        var mapper = JsonMapper.builder().build();
        try {
            if (bundle == null) {
                String installationBundle = System.getProperty("tgc.native.bundle");
                if (installationBundle == null || installationBundle.isBlank()) {
                    throw new NativeProbeException("bundle-required", "Specify --bundle DIRECTORY or use a self-contained distribution.");
                }
                bundle = Path.of(installationBundle);
            }
            var report = new NativeProbe().run(bundle);
            spec.commandLine().getOut().println(json ? mapper.writeValueAsString(report)
                    : "TDLib native probe OK (synthetic UTF-16 entities; no account or Telegram connection).");
            return 0;
        } catch (NativeProbeException exception) {
            if (json) {
                spec.commandLine().getOut().println(mapper.writeValueAsString(Map.of("schemaVersion", 1,
                        "mode", "account-free-native-probe", "status", "error", "code", exception.code(),
                        "message", exception.getMessage())));
            } else { spec.commandLine().getErr().println(exception.getMessage()); }
            return 1;
        }
    }
}
