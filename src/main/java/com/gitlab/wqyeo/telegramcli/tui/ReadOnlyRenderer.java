package com.gitlab.wqyeo.telegramcli.tui;

import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.Screen;
import java.util.*;

/** Incumbent Lanterna composition without a composer. No physical clear during ordinary redraws. */
public final class ReadOnlyRenderer {
    private static final TextColor NORMAL = TextColor.ANSI.DEFAULT, BACKGROUND = TextColor.ANSI.DEFAULT;
    private static final int MAX_LINES = 10_000;
    private List<Message> cachedMessages = List.of();
    private List<String> cachedLines = List.of();
    private long cachedChat;
    private int cachedWidth;
    public void render(Screen screen, ReadOnlyController ui) {
        ui.sync(); screen.doResizeIfNecessary();
        screen.newTextGraphics().setForegroundColor(NORMAL).setBackgroundColor(BACKGROUND).fill(' ');
        screen.setCursorPosition(null);
        int width = screen.getTerminalSize().getColumns(), height = screen.getTerminalSize().getRows();
        line(screen, 0, 0, width, "READ-ONLY PREVIEW | SYNTHETIC", TextColor.ANSI.YELLOW);
        if (width < 40 || height < 10) {
            purge(); line(screen, 0, 2, width, "Terminal too small (minimum 40 x 10).", NORMAL);
            line(screen, 0, height - 1, width, "Q/Ctrl+C quit", NORMAL); return;
        }
        boolean split = width >= 80 && height >= 16;
        int left = split ? 25 : 0, contentWidth = width - left;
        int historyRows = height - 5, listRows = height - 5;
        boolean chatsOnly = !split && ui.focus() == ReadOnlyController.Focus.CHATS;
        if (split || chatsOnly) {
            int listWidth = split ? left - 1 : width;
            line(screen, 0, 1, listWidth, "Chats" + (ui.focus() == ReadOnlyController.Focus.CHATS ? " *" : ""), NORMAL);
            int start = Math.max(0, ui.selectedIndex() - listRows + 1);
            if (ui.state().chats().isEmpty()) { line(screen, 0, 2, listWidth,
                    ui.state().identity() == null ? "Preview unavailable. Q quit." : "No retained chats. R reload.", NORMAL); }
            for (int i = 0; i < listRows && start + i < ui.state().chats().size(); i++) {
                Chat chat = ui.state().chats().get(start + i);
                line(screen, 0, 2 + i, listWidth, (chat.id() == ui.selectedId() ? "> " : "  ") + chat.title(),
                        chat.id() == ui.selectedId() ? TextColor.ANSI.CYAN : NORMAL);
            }
        }
        if (!chatsOnly) {
            if (split) { for (int row = 1; row < height - 3; row++) { line(screen, left - 1, row, 1, "|", NORMAL); } }
            Chat chat = ui.selectedChat();
            line(screen, left, 1, contentWidth, (chat == null ? "No chat selected" : chat.title())
                    + (ui.focus() == ReadOnlyController.Focus.HISTORY ? " [history *]" : ""), NORMAL);
            var history = history(ui, contentWidth);
            ui.clampScroll(Math.max(0, history.size() - historyRows));
            int start = Math.max(0, history.size() - historyRows - ui.scroll());
            for (int i = 0; i < historyRows && start + i < history.size(); i++) { line(screen, left, 2 + i, contentWidth, history.get(start + i), NORMAL); }
        } else { purge(); }
        line(screen, 0, height - 3, width, "Fixture view | Chats " + ui.state().chats().size() + "/100 | Saved "
                + ui.state().messages().size() + " visible, " + ui.state().retainedMessages() + "/100 cached", NORMAL);
        line(screen, 0, height - 2, width, ui.status(), TextColor.ANSI.YELLOW);
        line(screen, 0, height - 1, width, width < 80 ? "Q quit | Tab focus | N/B pages | F1 help"
                : "Q/Ctrl+C quit | Tab focus | PgUp/PgDn scroll | N/B pages | R refresh | F1 help", NORMAL);
        if (ui.help()) {
            overlay(screen, width < 80 || height < 16
                    ? List.of("SYNTHETIC READ-ONLY HELP", "Q/Ctrl+C/EOF quit; F1/Esc close",
                            "Tab/Shift+Tab focus; Enter history", "Esc chats; arrows select/scroll",
                            "PgUp/PgDn scroll; Home/End edges", "N more/older; B newer; R refresh",
                            "Saved text; no send/edit/delete/media", "No live/read/presence/encryption tests")
                    : List.of("SYNTHETIC READ-ONLY HELP", "Q/Ctrl+C/EOF: quit; F1/Escape: close help",
                            "Tab/Shift+Tab: chats/history; arrows: select/scroll", "Enter: history; Escape: chats; Home/End: first/last",
                            "PgUp/PgDn: scroll history by ten lines",
                            "N: more chats / older Saved page; B: newer Saved page", "R: reload first fixture pages; cache max100 rows each",
                            "Only Saved Messages text; other chats are list-only", "No composer, send, edit, delete or media actions",
                            "No real account, read/presence or encryption behavior tested"));
        }
    }
    private List<String> history(ReadOnlyController ui, int width) {
        if (ui.state().identity() == null || ui.state().disposed()) {
            purge(); return List.of(ui.status());
        }
        if (!ui.savedSelected()) { purge(); return List.of("Only Saved Messages text is supported in this preview.", "Other chats are listed only. Tab returns to chats."); }
        var messages = ui.state().messages();
        if (cachedMessages == messages && cachedChat == ui.selectedId() && cachedWidth == width) { return cachedLines; }
        var lines = new ArrayDeque<String>(); boolean trimmed = false;
        for (Message message : messages) {
            for (String text : List.of("[synthetic] " + message.sender() + (message.edited() ? " (edited)" : ""), message.text(), "")) {
                for (String row : TextLayout.wrap(text, width)) {
                    lines.addLast(row); if (lines.size() > MAX_LINES) { lines.removeFirst(); trimmed = true; }
                }
            }
        }
        if (lines.isEmpty()) { lines.add("No synthetic text on this page. R reloads newest; B goes newer."); }
        if (trimmed) { lines.removeFirst(); lines.addFirst("[View limited to the latest 10,000 wrapped lines]"); }
        cachedMessages = messages; cachedChat = ui.selectedId(); cachedWidth = width; cachedLines = List.copyOf(lines);
        return cachedLines;
    }
    public void purge() { cachedMessages = List.of(); cachedLines = List.of(); cachedChat = 0; cachedWidth = 0; }
    int cachedLineCount() { return cachedLines.size(); }
    boolean cachedContains(String text) { return cachedLines.stream().anyMatch(value -> value.contains(text)); }
    private void overlay(Screen screen, List<String> text) {
        int width = screen.getTerminalSize().getColumns(), height = screen.getTerminalSize().getRows();
        int padding = height >= text.size() + 4 ? 1 : 0;
        int top = Math.max(1, (height - text.size() - 2 * padding) / 2);
        for (int i = 0; i < text.size() + 2 * padding && top + i < height - 1; i++) {
            line(screen, 0, top + i, width, " ".repeat(width), NORMAL);
            int index = i - padding;
            if (index >= 0 && index < text.size()) { line(screen, 1, top + i, width - 2, text.get(index), TextColor.ANSI.CYAN); }
        }
    }
    private void line(Screen screen, int x, int y, int width, String text, TextColor color) { TextLayout.draw(screen, x, y, width, text, color, BACKGROUND); }
}
