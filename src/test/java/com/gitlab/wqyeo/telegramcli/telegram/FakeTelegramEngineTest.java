package com.gitlab.wqyeo.telegramcli.telegram;

import static org.junit.jupiter.api.Assertions.*;

import com.gitlab.wqyeo.telegramcli.model.ConnectionStatus;
import com.gitlab.wqyeo.telegramcli.model.EngineEvent;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class FakeTelegramEngineTest {
    @Test void emitsSyntheticEventsInOrderAndClosesOnce() {
        var events = new ArrayList<EngineEvent>();
        var engine = new FakeTelegramEngine();
        engine.start(events::add);
        engine.close();
        engine.close();
        assertEquals(4, events.size());
        assertEquals(new EngineEvent.ConnectionChanged(ConnectionStatus.CONNECTING), events.get(0));
        assertEquals(new EngineEvent.ConnectionChanged(ConnectionStatus.READY), events.get(1));
        var message = assertInstanceOf(EngineEvent.MessageReceived.class, events.get(2)).message();
        assertTrue(message.chatId().startsWith("demo-"));
        assertTrue(message.text().contains("Synthetic"));
        assertEquals(new EngineEvent.ConnectionChanged(ConnectionStatus.CLOSED), events.get(3));
        assertThrows(IllegalStateException.class, () -> engine.start(events::add));
    }

    @Test void closeBeforeStartIsSafeButPreventsRestart() {
        var engine = new FakeTelegramEngine();
        engine.close();
        assertThrows(IllegalStateException.class, () -> engine.start(event -> {}));
    }

    @Test void rejectsMissingReceiver() {
        try (var engine = new FakeTelegramEngine()) {
            assertThrows(NullPointerException.class, () -> engine.start(null));
        }
    }
}
