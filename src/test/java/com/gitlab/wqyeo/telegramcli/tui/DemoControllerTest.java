package com.gitlab.wqyeo.telegramcli.tui;

import static org.junit.jupiter.api.Assertions.*;
import com.gitlab.wqyeo.telegramcli.application.DemoScenario;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoControllerTest {
    private final List<String> submissions = new ArrayList<>();
    private final DemoController ui = new DemoController(DemoScenario.standard(),
            (chat, text) -> submissions.add(chat + ":" + text));
    private void key(KeyType type) { ui.handle(new KeyStroke(type)); }
    private void text(String text) { for (char c : text.toCharArray()) { ui.handle(new KeyStroke(c, false, false)); } }
    private void composer() { key(KeyType.Tab); key(KeyType.Tab); }
    private void quit() { ui.handle(new KeyStroke('c', true, false)); }

    @Test void enterNeverSendsAndF2ExplicitlySimulates() {
        composer(); text("hello"); key(KeyType.Enter); text("你好🙂");
        assertTrue(submissions.isEmpty());
        assertEquals("hello\n你好🙂", ui.draft().text());
        key(KeyType.F2);
        assertEquals(List.of("demo-saved:hello\n你好🙂"), submissions);
        assertEquals("", ui.draft().text());
        assertTrue(ui.status().contains("Nothing was sent"));
    }
    @Test void draftsAreChatScoped() {
        composer(); text("draft one"); key(KeyType.Tab); key(KeyType.ArrowDown);
        composer(); text("draft two"); key(KeyType.Tab); key(KeyType.ArrowUp);
        assertEquals("draft one", ui.draft().text());
        key(KeyType.ArrowDown);
        assertEquals("draft two", ui.draft().text());
    }
    @Test void quitConfirmsDiscardAcrossAllChats() {
        composer(); text("unsent"); key(KeyType.Tab); key(KeyType.ArrowDown);
        quit(); assertTrue(ui.running()); assertTrue(ui.confirmQuit());
        key(KeyType.Escape); assertFalse(ui.confirmQuit());
        quit(); text("y"); assertFalse(ui.running());
        assertTrue(submissions.isEmpty());
    }
    @Test void emptyQuitAndEofDoNotHang() {
        quit(); assertFalse(ui.running());
        var other = new DemoController(DemoScenario.standard(), (a, b) -> fail());
        other.handle(new KeyStroke(KeyType.EOF)); assertFalse(other.running());
    }
    @Test void helpDoesNotSubmitOrEdit() {
        composer(); text("keep"); key(KeyType.F1); key(KeyType.F2); text("ignore");
        assertTrue(ui.help()); assertEquals("keep", ui.draft().text()); assertTrue(submissions.isEmpty());
        key(KeyType.Escape); assertFalse(ui.help());
    }
    @Test void rejectsBlankSubmissionsAndControlCharacters() {
        composer(); key(KeyType.F2); assertTrue(submissions.isEmpty());
        text("\u001b\u0007"); assertEquals("", ui.draft().text());
        ui.handle(new KeyStroke('v', true, false)); assertEquals("", ui.draft().text());
    }
    @Test void anOversizedPasteCannotSilentlySubmitItsAcceptedPrefix() {
        composer(); text("x".repeat(Draft.MAX_BYTES + 32));
        assertEquals(Draft.MAX_BYTES, ui.draft().text().length());
        key(KeyType.F2); assertTrue(submissions.isEmpty());
        assertTrue(ui.status().contains("rejected"));
        key(KeyType.ArrowLeft); key(KeyType.F2); assertTrue(submissions.isEmpty());
        key(KeyType.Backspace); key(KeyType.F2);
        assertEquals(1, submissions.size());
        assertEquals("demo-saved:".length() + Draft.MAX_BYTES - 1, submissions.getFirst().length());
    }
    @Test void historyScrollIsClampedAndDoesNotAffectInputFocus() {
        key(KeyType.Tab); key(KeyType.PageUp); assertEquals(10, ui.scroll());
        ui.clampScroll(3); assertEquals(3, ui.scroll());
        key(KeyType.PageDown); assertEquals(0, ui.scroll());
        assertEquals(DemoController.Focus.HISTORY, ui.focus());
    }
    @Test void incompleteSurrogateIsNotStored() {
        composer(); text("\ud83d"); assertEquals("", ui.draft().text());
        key(KeyType.Tab); composer(); text("\ude42"); assertEquals("", ui.draft().text());
    }
}
