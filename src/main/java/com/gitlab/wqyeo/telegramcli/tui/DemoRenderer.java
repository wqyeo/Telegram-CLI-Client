package com.gitlab.wqyeo.telegramcli.tui;

import com.gitlab.wqyeo.telegramcli.model.ClientState;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.Screen;
import java.util.ArrayDeque;
import java.util.List;

public final class DemoRenderer {
    private static final TextColor NORMAL = TextColor.ANSI.DEFAULT;
    private static final TextColor BACKGROUND = TextColor.ANSI.DEFAULT;
    private static final int MAX_HISTORY_LINES = 10_000;
    private ClientState cachedState;
    private String cachedChat;
    private int cachedWidth;
    private List<String> cachedHistory = List.of();

    public void render(Screen screen, DemoController ui, ClientState state) {
        screen.doResizeIfNecessary();
        screen.clear();
        screen.setCursorPosition(null);
        int width = screen.getTerminalSize().getColumns();
        int height = screen.getTerminalSize().getRows();
        line(screen, 0, 0, width, "OFFLINE DEMO - NOT CONNECTED - no real messages sent", TextColor.ANSI.YELLOW);
        if (width < 40 || height < 10) {
            line(screen, 0, 2, width, "Terminal too small (minimum 40 x 10).", NORMAL);
            line(screen, 0, height - 1, width, ui.confirmQuit() ? "Quit? Y/N" : "Ctrl+C to quit", NORMAL);
            return;
        }
        boolean split = width >= 80 && height >= 16;
        int left = split ? 25 : 0;
        int contentWidth = width - left;
        int historyRows = Math.max(1, height - 9);
        boolean chatsOnly = !split && ui.focus() == DemoController.Focus.CHATS;
        if (split || chatsOnly) {
            int listWidth = split ? left - 1 : width;
            line(screen, 0, 1, listWidth, "Chats" + (ui.focus() == DemoController.Focus.CHATS ? " *" : ""), NORMAL);
            for (int i = 0; i < ui.scenario().chats().size() && i < height - 4; i++) {
                var chat = ui.scenario().chats().get(i);
                line(screen, 0, 2 + i, listWidth, (i == ui.selectedIndex() ? "> " : "  ") + chat.title(),
                        i == ui.selectedIndex() ? TextColor.ANSI.CYAN : NORMAL);
            }
        }
        if (!chatsOnly) {
            if (split) {
                for (int row = 1; row < height - 2; row++) { line(screen, left - 1, row, 1, "|", NORMAL); }
            }
            line(screen, left, 1, contentWidth, ui.selectedChat().title()
                    + (ui.focus() == DemoController.Focus.HISTORY ? " [history *]" : ""), NORMAL);
            var history = history(ui, state, contentWidth);
            ui.clampScroll(Math.max(0, history.size() - historyRows));
            int start = Math.max(0, history.size() - historyRows - ui.scroll());
            for (int i = 0; i < historyRows && start + i < history.size(); i++) {
                line(screen, left, 2 + i, contentWidth, history.get(start + i), NORMAL);
            }
            int composerTop = height - 6;
            line(screen, left, composerTop, contentWidth,
                    "-- Composer" + (ui.focus() == DemoController.Focus.COMPOSER ? " *" : "")
                            + " (Enter newline, F2 simulate) --", TextColor.ANSI.CYAN);
            var draft = ui.draft();
            var lines = TextLayout.wrap(draft.text() + " ", contentWidth);
            var prefix = TextLayout.wrap(draft.text().substring(0, draft.cursor()) + " ", contentWidth);
            int cursorRow = prefix.size() - 1;
            int first = Math.max(0, cursorRow - 2);
            for (int i = 0; i < 3 && first + i < lines.size(); i++) {
                line(screen, left, composerTop + 1 + i, contentWidth, lines.get(first + i), NORMAL);
            }
            if (ui.focus() == DemoController.Focus.COMPOSER) {
                int column = Math.max(0, TextLayout.width(prefix.getLast()) - 1);
                screen.setCursorPosition(new TerminalPosition(left + Math.min(contentWidth - 1, column),
                        composerTop + 1 + cursorRow - first));
            }
        }
        line(screen, 0, height - 2, width, ui.status(), TextColor.ANSI.YELLOW);
        line(screen, 0, height - 1, width, "Tab focus | F1 help | F2 simulate send | Ctrl+C quit", NORMAL);
        if (ui.help()) {
            overlay(screen, List.of("OFFLINE DEMO HELP", "Tab/Shift+Tab: focus; arrows: chat/history navigation",
                    "Composer: Enter newline, Left/Right/Home/End, Backspace/Delete",
                    "F2: append a simulated local message; nothing is transmitted",
                    "PageUp/PageDown: history; drafts survive chat switching",
                    "16 KiB draft limit; Escape/F1 closes help; Ctrl+C quits"));
        }
        if (ui.confirmQuit()) {
            overlay(screen, List.of("DISCARD UNSENT DEMO DRAFTS?", "Y: discard and quit", "N or Escape: continue editing"));
        }
    }

    private List<String> history(DemoController ui, ClientState state, int width) {
        String chat = ui.selectedChat().id();
        if (cachedState == state && chat.equals(cachedChat) && width == cachedWidth) { return cachedHistory; }
        var lines = new ArrayDeque<String>();
        boolean trimmed = false;
        for (var message : state.messages()) {
            if (message.chatId().equals(chat)) {
                for (var text : List.of("[demo] " + message.sender(), message.text(), "")) {
                    for (var line : TextLayout.wrap(text, width)) {
                        lines.addLast(line);
                        if (lines.size() > MAX_HISTORY_LINES) { lines.removeFirst(); trimmed = true; }
                    }
                }
            }
        }
        if (lines.isEmpty()) { lines.add("No retained demo messages in this chat."); }
        if (trimmed) {
            lines.removeFirst();
            lines.addFirst("[View limited to the latest 10,000 wrapped lines]");
        }
        cachedState = state; cachedChat = chat; cachedWidth = width;
        cachedHistory = List.copyOf(lines);
        return cachedHistory;
    }

    private void overlay(Screen screen, List<String> lines) {
        int columns = screen.getTerminalSize().getColumns();
        int rows = screen.getTerminalSize().getRows();
        int top = Math.max(1, (rows - lines.size() - 2) / 2);
        screen.setCursorPosition(null);
        for (int i = 0; i < lines.size() + 2 && top + i < rows - 1; i++) {
            line(screen, 0, top + i, columns, " ".repeat(columns), NORMAL);
            if (i > 0 && i <= lines.size()) {
                line(screen, 1, top + i, columns - 2, lines.get(i - 1), TextColor.ANSI.CYAN);
            }
        }
    }

    private void line(Screen screen, int x, int y, int width, String text, TextColor color) {
        TextLayout.draw(screen, x, y, width, text, color, BACKGROUND);
    }
}
