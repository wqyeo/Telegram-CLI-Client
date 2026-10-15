package com.gitlab.wqyeo.telegramcli.tui;

import java.util.Locale;

/** Safe single-line text rendering; never emits input-supplied terminal controls. */
public final class TerminalText {
    private TerminalText() {}

    public static String escapeControls(String text) {
        var safe = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            boolean bidiControl = codePoint == 0x061c || codePoint == 0x200e || codePoint == 0x200f
                    || (codePoint >= 0x202a && codePoint <= 0x202e)
                    || (codePoint >= 0x2066 && codePoint <= 0x2069);
            if (Character.isISOControl(codePoint) || bidiControl || codePoint == 0x2028 || codePoint == 0x2029) {
                safe.append(String.format(Locale.ROOT, "\\u%04x", codePoint));
            } else {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString();
    }
}
