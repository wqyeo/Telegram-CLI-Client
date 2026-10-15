package com.gitlab.wqyeo.telegramcli.tui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TerminalTextTest {
    @Test void escapesTerminalControlsAndLineBreaks() {
        assertEquals("hello\\u001b[2J\\u0007\\u000a\\u000d\\u0009\\u009b31m",
                TerminalText.escapeControls("hello\u001b[2J\u0007\n\r\t\u009b31m"));
    }

    @Test void preservesUnicodeAndEmojiJoiners() {
        var text = "你好 café 👩‍💻";
        assertEquals(text, TerminalText.escapeControls(text));
    }

    @Test void escapesBidiSpoofingAndUnicodeLineSeparators() {
        assertEquals("a\\u202eb\\u2069\\u2028\\u2029",
                TerminalText.escapeControls("a\u202eb\u2069\u2028\u2029"));
    }
}
