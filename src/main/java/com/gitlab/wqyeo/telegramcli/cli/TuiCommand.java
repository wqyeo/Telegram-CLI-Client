package com.gitlab.wqyeo.telegramcli.cli;

import com.gitlab.wqyeo.telegramcli.tui.TuiApplication;
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
    @Option(names = "--demo", required = true, description = "Required: acknowledge the offline simulation.")
    private boolean demo;

    @Override
    public Integer call() throws Exception {
        if (!demo || System.console() == null || !System.console().isTerminal()) {
            spec.commandLine().getErr().println("tui --demo requires an interactive terminal; use demo --json otherwise.");
            return 2;
        }
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            spec.commandLine().getErr().println("Native Windows TUI backend is not yet available; no graphical fallback was started.");
            return 1;
        }
        return new TuiApplication().run(parent.config().historyLimit());
    }
}
