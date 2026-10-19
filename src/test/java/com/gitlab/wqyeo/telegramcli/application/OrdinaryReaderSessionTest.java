package com.gitlab.wqyeo.telegramcli.application;

import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import static org.junit.jupiter.api.Assertions.*;
import com.gitlab.wqyeo.telegramcli.session.bridge.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The same facade/core runs against PUBLIC in-memory and PUBLIC pipe peers, never TDLib. */
@Timeout(20)
class OrdinaryReaderSessionTest {
    private BridgeCoordinator.Snapshot await(ReadOnlyPreviewSession session, Predicate<BridgeCoordinator.Snapshot> predicate) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do { var state = session.tick(); if (predicate.test(state)) { return state; } Thread.sleep(5); } while (System.nanoTime() < end);
        fail("PUBLIC ordinary reader did not reach expected state"); return null;
    }
    private void authorize(ReadOnlyPreviewSession session) throws Exception {
        for (int n = 0; n < 5; n++) {
            var prompt = await(session, s -> s.auth() != null && s.auth().step().input() && s.pendingRequests() == 0);
            session.advanceAuthorization();
            await(session, s -> s.auth() != null && !s.auth().token().equals(prompt.auth().token()));
        }
        await(session, s -> s.connection() == Phase.READY && s.pendingRequests() == 0 && s.chats().size() == 3);
        assertNull(session.latest().identity()); assertEquals(1, session.latest().reader().account().userId());
        assertEquals(0, session.latest().reader().chatId()); assertTrue(session.latest().reader().messages().isEmpty());
    }
    private void ordinaryFlow(ReadOnlyPreviewSession session) throws Exception {
        session.searchLoaded("alex"); assertEquals("LOADED_ONLY", session.latest().reader().searchCoverage());
        assertEquals(List.of(1001L), session.latest().reader().searchResults().stream().map(Chat::id).toList());
        session.selectChat(1001); await(session, s -> !s.reader().loading());
        assertEquals(25, session.latest().reader().messages().getLast().id());
        assertTrue(session.latest().reader().messages().stream().allMatch(m -> m.chatId() == 1001));
        assertTrue(session.olderPage()); await(session, s -> !s.reader().loading());
        assertEquals(15, session.latest().reader().messages().getLast().id());
        assertTrue(session.olderPage()); await(session, s -> !s.reader().loading());
        assertEquals(5, session.latest().reader().messages().size()); assertFalse(session.olderPage());
        session.selectChat(1002); await(session, s -> !s.reader().loading());
        assertEquals(25, session.latest().reader().messages().getLast().id());
        assertTrue(session.latest().reader().messages().stream().allMatch(m -> m.chatId() == 1002));
        assertThrows(BridgeException.class, () -> session.selectChat(-77));
        assertFalse(session.moreChats()); assertThrows(BridgeException.class, () -> session.savedPage(0));
        session.refresh(); await(session, s -> s.reader().chatId() == 1002 && s.pendingRequests() == 0);
        assertEquals(25, session.latest().reader().messages().getLast().id());
        assertFalse(session.latest().reader().nativeStopAccepted());
    }
    @Test void inMemoryFlowHasNoSavedAssumptionAndPreservesUpdatesAndRevocation() throws Exception {
        var session = SyntheticReadOnlySession.ordinaryReader();
        try (session) {
            authorize(session); ordinaryFlow(session);
            session.fixtureIncoming(1002, 26, "PUBLIC incoming"); assertEquals(26, session.latest().reader().messages().getLast().id());
            session.fixtureEdit(1002, 26, "PUBLIC edited", 0); assertEquals("PUBLIC edited", session.latest().reader().messages().getLast().text());
            session.fixtureRemove(1002, 26); assertTrue(session.latest().reader().messages().stream().noneMatch(m -> m.id() == 26));
            session.fixtureAuthorizationExpired(); assertNull(session.latest().reader().account());
            assertEquals(0, session.latest().reader().retainedMessages()); assertTrue(session.latest().chats().isEmpty());
        }
        assertTrue(session.latest().disposed()); assertFalse(session.latest().reader().nativeStopAccepted());
    }
    @Test void processFlowUsesVersionTwoForAuthDirectoryHistoryRefreshAndFixtureClose() throws Exception {
        var child = PublicProcessHarness.launch(SyntheticReadOnlySession.Scenario.FULL, "READER");
        var session = ProcessReadOnlySession.ordinaryReader(child, UUID.randomUUID(), ProcessBridgeTransport.Budgets.defaults());
        try (session) { authorize(session); ordinaryFlow(session); assertFalse(session.peerCleanlyClosed()); }
        assertTrue(session.peerCleanlyClosed()); assertFalse(child.isAlive()); assertTrue(session.latest().disposed());
        assertEquals(0, session.latest().reader().retainedMessages()); assertFalse(session.latest().reader().nativeStopAccepted());
    }
    @Test void processUpdateActuallyInsertsAnUnseenSelectedChatMessage() throws Exception {
        var child = PublicProcessHarness.launch(SyntheticReadOnlySession.Scenario.FULL, "READER_UPDATES");
        try (var session = ProcessReadOnlySession.ordinaryReader(child, UUID.randomUUID(), ProcessBridgeTransport.Budgets.defaults())) {
            authorize(session); session.selectChat(1001);
            await(session, s -> s.reader().messages().stream().anyMatch(m -> m.id() == 26));
            assertEquals("PUBLIC process incoming", session.latest().reader().messages().getLast().text());
        }
        assertFalse(child.isAlive());
    }
    @Test void readerRejectsLegacyTypedPeerWithoutDowngradeAndReapsIt() throws Exception {
        var child = PublicProcessHarness.launch(SyntheticReadOnlySession.Scenario.FULL, "NORMAL");
        ProcessReadOnlySession session = null;
        try {
            try {
                session = ProcessReadOnlySession.ordinaryReader(child, UUID.randomUUID(), ProcessBridgeTransport.Budgets.defaults());
                var attached = session;
                assertThrows(BridgeException.class, () -> await(attached, s -> false));
            } catch (BridgeException expectedStartupRefusal) { assertEquals(BridgeException.Code.PROTOCOL_INVALID, expectedStartupRefusal.code()); }
        } finally { if (session != null) { session.close(); } }
        assertFalse(child.isAlive());
    }
}
