package com.gitlab.wqyeo.telegramcli.session.bridge;

import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** PUBLIC values only. No worker, profile, provider, SDK or account is constructed. */
class OrdinaryReaderTest {
    private final UUID generation = UUID.randomUUID();
    private final AtomicLong nanos = new AtomicLong();
    private final BridgeCoordinator core = BridgeCoordinator.publicReader(generation,
            Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC), nanos::get);
    private long sequence;
    private Frame copy(Frame f) throws Exception {
        var out = new ByteArrayOutputStream(); BridgeProtocol.write(out, f);
        return BridgeProtocol.read(new ByteArrayInputStream(out.toByteArray()));
    }
    private void post(Payload p, long id) throws Exception { core.offer(copy(new Frame(2, generation, ++sequence, id, p))); core.drain(); }
    private void post(Payload p) throws Exception { post(p, 0); }
    private void ready() throws Exception {
        post(new Auth(Step.READY, UUID.randomUUID(), 0, Unsupported.NONE)); post(new Account(1, "PUBLIC operator"));
        post(new Connection(Phase.READY)); Frame q = core.directoryPage(true);
        post(new Directory(List.of(new Chat(101, "PUBLIC Alex", ChatKind.USER), new Chat(102, "PUBLIC Blair", ChatKind.USER),
                new Chat(-1, "PUBLIC group", ChatKind.GROUP)), Continuation.end()), q.requestId());
    }
    private ReaderMessage message(long chat, long id, String text) { return ReaderMessage.project(chat, id, "PUBLIC sender", text, id, 0, false, Content.TEXT); }
    private void page(Frame request, Continuation next, ReaderMessage... rows) throws Exception {
        HistoryRequest q = (HistoryRequest) request.payload();
        post(new HistoryPage(q.chatId(), q.selection(), q.window(), List.of(rows), next), request.requestId());
    }
    @Test void versionTwoRoundTripsAllNewShapesAndRejectsMixedVersions() throws Exception {
        var m = message(101, 7, "PUBLIC private-shaped text");
        var payloads = List.<Payload>of(new Account(1, "PUBLIC operator"), new Directory(List.of(new Chat(101, "PUBLIC", ChatKind.USER)), Continuation.unknown()),
                new HistoryRequest(101, 1, 1, 0, 10), new HistoryPage(101, 1, 1, List.of(m), new Continuation(ContinuationState.AVAILABLE, 7)),
                new MessageUpdate(Update.NEW, m), new ChatRemoved(101, List.of(7L), true));
        var mapper = JsonMapper.builder().build();
        for (var p : payloads) {
            long id = p instanceof Account || p instanceof MessageUpdate || p instanceof ChatRemoved ? 0 : 1;
            var f = new Frame(2, generation, 1, id, p); assertEquals(f, copy(f));
            assertFalse(p.toString().contains("operator")); assertEquals("\"[REDACTED bridge payload]\"", mapper.writeValueAsString(p));
            assertThrows(IllegalArgumentException.class, () -> new Frame(generation, 1, id, p));
        }
        assertThrows(IllegalArgumentException.class, () -> new Frame(2, generation, 1, 1, new Messages(List.of(), 0)));
        assertThrows(IllegalArgumentException.class, () -> new Frame(2, generation, 1, 1, new Request(Target.SAVED_MESSAGES, 0, 1)));
        var out = new ByteArrayOutputStream(); BridgeProtocol.write(out, new Frame(2, generation, 1, 0, new Account(1, "PUBLIC")));
        byte[] bytes = out.toByteArray(); bytes[7] = '1';
        assertThrows(BridgeException.class, () -> BridgeProtocol.read(new ByteArrayInputStream(bytes)));
        assertThrows(BridgeException.class, () -> core.offer(new Frame(generation, 1, 0, new Connection(Phase.OFFLINE))));
        assertTrue(core.latest().disposed());
    }
    @Test void chatSelectionAndWindowIdentityPreventCrossChatOrSupersededReplies() throws Exception {
        ready(); Frame a = core.selectChat(101), b = core.selectChat(102);
        page(a, Continuation.end(), message(101, 7, "PUBLIC A")); assertTrue(core.latest().reader().messages().isEmpty());
        page(b, new Continuation(ContinuationState.AVAILABLE, 7), message(102, 7, "PUBLIC B"));
        assertEquals("PUBLIC B", core.latest().reader().messages().getFirst().text());
        Frame older = core.historyPage(false), latest = core.historyPage(true);
        page(older, Continuation.end(), message(102, 2, "PUBLIC older"));
        page(latest, Continuation.end(), message(102, 7, "PUBLIC B"));
        assertEquals(7, core.latest().reader().messages().getFirst().id());
        assertThrows(BridgeException.class, () -> core.selectChat(-1));
        assertEquals(102, core.latest().reader().chatId());
    }
    @Test void sameNumericIdInTwoChatsDoesNotShareEditOrRemovalState() throws Exception {
        ready(); page(core.selectChat(101), Continuation.end(), message(101, 7, "PUBLIC A"));
        page(core.selectChat(102), Continuation.end(), message(102, 7, "PUBLIC B"));
        assertEquals(2, core.latest().reader().retainedMessages());
        post(new MessageUpdate(Update.EDIT, message(101, 7, "PUBLIC edited A")));
        assertEquals("PUBLIC B", core.latest().reader().messages().getFirst().text());
        post(new ChatRemoved(101, List.of(7L), false));
        assertEquals(1, core.latest().reader().retainedMessages()); assertEquals("PUBLIC B", core.latest().reader().messages().getFirst().text());
    }
    @Test void newMessagesInsertAtLatestButDoNotMoveAnOlderHistoryAnchor() throws Exception {
        ready(); page(core.selectChat(101), new Continuation(ContinuationState.AVAILABLE, 7), message(101, 7, "PUBLIC initial"));
        post(new MessageUpdate(Update.NEW, message(101, 8, "PUBLIC incoming")));
        assertEquals(List.of(7L, 8L), core.latest().reader().messages().stream().map(ReaderMessage::id).toList());
        page(core.historyPage(true), new Continuation(ContinuationState.AVAILABLE, 7), message(101, 8, "PUBLIC incoming"), message(101, 7, "PUBLIC initial"));
        page(core.historyPage(false), Continuation.end(), message(101, 2, "PUBLIC older"));
        post(new MessageUpdate(Update.NEW, message(101, 9, "PUBLIC newest")));
        assertEquals(2, core.latest().reader().messages().getFirst().id()); assertEquals(1, core.latest().reader().incomingIndicator());
        post(new MessageUpdate(Update.NEW, message(101, 9, "PUBLIC duplicate"))); assertEquals(1, core.latest().reader().incomingIndicator());
    }
    @Test void editsRemovalsAndExpiryInvalidatePagesBeforeTheyCanRestorePrivateText() throws Exception {
        ready(); page(core.selectChat(101), Continuation.end(), message(101, 7, "PUBLIC old"));
        Frame stale = core.historyPage(true); post(new MessageUpdate(Update.EDIT, message(101, 7, "PUBLIC edited")));
        page(stale, Continuation.end(), message(101, 7, "PUBLIC stale"));
        assertFalse(core.latest().reader().loading()); assertEquals(ContinuationState.UNKNOWN, core.latest().reader().next().state());
        Frame deleted = core.historyPage(true); post(new ChatRemoved(101, List.of(7L), false));
        page(deleted, Continuation.end(), message(101, 7, "PUBLIC resurrected")); assertEquals(0, core.latest().reader().retainedMessages());
        var expired = new ReaderMessage(101, 8, "PUBLIC", "PUBLIC expired", 8, 999, false, false, Content.TEXT);
        page(core.historyPage(true), Continuation.end(), expired); assertEquals(0, core.latest().reader().retainedMessages());
    }
    @Test void pendingCancellationDeadlineUnknownAndConnectionLossDoNotReportHistoryEnd() throws Exception {
        ready(); Frame q = core.selectChat(101); core.control(Action.CANCEL, q.requestId());
        page(q, Continuation.end(), message(101, 1, "PUBLIC late")); assertEquals(ContinuationState.UNKNOWN, core.latest().reader().next().state());
        Frame timeout = core.historyPage(true); nanos.set(Duration.ofSeconds(11).toNanos()); core.drain();
        page(timeout, Continuation.end(), message(101, 1, "PUBLIC late")); assertEquals(ContinuationState.UNKNOWN, core.latest().reader().next().state());
        post(new Connection(Phase.OFFLINE)); assertThrows(BridgeException.class, () -> core.historyPage(true));
        assertEquals(ContinuationState.UNKNOWN, core.latest().reader().next().state());
    }
    @Test void loadedSearchAndGlobalCacheBoundsRemainExplicitAndRevocationPurgesEverything() throws Exception {
        ready(); core.searchLoaded("blair"); assertEquals("LOADED_ONLY", core.latest().reader().searchCoverage());
        assertEquals(102, core.latest().reader().searchResults().getFirst().id());
        page(core.selectChat(101), Continuation.end(), message(101, 1, "PUBLIC"));
        for (long id = 2; id < 140; id++) { post(new MessageUpdate(Update.NEW, message(101, id, "PUBLIC"))); }
        assertEquals(MAX_MESSAGES, core.latest().reader().retainedMessages()); assertEquals(MESSAGE_PAGE, core.latest().reader().messages().size());
        post(new Connection(Phase.AUTH_EXPIRED));
        assertTrue(core.latest().chats().isEmpty()); assertTrue(core.latest().reader().messages().isEmpty());
        assertEquals(0, core.latest().reader().retainedMessages()); assertEquals("", core.latest().reader().searchQuery());
        assertNull(core.latest().reader().account()); assertEquals(0, core.latest().reader().chatId());
        core.close(); assertTrue(core.latest().disposed()); assertFalse(core.latest().reader().nativeStopAccepted());
    }
    @Test void displayProjectionIsUnicodeBoundedExplicitlyTruncatedAndRedacted() throws Exception {
        var m = ReaderMessage.project(101, 1, "🙂".repeat(100), "🙂".repeat(1000), 1, 0, false, Content.TEXT);
        assertTrue(m.truncated()); assertEquals(512, m.text().codePointCount(0, m.text().length()));
        assertEquals(32, m.sender().codePointCount(0, m.sender().length()));
        assertEquals(new Frame(2, generation, 1, 0, new MessageUpdate(Update.NEW, m)), copy(new Frame(2, generation, 1, 0, new MessageUpdate(Update.NEW, m))));
        assertFalse(JsonMapper.builder().build().writeValueAsString(m).contains("🙂"));
        assertThrows(IllegalArgumentException.class, () -> ReaderMessage.project(101, 1, "", "\ud800", 0, 0, false, Content.TEXT));
        assertEquals("[Unsupported content]", ReaderMessage.project(101, 1, "", "ignored", 0, 0, false, Content.UNSUPPORTED).text());
    }
}
