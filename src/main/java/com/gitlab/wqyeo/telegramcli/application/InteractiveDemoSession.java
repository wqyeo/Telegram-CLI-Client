package com.gitlab.wqyeo.telegramcli.application;

import com.gitlab.wqyeo.telegramcli.model.ChatMessage;
import com.gitlab.wqyeo.telegramcli.model.ClientState;
import com.gitlab.wqyeo.telegramcli.model.ConnectionStatus;
import com.gitlab.wqyeo.telegramcli.model.EngineEvent;
import java.util.concurrent.TimeUnit;

/** UI-only simulation; deliberately has no real send or authentication capability. */
public final class InteractiveDemoSession implements AutoCloseable {
    private final DemoScenario scenario;
    private final OrderedStateStore store;
    private long sequence;
    private boolean closed;

    public InteractiveDemoSession(DemoScenario scenario, int historyLimit) throws Exception {
        this.scenario = scenario;
        store = new OrderedStateStore(historyLimit);
        try {
            store.accept(new EngineEvent.ConnectionChanged(ConnectionStatus.READY));
            for (var message : scenario.messages()) {
                store.accept(new EngineEvent.MessageReceived(message));
            }
            store.snapshot().get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            store.close();
            throw exception;
        }
    }

    public DemoScenario scenario() { return scenario; }
    public ClientState latest() { return store.latest(); }

    public void simulateSubmit(String chatId, String text) {
        if (closed || scenario.chats().stream().noneMatch(chat -> chat.id().equals(chatId))) {
            throw new IllegalStateException("Unknown or closed demo chat.");
        }
        store.accept(new EngineEvent.MessageReceived(new ChatMessage(chatId,
                "demo-local-" + (++sequence), "You (simulated)", text)));
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                store.accept(new EngineEvent.ConnectionChanged(ConnectionStatus.CLOSED));
            } finally {
                store.close();
            }
        }
    }
}
