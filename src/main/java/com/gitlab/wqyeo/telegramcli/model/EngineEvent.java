package com.gitlab.wqyeo.telegramcli.model;

import java.util.Objects;

/** Application-owned events; generated TDLib classes must stay inside its adapter. */
public sealed interface EngineEvent {
    record ConnectionChanged(ConnectionStatus status) implements EngineEvent {
        public ConnectionChanged {
            Objects.requireNonNull(status, "status");
        }
    }

    record MessageReceived(ChatMessage message) implements EngineEvent {
        public MessageReceived {
            Objects.requireNonNull(message, "message");
        }
    }
}
