package com.gitlab.wqyeo.telegramcli.application;

import com.gitlab.wqyeo.telegramcli.model.ChatMessage;
import java.util.ArrayList;
import java.util.List;

/** Public, synthetic fixtures only: these are not exports from a Telegram account. */
public record DemoScenario(List<Chat> chats, List<ChatMessage> messages) {
    public record Chat(String id, String title) {}

    public DemoScenario {
        chats = List.copyOf(chats);
        messages = List.copyOf(messages);
        if (chats.isEmpty()) {
            throw new IllegalArgumentException("A demo requires a chat.");
        }
    }

    public static DemoScenario standard() {
        var chats = List.of(new Chat("demo-saved", "Saved Messages (demo)"),
                new Chat("demo-project", "Project (demo)"), new Chat("demo-unicode", "Unicode (demo)"));
        var messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage("demo-saved", "1", "Demo assistant",
                "Welcome to the offline prototype.\nNothing here is transmitted or persisted."));
        messages.add(new ChatMessage("demo-project", "1", "Demo colleague",
                "Switch chats with the arrow keys, then Tab to the composer.\nEnter adds a newline; F2 simulates a send."));
        for (int i = 2; i <= 18; i++) {
            messages.add(new ChatMessage("demo-project", Integer.toString(i), "Demo colleague",
                    "Synthetic history item " + i + ". Long messages wrap within the conversation pane. ".repeat(2)));
        }
        messages.add(new ChatMessage("demo-unicode", "1", "Demo user",
                "你好 · café · e\u0301 · 👩‍💻\nUnicode width depends on your terminal and font."));
        messages.add(new ChatMessage("demo-unicode", "2", "Safety example",
                "Control sequences are displayed safely: \u001b[2J and \u0007, not executed."));
        return new DemoScenario(chats, messages);
    }
}
