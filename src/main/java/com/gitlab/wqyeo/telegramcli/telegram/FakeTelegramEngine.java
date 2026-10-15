package com.gitlab.wqyeo.telegramcli.telegram;

import com.gitlab.wqyeo.telegramcli.model.ChatMessage;
import com.gitlab.wqyeo.telegramcli.model.ConnectionStatus;
import com.gitlab.wqyeo.telegramcli.model.EngineEvent;
import java.util.Objects;
import java.util.function.Consumer;

/** Explicit offline demo only. Never authenticates, loads native libraries, or sends messages. */
public final class FakeTelegramEngine implements TelegramEngine {
    private Consumer<EngineEvent> events;
    private boolean closed;

    @Override
    public synchronized void start(Consumer<EngineEvent> receiver) {
        if (events != null || closed) {
            throw new IllegalStateException("Demo engine cannot be started again.");
        }
        events = Objects.requireNonNull(receiver, "receiver");
        events.accept(new EngineEvent.ConnectionChanged(ConnectionStatus.CONNECTING));
        events.accept(new EngineEvent.ConnectionChanged(ConnectionStatus.READY));
        events.accept(new EngineEvent.MessageReceived(new ChatMessage(
                "demo-saved-messages", "demo-message-1", "Demo user",
                "Synthetic message. No Telegram connection was made.")));
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            if (events != null) {
                events.accept(new EngineEvent.ConnectionChanged(ConnectionStatus.CLOSED));
            }
        }
    }
}
