package com.gitlab.wqyeo.telegramcli.tui;

import static org.junit.jupiter.api.Assertions.*;
import com.gitlab.wqyeo.telegramcli.application.DemoScenario;
import com.gitlab.wqyeo.telegramcli.model.ClientState;
import com.gitlab.wqyeo.telegramcli.model.ConnectionStatus;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.Test;

class DemoRendererTest {
    private static class CountingTerminal extends DefaultVirtualTerminal {
        int clears;
        int characters;
        int flushes;
        CountingTerminal() { super(new TerminalSize(100, 30)); }
        @Override public void clearScreen() { clears++; super.clearScreen(); }
        @Override public void putString(String text) { characters += text.length(); super.putString(text); }
        @Override public void flush() { flushes++; super.flush(); }
    }

    @Test void unchangedFramesDoNotClearOrRewriteThePhysicalTerminal() throws Exception {
        var scenario = DemoScenario.standard();
        var ui = new DemoController(scenario, (a, b) -> fail());
        var state = new ClientState(ConnectionStatus.READY, scenario.messages());
        var terminal = new CountingTerminal();
        try (terminal; var screen = new TerminalScreen(terminal)) {
            screen.startScreen();
            var renderer = new DemoRenderer();
            renderer.render(screen, ui, state); screen.refresh();
            int clears = terminal.clears;
            int characters = terminal.characters;
            for (int i = 0; i < 30; i++) { renderer.render(screen, ui, state); screen.refresh(); }
            assertEquals(clears, terminal.clears, "unchanged frames must not physically clear the screen");
            assertEquals(characters, terminal.characters, "unchanged frames must not rewrite text");
        }
    }

    @Test void composerEditsUseSmallDeltasAndEraseDeletedText() throws Exception {
        var scenario = DemoScenario.standard();
        var ui = new DemoController(scenario, (a, b) -> fail());
        ui.handle(new KeyStroke(KeyType.Tab)); ui.handle(new KeyStroke(KeyType.Tab));
        var state = new ClientState(ConnectionStatus.READY, scenario.messages());
        var terminal = new CountingTerminal();
        try (terminal; var screen = new TerminalScreen(terminal)) {
            screen.startScreen();
            var renderer = new DemoRenderer();
            renderer.render(screen, ui, state); screen.refresh();
            int clears = terminal.clears;
            int characters = terminal.characters;
            ui.handle(new KeyStroke('x', false, false));
            renderer.render(screen, ui, state); screen.refresh();
            assertEquals(clears, terminal.clears);
            assertTrue(terminal.characters > characters && terminal.characters - characters < 40,
                    "one character must not rewrite the whole screen");
            assertEquals("x", terminal.getCharacter(25, 25).getCharacterString());
            ui.handle(new KeyStroke(KeyType.Backspace));
            renderer.render(screen, ui, state); screen.refresh();
            assertEquals(clears, terminal.clears);
            assertEquals(" ", terminal.getCharacter(25, 25).getCharacterString());
        }
    }

    @Test @org.junit.jupiter.api.Timeout(5)
    void idleInputLoopDoesNotRefreshTheTerminal() throws Exception {
        var terminal = new CountingTerminal();
        int[] polls = {0};
        int[] initialFlushes = {0};
        assertEquals(0, new TuiApplication().run(terminal, DemoScenario.standard(), 100, () -> {
            int poll = polls[0]++;
            if (poll == 0) { initialFlushes[0] = terminal.flushes; }
            else if (poll <= 3) {
                assertEquals(initialFlushes[0], terminal.flushes, "idle polling must not flush another frame");
            }
            if (poll == 3) { terminal.addInput(new KeyStroke(KeyType.EOF)); }
        }));
        assertEquals(5, polls[0]);
    }

    @Test @org.junit.jupiter.api.Timeout(5)
    void resizesWithoutNeedingAKeypressOrStateUpdate() throws Exception {
        var terminal = new CountingTerminal();
        int[] polls = {0};
        int[] initialFlushes = {0};
        assertEquals(0, new TuiApplication().run(terminal, DemoScenario.standard(), 100, () -> {
            int poll = polls[0]++;
            if (poll == 0) {
                initialFlushes[0] = terminal.flushes;
                terminal.setTerminalSize(new TerminalSize(48, 14));
            } else if (poll == 1) {
                assertEquals(initialFlushes[0] + 1, terminal.flushes, "resize alone must repaint");
                terminal.addInput(new KeyStroke(KeyType.EOF));
            }
        }));
        assertEquals(3, polls[0]);
    }

    @Test void safelyRendersUnicodeAndControlsAcrossResizes() throws Exception {
        var scenario = DemoScenario.standard();
        var ui = new DemoController(scenario, (a, b) -> fail());
        ui.handle(new KeyStroke(KeyType.ArrowDown)); ui.handle(new KeyStroke(KeyType.ArrowDown));
        ui.handle(new KeyStroke(KeyType.Tab));
        var terminal = new DefaultVirtualTerminal(new TerminalSize(100, 30));
        try (terminal; var screen = new TerminalScreen(terminal)) {
            screen.startScreen();
            var state = new ClientState(ConnectionStatus.READY, scenario.messages());
            for (var size : new TerminalSize[] {new TerminalSize(100, 30), new TerminalSize(50, 14),
                    new TerminalSize(12, 4), new TerminalSize(80, 20)}) {
                terminal.setTerminalSize(size); new DemoRenderer().render(screen, ui, state); screen.refresh();
                for (int y = 0; y < size.getRows(); y++) {
                    for (int x = 0; x < size.getColumns(); x++) {
                        var cell = screen.getBackCharacter(x, y).getCharacterString();
                        assertFalse(cell.contains("\u001b")); assertFalse(cell.contains("\u0007"));
                    }
                }
            }
        }
    }
    @Test void layoutPreservesGraphemesAndEscapesProtocolControls() {
        assertEquals(2, TextLayout.width("你"));
        assertEquals(1, TextLayout.width("e\u0301"));
        assertEquals(java.util.List.of("👩‍💻", "你"), TextLayout.wrap("👩‍💻你", 2));
        assertEquals(java.util.List.of("a", "b"), TextLayout.wrap("a\nb", 5));
        assertEquals("\\u001b[2J", String.join("", TextLayout.wrap("\u001b[2J", 80)));
    }
    @Test void keepsQuitConfirmationVisibleInAnUndersizedTerminal() throws Exception {
        var ui = new DemoController(DemoScenario.standard(), (a, b) -> fail());
        ui.handle(new KeyStroke(KeyType.Tab)); ui.handle(new KeyStroke(KeyType.Tab));
        ui.handle(new KeyStroke('x', false, false)); ui.handle(new KeyStroke('c', true, false));
        var terminal = new DefaultVirtualTerminal(new TerminalSize(12, 4));
        try (terminal; var screen = new TerminalScreen(terminal)) {
            screen.startScreen(); new DemoRenderer().render(screen, ui, ClientState.initial());
            var footer = new StringBuilder();
            for (int x = 0; x < 12; x++) { footer.append(screen.getBackCharacter(x, 3).getCharacterString()); }
            assertTrue(footer.toString().startsWith("Quit? Y/N"));
            assertTrue(ui.confirmQuit()); assertTrue(ui.running());
        }
    }
    @Test void boundsWrappedHistoryAndCachesTheImmutableView() throws Exception {
        var ui = new DemoController(DemoScenario.standard(), (a, b) -> fail());
        var message = new com.gitlab.wqyeo.telegramcli.model.ChatMessage("demo-saved", "limit", "Demo", "\n".repeat(12_000));
        var state = new ClientState(ConnectionStatus.READY, java.util.List.of(message));
        var terminal = new DefaultVirtualTerminal(new TerminalSize(100, 30));
        try (terminal; var screen = new TerminalScreen(terminal)) {
            screen.startScreen();
            var renderer = new DemoRenderer(); renderer.render(screen, ui, state);
            for (int i = 0; i < 1100; i++) { ui.handle(new KeyStroke(KeyType.PageUp)); }
            renderer.render(screen, ui, state);
            assertEquals(10_000 - 21, ui.scroll());
            assertEquals("[", screen.getBackCharacter(25, 2).getCharacterString());
        }
    }
    @Test void closesTheTerminalOnFailure() {
        class TrackingTerminal extends DefaultVirtualTerminal {
            boolean closed;
            @Override public void close() { closed = true; super.close(); }
        }
        var terminal = new TrackingTerminal();
        assertThrows(IllegalStateException.class, () -> new TuiApplication().run(terminal,
                DemoScenario.standard(), 100, () -> { throw new IllegalStateException("injected failure"); }));
        assertTrue(terminal.closed);
    }
    @Test void exitsCleanlyOnEof() throws Exception {
        var terminal = new DefaultVirtualTerminal(); terminal.addInput(new KeyStroke(KeyType.EOF));
        assertEquals(0, new TuiApplication().run(terminal, DemoScenario.standard(), 100, () -> {}));
    }
}
