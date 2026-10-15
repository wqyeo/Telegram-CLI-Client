package com.gitlab.wqyeo.telegramcli.tui;

import com.gitlab.wqyeo.telegramcli.application.DemoScenario;
import com.gitlab.wqyeo.telegramcli.application.InteractiveDemoSession;
import com.gitlab.wqyeo.telegramcli.model.ClientState;
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
            ClientState renderedState = null;
            while (controller.running()) {
                boolean redraw = screen.doResizeIfNecessary() != null;
                for (int i = 0; i < 128; i++) {
                    var input = screen.pollInput();
                    if (input == null) { break; }
                    redraw = true;
                    controller.handle(input);
                    if (!controller.running()) { break; }
                }
                var state = session.latest();
                if (redraw || state != renderedState) {
                    renderer.render(screen, controller, state);
                    screen.refresh();
                    renderedState = state;
                }
                frameHook.run();
                if (controller.running()) { Thread.sleep(33); }
            }
            return 0;
        }
    }
}
