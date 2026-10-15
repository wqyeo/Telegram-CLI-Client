package com.gitlab.wqyeo.telegramcli.model;

import java.util.Objects;

/** IDs are opaque strings; message identity is scoped to a chat. */
public record ChatMessage(String chatId, String messageId, String sender, String text) {
    public ChatMessage {
        Objects.requireNonNull(chatId, "chatId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(text, "text");
        if (chatId.isBlank() || messageId.isBlank()) {
            throw new IllegalArgumentException("Message identifiers cannot be blank.");
        }
    }
}
