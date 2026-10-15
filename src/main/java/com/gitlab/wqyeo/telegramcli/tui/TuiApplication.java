package com.gitlab.wqyeo.telegramcli.tui;

import com.gitlab.wqyeo.telegramcli.application.DemoScenario;
import com.gitlab.wqyeo.telegramcli.application.InteractiveDemoSession;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.ansi.UnixTerminal;

public final class TuiApplication {
    public int run(int historyLimit) throws Exception {
        var terminal = new DefaultTerminalFactory().setForceTextTerminal(true)
                .setPreferTerminalEmulator(false)
                .setUnixTerminalCtrlCBehaviour(UnixTerminal.CtrlCBehaviour.TRAP)
                .createTerminal();
        return run(terminal, DemoScenario.standard(), historyLimit, () -> {});
    }

    /** Injectable terminal/frame hook allow cleanup tests without a real console. */
    public int run(Terminal terminal, DemoScenario scenario, int historyLimit, Runnable frameHook) throws Exception {
        try (terminal; var screen = new TerminalScreen(terminal);
             var session = new InteractiveDemoSession(scenario, historyLimit)) {
            var controller = new DemoController(scenario, session::simulateSubmit);
            var renderer = new DemoRenderer();
            screen.startScreen();
            while (controller.running()) {
                for (int i = 0; i < 128; i++) {
                    var input = screen.pollInput();
                    if (input == null) { break; }
                    controller.handle(input);
                    if (!controller.running()) { break; }
                }
                renderer.render(screen, controller, session.latest());
                screen.refresh();
                frameHook.run();
                if (controller.running()) { Thread.sleep(33); }
            }
            return 0;
        }
    }
}
