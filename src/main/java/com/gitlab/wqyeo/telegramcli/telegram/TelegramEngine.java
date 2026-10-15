package com.gitlab.wqyeo.telegramcli.telegram;

import com.gitlab.wqyeo.telegramcli.model.EngineEvent;
import java.util.function.Consumer;

/** An ordered event source. Future real adapters also own their shutdown handshake. */
public interface TelegramEngine extends AutoCloseable {
    void start(Consumer<EngineEvent> events);

    @Override
    void close();
}
