package com.gitlab.wqyeo.telegramcli.tui;

import com.gitlab.wqyeo.telegramcli.application.DemoScenario;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** Owned by the input/render thread. Demo submission is an explicit local-only action. */
public final class DemoController {
    public enum Focus { CHATS, HISTORY, COMPOSER }
    private final DemoScenario scenario;
    private final BiConsumer<String, String> submit;
    private final Map<String, Draft> drafts = new HashMap<>();
    private final Set<String> rejectedInput = new HashSet<>();
    private int selected;
    private Focus focus = Focus.CHATS;
    private int scroll;
    private boolean help;
    private boolean confirmQuit;
    private boolean running = true;
    private Character pendingHighSurrogate;
    private String status = "Synthetic data only. No Telegram connection.";

    public DemoController(DemoScenario scenario, BiConsumer<String, String> submit) {
        this.scenario = scenario;
        this.submit = submit;
    }

    public DemoScenario scenario() { return scenario; }
    public DemoScenario.Chat selectedChat() { return scenario.chats().get(selected); }
    public int selectedIndex() { return selected; }
    public Focus focus() { return focus; }
    public int scroll() { return scroll; }
    public boolean help() { return help; }
    public boolean confirmQuit() { return confirmQuit; }
    public boolean running() { return running; }
    public String status() { return status; }
    public Draft draft() { return drafts.getOrDefault(selectedChat().id(), Draft.empty()); }
    public boolean hasDrafts() { return drafts.values().stream().anyMatch(draft -> !draft.text().isEmpty()); }
    public void clampScroll(int maximum) { scroll = Math.max(0, Math.min(scroll, maximum)); }

    public void handle(KeyStroke key) {
        if (key.getKeyType() == KeyType.EOF) { running = false; return; }
        boolean quit = key.isCtrlDown() && key.getKeyType() == KeyType.Character
                && Character.toLowerCase(key.getCharacter()) == 'c';
        if (quit) {
            confirmQuit = hasDrafts();
            running = confirmQuit;
            return;
        }
        if (confirmQuit) {
            if (key.getKeyType() == KeyType.Character && !key.isCtrlDown()
                    && Character.toLowerCase(key.getCharacter()) == 'y') { running = false; }
            else if (key.getKeyType() == KeyType.Escape || (key.getKeyType() == KeyType.Character
                    && Character.toLowerCase(key.getCharacter()) == 'n')) { confirmQuit = false; }
            return;
        }
        if (key.getKeyType() == KeyType.F1) { help = !help; return; }
        if (help) {
            if (key.getKeyType() == KeyType.Escape) { help = false; }
            return;
        }
        if (key.getKeyType() != KeyType.Character) { pendingHighSurrogate = null; }
        switch (key.getKeyType()) {
            case Tab -> focus = Focus.values()[(focus.ordinal() + 1) % Focus.values().length];
            case ReverseTab -> focus = Focus.values()[(focus.ordinal() + 2) % Focus.values().length];
            case ArrowUp -> {
                if (focus == Focus.CHATS) { select(-1); }
                else if (focus == Focus.HISTORY) { scroll = Math.min(10_000, scroll + 1); }
            }
            case ArrowDown -> {
                if (focus == Focus.CHATS) { select(1); }
                else if (focus == Focus.HISTORY) { scroll = Math.max(0, scroll - 1); }
            }
            case PageUp -> scroll = Math.min(10_000, scroll + 10);
            case PageDown -> scroll = Math.max(0, scroll - 10);
            case F2 -> simulateSubmit();
            default -> { if (focus == Focus.COMPOSER) { edit(key); } }
        }
    }

    private void select(int direction) {
        selected = Math.max(0, Math.min(scenario.chats().size() - 1, selected + direction));
        scroll = 0;
    }

    private void simulateSubmit() {
        if (focus != Focus.COMPOSER) { status = "Tab to the composer before simulating submission."; return; }
        if (rejectedInput.contains(selectedChat().id())) {
            status = "Input was rejected. Edit and review the draft before F2; nothing submitted.";
            return;
        }
        if (draft().text().isBlank()) { status = "The draft is empty; nothing submitted."; return; }
        submit.accept(selectedChat().id(), draft().text());
        drafts.put(selectedChat().id(), Draft.empty());
        scroll = 0;
        status = "Simulated locally. Nothing was sent to Telegram.";
    }

    private void edit(KeyStroke key) {
        var draft = draft();
        var previous = draft;
        try {
            draft = switch (key.getKeyType()) {
                case Enter -> draft.insert("\n");
                case Backspace -> draft.backspace();
                case Delete -> draft.delete();
                case ArrowLeft -> draft.left();
                case ArrowRight -> draft.right();
                case Home -> draft.home();
                case End -> draft.end();
                case Character -> insertCharacter(draft, key);
                default -> draft;
            };
            drafts.put(selectedChat().id(), draft);
            if (!draft.text().equals(previous.text()) && rejectedInput.remove(selectedChat().id())) {
                status = "Draft edited after rejected input. Review it before simulating submission.";
            }
        } catch (IllegalArgumentException exception) {
            rejectedInput.add(selectedChat().id());
            status = "Draft limit: 16 KiB. Input rejected; edit and review before F2.";
        }
    }

    private Draft insertCharacter(Draft draft, KeyStroke key) {
        char character = key.getCharacter();
        if (key.isCtrlDown() || key.isAltDown() || Character.isISOControl(character)) { return draft; }
        if (Character.isHighSurrogate(character)) { pendingHighSurrogate = character; return draft; }
        if (Character.isLowSurrogate(character)) {
            if (pendingHighSurrogate == null) { return draft; }
            String pair = "" + pendingHighSurrogate + character;
            pendingHighSurrogate = null;
            return draft.insert(pair);
        }
        pendingHighSurrogate = null;
        return draft.insert(Character.toString(character));
    }
}
