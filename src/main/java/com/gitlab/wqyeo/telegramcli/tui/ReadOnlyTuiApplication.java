package com.gitlab.wqyeo.telegramcli.tui;

import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import com.gitlab.wqyeo.telegramcli.application.SyntheticReadOnlySession;
import com.gitlab.wqyeo.telegramcli.application.ReadOnlyPreviewSession;
import com.gitlab.wqyeo.telegramcli.session.bridge.*;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.*;
import com.googlecode.lanterna.terminal.ansi.UnixTerminal;

/** One terminal reader: fixture-only guide first, then a separate composer-free fullscreen surface. */
public final class ReadOnlyTuiApplication {
    public int run(SyntheticReadOnlySession.Scenario scenario) throws Exception {
        try (var session = new SyntheticReadOnlySession(scenario, java.time.Clock.systemUTC(), System::nanoTime)) {
            var terminal = new DefaultTerminalFactory().setForceTextTerminal(true).setPreferTerminalEmulator(false)
                    .setUnixTerminalCtrlCBehaviour(UnixTerminal.CtrlCBehaviour.TRAP).createTerminal();
            return run(terminal, session, () -> {});
        }
    }
    /** Owns terminal/session. The hook injects fixture events/keys only; no genuine Console input. */
    public int run(Terminal terminal, ReadOnlyPreviewSession session, Runnable frameHook) throws Exception {
        try (terminal; session) {
            if (!guide(terminal, session, frameHook)) { return 0; }
            var renderer = new ReadOnlyRenderer();
            try (var screen = new TerminalScreen(terminal)) {
                var ui = new ReadOnlyController(session); screen.startScreen();
                BridgeCoordinator.Snapshot rendered = null;
                while (ui.running()) {
                    boolean redraw = screen.doResizeIfNecessary() != null;
                    for (int i = 0; i < 128; i++) {
                        var input = screen.pollInput(); if (input == null) { break; }
                        redraw = true; ui.handle(input); if (!ui.running()) { break; }
                    }
                    var state = session.tick(); ui.sync();
                    if (redraw || state != rendered) { renderer.render(screen, ui); screen.refresh(); rendered = state; }
                    frameHook.run(); if (ui.running()) { Thread.sleep(33); }
                }
                return 0;
            } finally { renderer.purge(); }
        }
    }
    private boolean guide(Terminal terminal, ReadOnlyPreviewSession session, Runnable frameHook) throws Exception {
        String rendered = null, notice = "";
        while (!viewReady(session.latest()) && !session.latest().disposed()) {
            var state = session.tick();
            if (state.retryAfterSeconds() == 0) { notice = ""; }
            String text = guideText(state, notice);
            if (!text.equals(rendered)) {
                // putString is for printable text: the virtual backend rejects CR/LF.
                // Use the terminal's newline operation and an explicit column reset,
                // rather than depending on the operator's output CR/LF translation.
                for (String line : text.split("\r\n", -1)) {
                    terminal.putString(line); terminal.putCharacter('\n');
                    terminal.setCursorPosition(0, terminal.getCursorPosition().getRow());
                }
                terminal.flush(); rendered = text;
            }
            for (int i = 0; i < 128; i++) {
                var key = terminal.pollInput(); if (key == null) { break; }
                if (ReadOnlyController.quits(key)) { return false; }
                if (key.getKeyType() == KeyType.Enter) {
                    if (state.auth() != null && state.auth().step().input() && state.pendingRequests() == 0) {
                        try { session.advanceAuthorization(); notice = ""; }
                        catch (BridgeException failure) {
                            if (failure.code() != BridgeException.Code.TIMEOUT) { throw failure; }
                            notice = "Retry delay active; no attempt was sent.";
                        }
                    }
                    break; // One explicit fixture step per input/render iteration.
                }
                // All other typed characters are ignored, never collected or echoed as credentials.
            }
            frameHook.run(); if (!viewReady(session.latest())) { Thread.sleep(33); }
        }
        return viewReady(session.latest());
    }
    private static boolean viewReady(BridgeCoordinator.Snapshot state) {
        return !state.disposed() && state.auth() != null && state.auth().step() == Step.READY && state.identity() != null;
    }
    private static String guideText(BridgeCoordinator.Snapshot state, String notice) {
        String step;
        if (state.auth() == null) { step = "Fixture authorization unavailable/expired. Quit and restart explicitly."; }
        else {
            step = switch (state.auth().step()) {
                case PHONE -> "Phone number step (public fixture, not collected).";
                case CODE -> "Login code step (public fixture, not collected).";
                case EMAIL -> "Email address step (public fixture, not collected).";
                case EMAIL_CODE -> "Email verification code step (public fixture, not collected).";
                case PASSWORD -> "Two-step verification password step (public fixture, not collected).";
                case RETRY -> "Retry delay: no automatic resubmission.";
                case UNSUPPORTED -> "Unsupported fixture flow: " + state.auth().unsupported().name() + ". Nothing accepted.";
                case CLOSED -> "Fixture flow closed. Quit and restart explicitly.";
                case READY -> "Fixture ready.";
            };
        }
        return "SYNTHETIC AUTH PREVIEW - NOT CONNECTED\r\n" + step
                + "\r\nNever type credentials. " + (state.auth() != null && state.auth().step().input()
                    ? "Enter uses one public fixture; Q/Ctrl+C/EOF quits." : "No next step is supported. Q/Ctrl+C/EOF quits.")
                + (state.retryAfterSeconds() > 0 ? "\r\nRetry delay active. Wait, then press Enter explicitly." : "")
                + (notice.isEmpty() ? "" : "\r\n" + notice);
    }
}
