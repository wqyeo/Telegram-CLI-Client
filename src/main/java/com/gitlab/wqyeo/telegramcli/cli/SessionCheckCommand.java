package com.gitlab.wqyeo.telegramcli.cli;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gitlab.wqyeo.telegramcli.session.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "session-check", mixinStandardHelpOptions = true,
        description = "Account-free worker lease/JNI/closure check. No login, account database or Telegram connection.")
public final class SessionCheckCommand implements Callable<Integer> {
    @ParentCommand private RootCommand parent;
    @Spec private CommandSpec spec;
    @Option(names = "--workspace", required = true, paramLabel = "DIRECTORY", description = "Existing private absolute data workspace.")
    private Path workspace;
    @Option(names = "--profile", required = true, paramLabel = "NAME", description = "Existing explicitly initialized profile; never created here.")
    private String profile;
    @Option(names = "--bundle", paramLabel = "DIRECTORY", description = "Manifest-verified native bundle, or the installed bundle by default.")
    private Path bundle;
    @Option(names = "--json", description = "Fixed-schema account-free outcome; no worker output or paths.") private boolean json;
    @Option(names = "--hold-seconds", paramLabel = "0..30", description = "Keep the account-free fixture running before closure for lock/parent-loss checks (default: 0).")
    private int holdSeconds;

    @Override public Integer call() throws Exception {
        parent.config();
        if (holdSeconds < 0 || holdSeconds > 30) {
            if (json) {
                spec.commandLine().getOut().println("{\"schemaVersion\":1,\"mode\":\"account-free-worker-check\",\"status\":\"error\",\"code\":\"INVALID_ARGUMENT\"}");
            } else { spec.commandLine().getErr().println("Hold duration must be between 0 and 30 seconds."); }
            return 2;
        }
        try {
            if (!SessionWorker.integrated()) { throw new SessionException(SessionException.Code.NATIVE_NOT_BUILT); }
            if (bundle == null) {
                String installed = System.getProperty("tgc.native.bundle");
                if (installed == null || installed.isBlank()) { throw new SessionException(SessionException.Code.BUNDLE_INVALID); }
                bundle = Path.of(installed);
            }
            try (var supervisor = SessionWorker.startAccountFree(bundle, workspace, profile)) {
                if (holdSeconds > 0) { Thread.sleep(java.time.Duration.ofSeconds(holdSeconds)); }
                supervisor.close();
                if (supervisor.latest().lifecycle() != SessionState.Lifecycle.CLOSED) {
                    throw new SessionException(SessionException.Code.CLOSE_FAILED);
                }
            }
            if (json) {
                spec.commandLine().getOut().println(JsonMapper.builder().build().writeValueAsString(Map.of(
                        "schemaVersion", 1, "mode", "account-free-worker-check", "status", "ok",
                        "workerClosed", true, "tdlibClientCreated", false, "accountDatabaseOpened", false,
                        "telegramConnectionMade", false)));
            } else {
                spec.commandLine().getOut().println("Account-free worker check OK: private lease, fixed JNI probe and worker closure. Not TDLib account Close.");
            }
            return 0;
        } catch (SessionException failure) {
            if (json) {
                spec.commandLine().getOut().println(JsonMapper.builder().build().writeValueAsString(Map.of(
                        "schemaVersion", 1, "mode", "account-free-worker-check", "status", "error", "code", failure.code().name())));
            } else { spec.commandLine().getErr().println(failure.getMessage()); }
            return 1;
        }
    }
}
