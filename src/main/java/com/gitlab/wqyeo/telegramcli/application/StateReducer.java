package com.gitlab.wqyeo.telegramcli.application;

import com.gitlab.wqyeo.telegramcli.model.ClientState;
import com.gitlab.wqyeo.telegramcli.model.EngineEvent;
import java.util.ArrayList;

/** Pure, bounded reduction of the currently supported offline events. */
public final class StateReducer {
    private final int historyLimit;

    public StateReducer(int historyLimit) {
        if (historyLimit < 1 || historyLimit > 1000) {
            throw new IllegalArgumentException("Invalid history limit.");
        }
        this.historyLimit = historyLimit;
    }

    public ClientState reduce(ClientState state, EngineEvent event) {
        return switch (event) {
            case EngineEvent.ConnectionChanged changed -> new ClientState(changed.status(), state.messages());
            case EngineEvent.MessageReceived received -> {
                var message = received.message();
                var messages = new ArrayList<>(state.messages());
                messages.removeIf(old -> old.chatId().equals(message.chatId())
                        && old.messageId().equals(message.messageId()));
                messages.add(message);
                int start = Math.max(0, messages.size() - historyLimit);
                yield new ClientState(state.connection(), messages.subList(start, messages.size()));
            }
        };
    }
}
