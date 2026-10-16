package com.gitlab.wqyeo.telegramcli.cli;

import com.gitlab.wqyeo.telegramcli.tui.TuiApplication;
import com.gitlab.wqyeo.telegramcli.tui.ReadOnlyTuiApplication;
import com.gitlab.wqyeo.telegramcli.application.SyntheticReadOnlySession;
import picocli.CommandLine.ArgGroup;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(name = "tui", mixinStandardHelpOptions = true,
        description = "Interactive synthetic terminal demo. No account, persistence, or real sending.")
public final class TuiCommand implements Callable<Integer> {
    @ParentCommand private RootCommand parent;
    @Spec private CommandSpec spec;
    static final class Mode {
        @Option(names = "--demo", description = "Existing offline composer/send simulation; never sends a real message.") boolean demo;
        @Option(names = "--read-only-demo", description = "Synthetic authorization guide, then bounded composer-free read-only preview.") boolean readOnly;
    }
    @ArgGroup(exclusive = true, multiplicity = "1") private Mode mode;
    @Option(names = "--auth-scenario", description = "Read-only fixture flow only: ${COMPLETION-CANDIDATES}.")
    private SyntheticReadOnlySession.Scenario scenario;

    @Override
    public Integer call() throws Exception {
        if (mode == null || mode.demo == mode.readOnly) {
            spec.commandLine().getErr().println("Choose exactly one explicit --demo or --read-only-demo mode."); return 2;
        }
        if (mode.demo && scenario != null) {
            spec.commandLine().getErr().println("--auth-scenario requires --read-only-demo."); return 2;
        }
        if (System.console() == null || !System.console().isTerminal()) {
            spec.commandLine().getErr().println("tui " + (mode.readOnly ? "--read-only-demo" : "--demo")
                    + " requires an interactive terminal; use demo --json otherwise.");
            return 2;
        }
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            spec.commandLine().getErr().println("Native Windows TUI backend is not yet available; no graphical fallback was started.");
            return 1;
        }
        var config = parent.config();
        return mode.readOnly ? new ReadOnlyTuiApplication().run(scenario == null ? SyntheticReadOnlySession.Scenario.FULL : scenario)
                : new TuiApplication().run(config.historyLimit());
    }
}
