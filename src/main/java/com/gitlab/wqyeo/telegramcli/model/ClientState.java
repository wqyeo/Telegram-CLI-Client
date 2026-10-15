package com.gitlab.wqyeo.telegramcli.model;

import java.util.List;
import java.util.Objects;

public record ClientState(ConnectionStatus connection, List<ChatMessage> messages) {
    public ClientState {
        Objects.requireNonNull(connection, "connection");
        messages = List.copyOf(messages);
    }

    public static ClientState initial() {
        return new ClientState(ConnectionStatus.OFFLINE, List.of());
    }
}
