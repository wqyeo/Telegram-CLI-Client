package com.gitlab.wqyeo.telegramcli.tui;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DraftTest {
    @Test void editsWholeGraphemes() {
        var draft = Draft.empty().insert("e\u0301👩‍💻你好");
        assertEquals("e\u0301👩‍💻你", draft.backspace().text());
        assertEquals("e\u0301你好", draft.left().left().backspace().text());
        assertEquals("e\u0301", Draft.empty().insert("e\u0301👩‍💻").backspace().text());
        assertEquals("e\u0301你好", draft.home().right().delete().text());
    }
    @Test void insertsAndEditsMultilineText() {
        var draft = Draft.empty().insert("first\nsecond");
        assertEquals(6, draft.home().cursor());
        assertEquals(12, draft.home().end().cursor());
        assertEquals("first!\nsecond", draft.home().left().insert("!").text());
        assertEquals(0, Draft.empty().left().cursor());
        assertEquals(Draft.empty(), Draft.empty().backspace().delete());
    }
    @Test void mergingAdjacentEmojiKeepsTheCursorAtAGraphemeBoundary() {
        var draft = Draft.empty().insert("👩💻").left().insert("‍");
        assertEquals("👩‍💻", draft.text());
        assertEquals(draft.text().length(), draft.cursor());
        assertEquals("", draft.backspace().text());
        assertThrows(IllegalArgumentException.class, () -> new Draft("🙂", 1));
    }
    @Test void enforcesUtf8ByteLimitWithoutChangingTheOriginal() {
        var draft = Draft.empty().insert("a".repeat(Draft.MAX_BYTES - 3));
        var full = draft.insert("界");
        assertThrows(IllegalArgumentException.class, () -> full.insert("b"));
        assertEquals(Draft.MAX_BYTES - 3, draft.text().length());
    }
}
