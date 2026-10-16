package com.gitlab.wqyeo.telegramcli.tui;

import static org.junit.jupiter.api.Assertions.*;
import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import com.gitlab.wqyeo.telegramcli.application.SyntheticReadOnlySession;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.*;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ReadOnlyRendererTest {
    private static class TrackingTerminal extends DefaultVirtualTerminal {
        int clears, characters, flushes, entries; boolean closed, privateMode;
        Runnable enterCheck = () -> {};
        final StringBuilder written = new StringBuilder();
        TrackingTerminal() { super(new TerminalSize(100, 30)); }
        @Override public void enterPrivateMode() { enterCheck.run(); entries++; privateMode = true; super.enterPrivateMode(); }
        @Override public void exitPrivateMode() { privateMode = false; super.exitPrivateMode(); }
        @Override public void clearScreen() { clears++; super.clearScreen(); }
        @Override public void putString(String text) { characters += text.length(); written.append(text); super.putString(text); }
        @Override public void flush() { flushes++; super.flush(); }
        @Override public void close() { closed = true; super.close(); }
    }
    private SyntheticReadOnlySession ready() throws Exception {
        var session = new SyntheticReadOnlySession(); for (int i = 0; i < 5; i++) { session.advanceAuthorization(); } return session;
    }
    private String grid(TerminalScreen screen) {
        var result = new StringBuilder(); var size = screen.getTerminalSize();
        for (int y = 0; y < size.getRows(); y++) {
            var row = new StringBuilder();
            for (int x = 0; x < size.getColumns(); x++) { row.append(screen.getBackCharacter(x, y).getCharacterString()); }
            result.append(row.toString().stripTrailing()).append('\n');
        }
        return result.toString();
    }
    private void capture(TerminalScreen screen, String name) throws Exception {
        Path directory = Path.of("target/readonly-ui-review"); Files.createDirectories(directory);
        Files.writeString(directory.resolve(name + ".txt"), grid(screen), StandardCharsets.UTF_8);
    }

    @Test void incumbentLayoutsUnicodeAndControlSafetyHaveNoComposerOrSendAffordance() throws Exception {
        try (var session = ready(); var terminal = new TrackingTerminal(); var screen = new TerminalScreen(terminal)) {
            screen.startScreen(); var ui = new ReadOnlyController(session); var renderer = new ReadOnlyRenderer();
            for (var size : new TerminalSize[] {new TerminalSize(100, 30), new TerminalSize(80, 16), new TerminalSize(50, 14),
                    new TerminalSize(40, 10), new TerminalSize(12, 4)}) {
                terminal.setTerminalSize(size); renderer.render(screen, ui); screen.refresh();
                capture(screen, size.getColumns() + "x" + size.getRows() + "-chats");
                String content = grid(screen); assertFalse(content.contains("Composer")); assertFalse(content.contains("simulate send"));
                assertFalse(content.contains("\u001b")); assertFalse(content.contains("\u0007")); assertFalse(content.contains("\u202e"));
                assertTrue(content.contains(size.getColumns() < 40 ? "Q/Ctrl+C" : "SYNTHETIC"));
                if (size.getColumns() >= 80) { assertEquals("|", screen.getBackCharacter(24, 2).getCharacterString()); }
            }
            terminal.setTerminalSize(new TerminalSize(50, 14)); ui.handle(new KeyStroke(KeyType.Tab));
            renderer.render(screen, ui); screen.refresh(); capture(screen, "50x14-history");
            assertTrue(grid(screen).contains("Saved Messages"));
            ui.handle(new KeyStroke(KeyType.Escape)); ui.handle(new KeyStroke(KeyType.ArrowDown)); ui.handle(new KeyStroke(KeyType.Enter));
            renderer.render(screen, ui); screen.refresh(); capture(screen, "50x14-list-only");
            assertTrue(grid(screen).contains("listed only")); assertEquals(0, renderer.cachedLineCount());
            ui.handle(new KeyStroke(KeyType.F1)); renderer.render(screen, ui); screen.refresh(); capture(screen, "50x14-help");
        }
    }

    @Test void helpIsCompleteAtTheMinimumAndNarrowSizesIncludingPagingAndLimitations() throws Exception {
        try (var session = ready(); var terminal = new TrackingTerminal(); var screen = new TerminalScreen(terminal)) {
            screen.startScreen(); var ui = new ReadOnlyController(session); var renderer = new ReadOnlyRenderer();
            ui.handle(new KeyStroke(KeyType.F1));
            for (var size : new TerminalSize[] {new TerminalSize(40, 10), new TerminalSize(50, 14), new TerminalSize(80, 16)}) {
                terminal.setTerminalSize(size); renderer.render(screen, ui); screen.refresh(); String content = grid(screen);
                assertTrue(content.contains("SYNTHETIC READ-ONLY HELP"));
                assertTrue(content.contains("PgUp/PgDn")); assertTrue(content.contains("Tab/Shift+Tab"));
                if (size.getColumns() < 80) {
                    for (String instruction : new String[] {"Q/Ctrl+C/EOF quit; F1/Esc close",
                            "Tab/Shift+Tab focus; Enter history", "Esc chats; arrows select/scroll",
                            "PgUp/PgDn scroll; Home/End edges", "N more/older; B newer; R refresh",
                            "Saved text; no send/edit/delete/media", "No live/read/presence/encryption tests"}) {
                        assertTrue(content.contains(instruction), "Complete help instruction missing: " + instruction);
                    }
                } else { assertTrue(content.contains("No real account, read/presence or encryption behavior tested")); }
                if (size.getColumns() != 50) { capture(screen, size.getColumns() + "x" + size.getRows() + "-help"); }
            }
            ui.handle(new KeyStroke(KeyType.Escape)); assertFalse(ui.help());
            renderer.render(screen, ui); assertFalse(grid(screen).contains("SYNTHETIC READ-ONLY HELP"));
            ui.handle(new KeyStroke(KeyType.F1)); ui.handle(new KeyStroke('q', false, false)); assertFalse(ui.running());
        }
    }

    @Test void repeatedFramesUseChangedCellsWithoutPhysicalClearOrTextRewrite() throws Exception {
        try (var session = ready(); var terminal = new TrackingTerminal(); var screen = new TerminalScreen(terminal)) {
            screen.startScreen(); var ui = new ReadOnlyController(session); var renderer = new ReadOnlyRenderer();
            renderer.render(screen, ui); screen.refresh(); int clears = terminal.clears, characters = terminal.characters;
            for (int i = 0; i < 30; i++) { renderer.render(screen, ui); screen.refresh(); }
            assertEquals(clears, terminal.clears); assertEquals(characters, terminal.characters);
        }
    }

    @Test void deletedEditedAndAuthorizationExpiredTextIsPurgedFromTheWrappedCache() throws Exception {
        try (var session = ready(); var terminal = new TrackingTerminal(); var screen = new TerminalScreen(terminal)) {
            screen.startScreen(); var ui = new ReadOnlyController(session); var renderer = new ReadOnlyRenderer();
            session.fixtureEdit(100, "PUBLIC_UNIQUE_OLD_TEXT", 0); renderer.render(screen, ui); screen.refresh();
            assertTrue(renderer.cachedContains("PUBLIC_UNIQUE_OLD_TEXT"));
            session.fixtureEdit(100, "PUBLIC_NEW_TEXT", 0); renderer.render(screen, ui); screen.refresh();
            assertFalse(renderer.cachedContains("PUBLIC_UNIQUE_OLD_TEXT")); assertTrue(renderer.cachedContains("PUBLIC_NEW_TEXT"));
            session.fixtureRemove(100); renderer.render(screen, ui); screen.refresh();
            assertFalse(renderer.cachedContains("PUBLIC_NEW_TEXT")); assertFalse(grid(screen).contains("PUBLIC_NEW_TEXT"));
            session.fixtureAuthorizationExpired(); renderer.render(screen, ui); screen.refresh(); capture(screen, "100x30-auth-expired");
            assertEquals(0, renderer.cachedLineCount()); assertTrue(grid(screen).contains("expired"));
        }
    }

    @Test void wrappedCacheIsHardBoundedAndTopMarkerIsReachable() throws Exception {
        try (var session = ready(); var terminal = new TrackingTerminal(); var screen = new TerminalScreen(terminal)) {
            for (int id = 91; id <= 100; id++) { session.fixtureEdit(id, "\n".repeat(2048), 0); }
            screen.startScreen(); var ui = new ReadOnlyController(session); var renderer = new ReadOnlyRenderer();
            ui.handle(new KeyStroke(KeyType.Tab)); ui.handle(new KeyStroke(KeyType.Home)); renderer.render(screen, ui);
            assertEquals(10_000, renderer.cachedLineCount()); assertEquals(10_000 - 25, ui.scroll());
            assertEquals("[", screen.getBackCharacter(25, 2).getCharacterString());
        }
    }

    @Test @Timeout(5) void idleLoopDoesNotFlushAnotherFrame() throws Exception {
        var session = ready(); var terminal = new TrackingTerminal(); int[] polls = {0}, initial = {0};
        assertEquals(0, new ReadOnlyTuiApplication().run(terminal, session, () -> {
            int poll = polls[0]++;
            if (poll == 0) { initial[0] = terminal.flushes; }
            else if (poll <= 3) { assertEquals(initial[0], terminal.flushes); }
            if (poll == 3) { terminal.addInput(new KeyStroke(KeyType.EOF)); }
        }));
        assertEquals(5, polls[0]); assertTrue(terminal.closed); assertTrue(session.latest().disposed());
    }

    @Test @Timeout(5) void resizeWithoutInputRepaintsExactlyOnce() throws Exception {
        var session = ready(); var terminal = new TrackingTerminal(); int[] polls = {0}, initial = {0};
        assertEquals(0, new ReadOnlyTuiApplication().run(terminal, session, () -> {
            int poll = polls[0]++;
            if (poll == 0) { initial[0] = terminal.flushes; terminal.setTerminalSize(new TerminalSize(48, 14)); }
            if (poll == 1) { assertEquals(initial[0] + 1, terminal.flushes); terminal.addInput(new KeyStroke('c', true, false)); }
        }));
        assertTrue(terminal.closed); assertTrue(session.latest().disposed());
    }

    @Test @Timeout(5) void fixtureGuideOwnsInputBeforePrivateModeAndNeverPrintsFixtureValues() throws Exception {
        var session = new SyntheticReadOnlySession(); var terminal = new TrackingTerminal();
        terminal.enterCheck = () -> { assertEquals(Step.READY, session.latest().auth().step()); assertNotNull(session.latest().identity()); };
        assertEquals(0, new ReadOnlyTuiApplication().run(terminal, session, () -> {
            if (session.latest().auth().step() != Step.READY) { assertFalse(terminal.privateMode); terminal.addInput(new KeyStroke(KeyType.Enter)); }
            else if (terminal.privateMode) { terminal.addInput(new KeyStroke(KeyType.EOF)); }
        }));
        assertEquals(1, terminal.entries); assertTrue(terminal.written.toString().contains("Never type credentials"));
        assertTrue(terminal.written.toString().contains("password step")); assertFalse(terminal.written.toString().contains("PUBLIC_SYNTHETIC_"));
        assertEquals(5, session.activity().authAttempts()); assertTrue(terminal.closed);
    }

    @Test @Timeout(5) void guideIgnoresTypedTextAndEofCancelsBeforeFullscreen() throws Exception {
        var session = new SyntheticReadOnlySession(); var terminal = new TrackingTerminal();
        for (char c : "PUBLIC_TYPED_DO_NOT_ECHO".toCharArray()) { terminal.addInput(new KeyStroke(c, false, false)); }
        terminal.addInput(new KeyStroke(KeyType.EOF));
        assertEquals(0, new ReadOnlyTuiApplication().run(terminal, session, () -> fail()));
        assertEquals(0, terminal.entries); assertEquals(0, session.activity().authAttempts()); assertTrue(terminal.closed);
        assertFalse(terminal.written.toString().contains("PUBLIC_TYPED_DO_NOT_ECHO"));
    }

    @Test @Timeout(5) void unsupportedAndRetryGuideStatesNeverAdvanceWithoutAnExplicitAction() throws Exception {
        var unsupported = new SyntheticReadOnlySession(SyntheticReadOnlySession.Scenario.UNSUPPORTED, Clock.systemUTC(), System::nanoTime);
        var terminal = new TrackingTerminal();
        assertEquals(0, new ReadOnlyTuiApplication().run(terminal, unsupported, () -> {
            if (unsupported.latest().auth().step() == Step.UNSUPPORTED) { terminal.addInput(new KeyStroke('c', true, false)); }
            else { terminal.addInput(new KeyStroke(KeyType.Enter)); }
        }));
        assertEquals(0, terminal.entries); assertTrue(terminal.written.toString().contains("Nothing accepted"));
        AtomicLong nanos = new AtomicLong();
        var retry = new SyntheticReadOnlySession(SyntheticReadOnlySession.Scenario.RETRY, Clock.systemUTC(), nanos::get);
        var retryTerminal = new TrackingTerminal(); boolean[] delayed = {false};
        assertEquals(0, new ReadOnlyTuiApplication().run(retryTerminal, retry, () -> {
            if (retry.latest().retryAfterSeconds() > 0) {
                assertEquals(1, retry.activity().authAttempts()); delayed[0] = true; nanos.set(Duration.ofSeconds(2).toNanos());
            }
            if (retryTerminal.privateMode) { retryTerminal.addInput(new KeyStroke('q', false, false)); }
            else if (retry.latest().auth().step().input()) { retryTerminal.addInput(new KeyStroke(KeyType.Enter)); }
        }));
        assertTrue(delayed[0]); assertEquals(6, retry.activity().authAttempts()); assertEquals(1, retryTerminal.entries);
    }

    @Test @Timeout(5) void failuresCloseTheSingleTerminalAndPurgeSessionInBothPhases() throws Exception {
        for (boolean authorized : new boolean[] {false, true}) {
            var session = authorized ? ready() : new SyntheticReadOnlySession(); var terminal = new TrackingTerminal();
            assertThrows(IllegalStateException.class, () -> new ReadOnlyTuiApplication().run(terminal, session,
                    () -> { throw new IllegalStateException("Injected synthetic failure"); }));
            assertTrue(terminal.closed); assertTrue(session.latest().disposed()); assertTrue(session.latest().messages().isEmpty());
            assertEquals(authorized ? 1 : 0, terminal.entries);
        }
    }
}
