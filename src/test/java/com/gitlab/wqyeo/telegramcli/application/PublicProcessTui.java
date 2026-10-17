package com.gitlab.wqyeo.telegramcli.application;

import com.gitlab.wqyeo.telegramcli.session.bridge.ProcessBridgeTransport;
import com.gitlab.wqyeo.telegramcli.tui.ReadOnlyTuiApplication;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.ansi.UnixTerminal;
import java.util.UUID;

/** Test-only PTY entry point into the SAME accepted UI, not a shipped command/demo surface. */
public final class PublicProcessTui {
    public static void main(String[] args) {
        int result = 1;
        try {
            if (args.length != 4 || !args[0].equals("tui") || !args[1].equals("--read-only-demo") || !args[2].equals("--auth-scenario")
                    || !System.getProperty("os.name", "").equals("Linux") || System.console() == null || !System.console().isTerminal()) {
                throw new IllegalArgumentException();
            }
            var scenario = SyntheticReadOnlySession.Scenario.valueOf(args[3]);
            var process = PublicProcessHarness.launch(scenario, "NORMAL");
            try (var session = new ProcessReadOnlySession(process, UUID.randomUUID(), ProcessBridgeTransport.Budgets.defaults())) {
                var terminal = new DefaultTerminalFactory().setForceTextTerminal(true).setPreferTerminalEmulator(false)
                        .setUnixTerminalCtrlCBehaviour(UnixTerminal.CtrlCBehaviour.TRAP).createTerminal();
                result = new ReadOnlyTuiApplication().run(terminal, session, () -> {});
            }
        } catch (Exception | LinkageError failure) { System.err.println("PUBLIC process preview failed."); }
        System.exit(result);
    }
}
