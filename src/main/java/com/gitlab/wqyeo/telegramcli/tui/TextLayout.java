package com.gitlab.wqyeo.telegramcli.tui;

import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.Screen;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** Uses the same grapheme/cell approximation as the selected terminal backend. */
public final class TextLayout {
    private TextLayout() {}

    private static TextCharacter[] glyphs(String line) {
        return TextCharacter.fromString(Normalizer.normalize(TerminalText.escapeControls(line), Normalizer.Form.NFC));
    }

    public static int width(String line) {
        int columns = 0;
        for (var glyph : glyphs(line)) { columns += glyph.isDoubleWidth() ? 2 : 1; }
        return columns;
    }

    public static List<String> wrap(String text, int columns) {
        if (columns < 1) { throw new IllegalArgumentException("Positive width required."); }
        var lines = new ArrayList<String>();
        for (var paragraph : text.split("\n", -1)) {
            var line = new StringBuilder();
            int used = 0;
            for (var glyph : glyphs(paragraph)) {
                int size = glyph.isDoubleWidth() ? 2 : 1;
                var value = glyph.getCharacterString();
                if (size > columns) { value = "?"; size = 1; }
                if (used + size > columns) {
                    lines.add(line.toString()); line.setLength(0); used = 0;
                }
                line.append(value); used += size;
            }
            lines.add(line.toString());
        }
        return List.copyOf(lines);
    }

    public static void draw(Screen screen, int x, int y, int columns, String line,
                            TextColor foreground, TextColor background) {
        if (y < 0 || y >= screen.getTerminalSize().getRows()) { return; }
        int limit = Math.min(screen.getTerminalSize().getColumns(), x + columns);
        for (var glyph : glyphs(line)) {
            int size = glyph.isDoubleWidth() ? 2 : 1;
            if (x < 0 || x + size > limit) { break; }
            screen.setCharacter(x, y, glyph.withForegroundColor(foreground).withBackgroundColor(background));
            x += size;
        }
    }
}
