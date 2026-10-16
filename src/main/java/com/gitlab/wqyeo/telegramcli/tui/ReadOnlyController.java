package com.gitlab.wqyeo.telegramcli.tui;

import com.gitlab.wqyeo.telegramcli.application.SyntheticReadOnlySession;
import com.gitlab.wqyeo.telegramcli.session.bridge.*;
import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import com.googlecode.lanterna.input.*;
import java.util.ArrayDeque;

/** Input/render thread owner. Exactly two focus targets; no draft, composer or mutation callback. */
public final class ReadOnlyController {
    public enum Focus { CHATS, HISTORY }
    private final SyntheticReadOnlySession session;
    private final ArrayDeque<Long> newer = new ArrayDeque<>();
    private long selected, cursor;
    private Focus focus = Focus.CHATS;
    private int scroll;
    private boolean help, running = true;
    private String action = "Synthetic fixtures only. No Telegram connection.";
    public ReadOnlyController(SyntheticReadOnlySession session) { this.session = session; sync(); }
    public BridgeCoordinator.Snapshot state() { return session.latest(); }
    public Focus focus() { return focus; }
    public boolean running() { return running; }
    public boolean help() { return help; }
    public int scroll() { return scroll; }
    public long selectedId() { return selected; }
    public int paginationDepth() { return newer.size(); }
    public Chat selectedChat() { return state().chats().stream().filter(value -> value.id() == selected).findFirst().orElse(null); }
    public int selectedIndex() {
        for (int i = 0; i < state().chats().size(); i++) { if (state().chats().get(i).id() == selected) { return i; } }
        return 0;
    }
    public boolean savedSelected() { return state().identity() != null && selected == state().identity().savedChatId(); }
    public void clampScroll(int maximum) { scroll = Math.max(0, Math.min(scroll, maximum)); }
    public void sync() {
        if (state().identity() == null) { selected = 0; cursor = 0; newer.clear(); scroll = 0; return; }
        if (selectedChat() == null) {
            long saved = state().identity().savedChatId();
            selected = state().chats().stream().anyMatch(value -> value.id() == saved) ? saved
                    : state().chats().isEmpty() ? 0 : state().chats().getFirst().id();
            scroll = 0;
        }
    }
    public String status() {
        if (state().disposed()) { return "Preview closed. Quit and restart the synthetic preview."; }
        if (state().connection() == Phase.AUTH_EXPIRED) { return "Synthetic authorization expired. Q quit; restart preview explicitly."; }
        if (state().auth() != null && state().auth().step() == Step.UNSUPPORTED) { return "Unsupported fixture flow. Nothing accepted. Q quit."; }
        if (state().connection() == Phase.OFFLINE) { return "Fixture offline: cached text only. No automatic reconnect/retry. Q quit."; }
        if (state().connection() == Phase.LOADING || state().pendingRequests() != 0) { return "Loading bounded synthetic pages. Q quit."; }
        if (state().lastError() != null) { return "Fixture request unavailable. R refresh when ready; Q quit."; }
        return action;
    }
    public static boolean quits(KeyStroke key) {
        return key.getKeyType() == KeyType.EOF || (key.getKeyType() == KeyType.Character
                && ((key.isCtrlDown() && Character.toLowerCase(key.getCharacter()) == 'c')
                || (!key.isCtrlDown() && !key.isAltDown() && Character.toLowerCase(key.getCharacter()) == 'q')));
    }
    public void handle(KeyStroke key) throws BridgeException {
        sync();
        if (quits(key)) { running = false; return; }
        if (key.getKeyType() == KeyType.F1) { help = !help; return; }
        if (help) { if (key.getKeyType() == KeyType.Escape) { help = false; } return; }
        switch (key.getKeyType()) {
            case Tab, ReverseTab -> focus = focus == Focus.CHATS ? Focus.HISTORY : Focus.CHATS;
            case Enter -> focus = Focus.HISTORY;
            case Escape -> focus = Focus.CHATS;
            case ArrowUp -> { if (focus == Focus.CHATS) { select(-1); } else { scroll = Math.min(10_000, scroll + 1); } }
            case ArrowDown -> { if (focus == Focus.CHATS) { select(1); } else { scroll = Math.max(0, scroll - 1); } }
            case PageUp -> scroll = Math.min(10_000, scroll + 10);
            case PageDown -> scroll = Math.max(0, scroll - 10);
            case Home -> { if (focus == Focus.CHATS) { select(-MAX_CHATS); } else { scroll = 10_000; } }
            case End -> { if (focus == Focus.CHATS) { select(MAX_CHATS); } else { scroll = 0; } }
            case F2 -> action = "Read-only preview: no composer or send action.";
            case Character -> {
                if (key.isCtrlDown() || key.isAltDown()) { return; }
                switch (Character.toLowerCase(key.getCharacter())) {
                    case 'n' -> next();
                    case 'b' -> previous();
                    case 'r' -> {
                        if (state().connection() != Phase.READY) { return; }
                        session.refresh(); cursor = 0; newer.clear(); scroll = 0; sync(); action = "Synthetic view refreshed. Nothing sent.";
                    }
                    default -> { /* Typed text is never collected, edited or sent. */ }
                }
            }
            default -> { /* No composer, delete, media or send binding. */ }
        }
    }
    private void select(int direction) {
        if (state().chats().isEmpty()) { return; }
        int index = Math.max(0, Math.min(state().chats().size() - 1, selectedIndex() + direction));
        selected = state().chats().get(index).id(); scroll = 0;
    }
    private void next() throws BridgeException {
        if (state().identity() == null) { return; }
        if (focus == Focus.CHATS) {
            action = session.moreChats() ? "More fixture chats retained (maximum 100)." : "End of bounded fixture chat list (maximum 100).";
            sync(); return;
        }
        if (!savedSelected()) { action = "Only Saved Messages text can be viewed in this preview."; return; }
        long next = state().nextMessages();
        if (next == 0) { action = "End of bounded fixture Saved Messages (maximum 100)."; return; }
        session.savedPage(next);
        if (state().lastError() == null) {
            if (newer.size() == 10) { newer.removeFirst(); }
            newer.addLast(cursor); cursor = next; scroll = 0; action = "Older synthetic Saved page. B newer; R newest.";
        }
    }
    private void previous() throws BridgeException {
        if (!savedSelected() || focus != Focus.HISTORY) { return; }
        if (newer.isEmpty()) { action = "Newest requested page already shown. N older."; return; }
        long previous = newer.peekLast(); session.savedPage(previous);
        if (state().lastError() == null) { newer.removeLast(); cursor = previous; scroll = 0; action = "Newer synthetic Saved page."; }
    }
}
