package com.gitlab.wqyeo.telegramcli.tui;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Immutable grapheme-aware editor with a visible, bounded UTF-8 draft size. */
public record Draft(String text, int cursor) {
    public static final int MAX_BYTES = 16_384;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    public Draft {
        if (cursor < 0 || cursor > text.length() || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
                || boundaryAtOrAfter(text, cursor) != cursor) {
            throw new IllegalArgumentException("Invalid draft.");
        }
    }

    public static Draft empty() { return new Draft("", 0); }

    public Draft insert(String addition) {
        var updated = text.substring(0, cursor) + addition + text.substring(cursor);
        if (updated.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Draft limit is 16 KiB; nothing was submitted.");
        }
        return new Draft(updated, boundaryAtOrAfter(updated, cursor + addition.length()));
    }

    private static int boundaryAtOrAfter(String text, int position) {
        if (position == 0 || position == text.length()) { return position; }
        var matcher = GRAPHEME.matcher(text);
        while (matcher.find()) {
            if (matcher.end() >= position) { return matcher.end(); }
        }
        return text.length();
    }

    private int previousBoundary() {
        var matcher = GRAPHEME.matcher(text);
        int previous = 0;
        while (matcher.find() && matcher.end() < cursor) { previous = matcher.end(); }
        return previous;
    }

    private int nextBoundary() {
        var matcher = GRAPHEME.matcher(text);
        while (matcher.find()) {
            if (matcher.end() > cursor) { return matcher.end(); }
        }
        return text.length();
    }

    public Draft left() { return new Draft(text, previousBoundary()); }
    public Draft right() { return new Draft(text, nextBoundary()); }
    public Draft home() { return new Draft(text, text.lastIndexOf('\n', Math.max(-1, cursor - 1)) + 1); }
    public Draft end() {
        int next = text.indexOf('\n', cursor);
        if (next > 0 && text.charAt(next - 1) == '\r') { next--; }
        return new Draft(text, next < 0 ? text.length() : next);
    }
    public Draft backspace() {
        if (cursor == 0) { return this; }
        int start = previousBoundary();
        var updated = text.substring(0, start) + text.substring(cursor);
        return new Draft(updated, boundaryAtOrAfter(updated, start));
    }
    public Draft delete() {
        if (cursor == text.length()) { return this; }
        var updated = text.substring(0, cursor) + text.substring(nextBoundary());
        return new Draft(updated, boundaryAtOrAfter(updated, cursor));
    }
}
