package com.gitlab.wqyeo.telegramcli.application;

import static org.junit.jupiter.api.Assertions.*;

import com.gitlab.wqyeo.telegramcli.model.ChatMessage;
import com.gitlab.wqyeo.telegramcli.model.ConnectionStatus;
import com.gitlab.wqyeo.telegramcli.model.EngineEvent;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class OrderedStateStoreTest {
    private EngineEvent message(String chat, String id, String text) {
        return new EngineEvent.MessageReceived(new ChatMessage(chat, id, "Synthetic user", text));
    }

    @Test void barrierObservesOrderedEventsAndBoundedHistory() throws Exception {
        try (var store = new OrderedStateStore(2)) {
            store.accept(new EngineEvent.ConnectionChanged(ConnectionStatus.CONNECTING));
            store.accept(message("chat", "1", "first"));
            store.accept(message("chat", "2", "second"));
            store.accept(message("chat", "3", "third"));
            store.accept(new EngineEvent.ConnectionChanged(ConnectionStatus.READY));
            var state = store.snapshot().get(2, TimeUnit.SECONDS);
            assertEquals(ConnectionStatus.READY, state.connection());
            assertEquals(java.util.List.of("2", "3"), state.messages().stream().map(ChatMessage::messageId).toList());
            assertThrows(UnsupportedOperationException.class, () -> state.messages().clear());
        }
    }

    @Test void messageIdentityIncludesChatAndPreservesLargeIds() throws Exception {
        try (var store = new OrderedStateStore(10)) {
            store.accept(message("one", "9223372036854775807", "before"));
            store.accept(message("two", "9223372036854775807", "other chat"));
            store.accept(message("one", "9223372036854775807", "after"));
            var messages = store.snapshot().get(2, TimeUnit.SECONDS).messages();
            assertEquals(2, messages.size());
            assertEquals("other chat", messages.getFirst().text());
            assertEquals("after", messages.getLast().text());
        }
    }

    @Test void snapshotsAreImmutableAndDoNotChangeAfterLaterEvents() throws Exception {
        try (var store = new OrderedStateStore(10)) {
            var initial = store.snapshot().get(2, TimeUnit.SECONDS);
            store.accept(message("chat", "1", "new"));
            assertEquals(1, store.snapshot().get(2, TimeUnit.SECONDS).messages().size());
            assertTrue(initial.messages().isEmpty());
        }
    }

    @Test void closedStoreRejectsRatherThanDropsEvents() {
        var store = new OrderedStateStore(2);
        store.close();
        assertThrows(RejectedExecutionException.class, () -> store.accept(message("chat", "1", "new")));
        assertThrows(RejectedExecutionException.class, store::snapshot);
        store.close();
    }

    @Test void rejectsInvalidLimitsAndNullEvents() {
        assertThrows(IllegalArgumentException.class, () -> new OrderedStateStore(0));
        try (var store = new OrderedStateStore(1)) {
            assertThrows(NullPointerException.class, () -> store.accept(null));
        }
    }
}
