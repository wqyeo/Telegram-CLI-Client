package com.gitlab.wqyeo.telegramcli.session.bridge;

import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BridgeCoordinatorTest {
    private final UUID generation = UUID.randomUUID();
    private final AtomicLong nanos = new AtomicLong(), seconds = new AtomicLong(1000);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(seconds.get()); }
    };
    private final BridgeCoordinator bridge = new BridgeCoordinator(generation, clock, nanos::get);
    private long sequence;
    private final String marker = "PRIVATE_PUBLIC_SYNTHETIC";
    private Auth auth(Step step) { return new Auth(step, UUID.randomUUID(), 0, Unsupported.NONE); }
    private void post(Payload payload, long request) throws Exception {
        var out = new ByteArrayOutputStream(); BridgeProtocol.write(out, new Frame(generation, ++sequence, request, payload));
        bridge.offer(BridgeProtocol.read(new ByteArrayInputStream(out.toByteArray()))); bridge.drain();
    }
    private void post(Payload payload) throws Exception { post(payload, 0); }
    private void ready() throws Exception { post(auth(Step.READY)); post(new Identity(1, 42, marker)); post(new Connection(Phase.READY)); }
    private Message message(long id, String text, long expiry) { return new Message(id, marker, text, id, expiry, false); }
    private void page(Message... messages) throws Exception {
        Frame request = bridge.request(Target.SAVED_MESSAGES, 0, 10); post(new Messages(List.of(messages), 0), request.requestId());
    }

    @Test void idleAndStaleGenerationsKeepSnapshotIdentityWithoutEnqueueing() throws Exception {
        var initial = bridge.latest(); assertSame(initial, bridge.drain());
        for (int i = 0; i < 1000; i++) { bridge.offer(new Frame(UUID.randomUUID(), 1, 0, new Connection(Phase.OFFLINE))); }
        assertEquals(0, bridge.queued()); assertSame(initial, bridge.drain());
        ready(); var current = bridge.latest(); assertSame(current, bridge.drain()); assertSame(current, bridge.latest());
    }

    @Test void outOfOrderDuplicatesAndUnissuedRepliesFailClosedWithoutPrivateState() throws Exception {
        ready(); page(message(1, marker, 0));
        bridge.offer(new Frame(generation, sequence, 0, new Connection(Phase.OFFLINE)));
        assertEquals(BridgeException.Code.STATE_INVALID, assertThrows(BridgeException.class, bridge::drain).code());
        assertTrue(bridge.latest().disposed()); assertNull(bridge.latest().identity()); assertTrue(bridge.latest().messages().isEmpty());
        var other = new BridgeCoordinator(generation);
        other.offer(new Frame(generation, 1, 999, new Chats(List.of(), 0)));
        assertThrows(BridgeException.class, other::drain); assertTrue(other.latest().disposed());
    }

    @Test void onlyOneSubmissionPerCurrentTokenAndRepeatedPromptCannotRearmIt() throws Exception {
        Auth phone = auth(Step.PHONE); post(phone);
        assertThrows(BridgeException.class, () -> bridge.authenticate(new byte[] {(byte) 255}));
        byte[] old;
        try (var value = bridge.authenticate(marker.getBytes(StandardCharsets.UTF_8))) {
            var output = new ByteArrayOutputStream(); AuthTransfer.write(output, value); old = output.toByteArray();
        }
        assertEquals(1, bridge.latest().pendingRequests());
        assertThrows(BridgeException.class, () -> bridge.authenticate(marker.getBytes(StandardCharsets.UTF_8)));
        post(phone); // Identical repeated metadata doesn't reset the consumed flag.
        assertThrows(BridgeException.class, () -> bridge.authenticate(marker.getBytes(StandardCharsets.UTF_8)));
        Auth code = auth(Step.CODE); post(code);
        assertThrows(BridgeException.class, () -> AuthTransfer.read(new ByteArrayInputStream(old), generation, 2, code.token(), Step.CODE));
        try (var value = bridge.authenticate(marker.getBytes(StandardCharsets.UTF_8))) {
            assertEquals(code.token(), value.token()); assertEquals(2, value.requestId());
        }
        Arrays.fill(old, (byte) 0);
    }

    @Test void retryDelaySurvivesAnEarlyFreshPromptAndNeverResubmitsAutomatically() throws Exception {
        post(auth(Step.PHONE)); try (var value = bridge.authenticate(marker.getBytes(StandardCharsets.UTF_8))) { assertNotNull(value); }
        post(new Auth(Step.RETRY, UUID.randomUUID(), 30, Unsupported.NONE)); post(auth(Step.PHONE));
        assertEquals(30, bridge.latest().retryAfterSeconds());
        assertEquals(BridgeException.Code.TIMEOUT, assertThrows(BridgeException.class, () -> bridge.authenticate(marker.getBytes(StandardCharsets.UTF_8))).code());
        nanos.addAndGet(Duration.ofSeconds(31).toNanos()); bridge.drain();
        assertEquals(0, bridge.latest().pendingRequests()); assertEquals(0, bridge.latest().retryAfterSeconds());
        try (var value = bridge.authenticate(marker.getBytes(StandardCharsets.UTF_8))) { assertNotNull(value); }
    }

    @Test void humanWaitingHasNoStartupTimeoutButSubmittedRequestHasIndependentDeadline() throws Exception {
        post(auth(Step.PHONE)); var prompt = bridge.latest();
        nanos.addAndGet(Duration.ofHours(1).toNanos()); assertSame(prompt, bridge.drain());
        try (var value = bridge.authenticate(marker.getBytes(StandardCharsets.UTF_8))) { assertNotNull(value); }
        nanos.addAndGet(Duration.ofSeconds(31).toNanos());
        assertEquals(BridgeException.Code.TIMEOUT, assertThrows(BridgeException.class, bridge::drain).code());
        assertEquals(BridgeException.Code.TIMEOUT, bridge.latest().failure()); assertTrue(bridge.latest().disposed());
    }

    @Test void finiteQueueAndPendingWindowFailExplicitlyRatherThanDroppingTransitions() throws Exception {
        for (int i = 1; i <= BridgeCoordinator.QUEUE_CAPACITY; i++) { bridge.offer(new Frame(generation, i, 0, new Connection(Phase.OFFLINE))); }
        assertEquals(BridgeCoordinator.QUEUE_CAPACITY, bridge.queued());
        assertEquals(BridgeException.Code.OVERFLOW, assertThrows(BridgeException.class,
                () -> bridge.offer(new Frame(generation, 257, 0, new Connection(Phase.OFFLINE)))).code());
        assertTrue(bridge.latest().disposed()); assertEquals(0, bridge.queued());
        var other = new BridgeCoordinator(generation);
        other.offer(new Frame(generation, 1, 0, auth(Step.READY))); other.offer(new Frame(generation, 2, 0, new Identity(1, 42, marker))); other.drain();
        for (int i = 0; i < BridgeCoordinator.MAX_PENDING; i++) { other.request(Target.CHATS, 0, 1); }
        assertEquals(BridgeCoordinator.MAX_PENDING, other.latest().pendingRequests());
        assertEquals(BridgeException.Code.OVERFLOW, assertThrows(BridgeException.class, () -> other.request(Target.CHATS, 0, 1)).code());
        assertTrue(other.latest().disposed()); assertEquals(0, other.latest().pendingRequests());
    }

    @Test void cancellationAndResyncIgnoreLateRepliesButRejectWrongTarget() throws Exception {
        ready(); Frame old = bridge.request(Target.CHATS, 0, 20);
        bridge.control(Action.CANCEL, old.requestId()); post(new Chats(List.of(new Chat(1, marker, ChatKind.USER)), 0), old.requestId());
        assertTrue(bridge.latest().chats().isEmpty());
        Frame next = bridge.request(Target.CHATS, 0, 20); bridge.control(Action.RESYNC, 0);
        post(new Chats(List.of(new Chat(2, marker, ChatKind.USER)), 0), next.requestId()); assertTrue(bridge.latest().chats().isEmpty());
        Frame saved = bridge.request(Target.SAVED_MESSAGES, 0, 10);
        postFailure(new Chats(List.of(), 0), saved.requestId()); assertTrue(bridge.latest().disposed());
    }
    private void postFailure(Payload payload, long request) throws Exception {
        bridge.offer(new Frame(generation, ++sequence, request, payload)); assertThrows(BridgeException.class, bridge::drain);
    }

    @Test void paginationCachesHaveHardCeilingsAndTheActivePageRemainsVisible() throws Exception {
        ready();
        for (int page = 0; page < 7; page++) {
            long start = page * 20L + 1; Frame request = bridge.request(Target.CHATS, page, 20);
            var rows = LongStream.range(start, start + 20).mapToObj(id -> new Chat(-id, marker, ChatKind.GROUP)).toList();
            post(new Chats(rows, page + 1), request.requestId()); assertTrue(bridge.latest().chats().size() <= MAX_CHATS);
        }
        assertEquals(MAX_CHATS, bridge.latest().chats().size());
        for (int page = 0; page < 13; page++) {
            long start = page * 10L + 1; Frame request = bridge.request(Target.SAVED_MESSAGES, page, 10);
            var rows = LongStream.range(start, start + 10).mapToObj(id -> message(id, marker, 0)).toList();
            post(new Messages(rows, page + 1), request.requestId());
            assertTrue(bridge.latest().retainedMessages() <= MAX_MESSAGES); assertEquals(10, bridge.latest().messages().size());
        }
        assertEquals(MAX_MESSAGES, bridge.latest().retainedMessages());
        assertEquals(121, bridge.latest().messages().getFirst().id()); assertEquals(130, bridge.latest().messages().getLast().id());
    }

    @Test void editsDeletionsAndExpiryPurgeVisibleAndCachedTextAndInvalidateOldPages() throws Exception {
        ready(); page(message(1, "old", 0), message(2, "retained", 0), message(3, "expires", 1002));
        Frame oldEdit = bridge.request(Target.SAVED_MESSAGES, 0, 10);
        post(new Changed(new Message(1, marker, "edited", 1, 0, true)));
        post(new Messages(List.of(message(1, "old", 0)), 0), oldEdit.requestId());
        assertEquals("edited", bridge.latest().messages().getFirst().text());
        Frame oldDeletion = bridge.request(Target.SAVED_MESSAGES, 0, 10); post(new Removed(List.of(1L), false));
        post(new Messages(List.of(message(1, "resurrected", 0)), 0), oldDeletion.requestId());
        assertTrue(bridge.latest().messages().stream().noneMatch(value -> value.id() == 1));
        Frame oldExpiry = bridge.request(Target.SAVED_MESSAGES, 0, 10); seconds.set(1003);
        post(new Messages(List.of(message(3, "old-expiry", 0)), 0), oldExpiry.requestId());
        assertEquals(1, bridge.latest().retainedMessages()); assertEquals(2, bridge.latest().messages().getFirst().id());
        post(new Changed(message(1, "unknown deleted edit", 0))); assertEquals(1, bridge.latest().retainedMessages());
    }

    @Test void authorizationExpiryPurgesAllPrivateStateAndPreventsLatePageRestoration() throws Exception {
        ready(); page(message(1, marker, 0)); Frame old = bridge.request(Target.SAVED_MESSAGES, 0, 10);
        assertEquals("\"[REDACTED bridge snapshot]\"", JsonMapper.builder().build().writeValueAsString(bridge.latest()));
        assertFalse(bridge.latest().toString().contains(marker));
        post(new Connection(Phase.AUTH_EXPIRED));
        post(new Messages(List.of(message(1, marker, 0)), 0), old.requestId());
        assertNull(bridge.latest().identity()); assertTrue(bridge.latest().messages().isEmpty()); assertEquals(0, bridge.latest().retainedMessages());
        assertThrows(BridgeException.class, () -> bridge.request(Target.CHATS, 0, 1));
        post(auth(Step.PHONE)); assertEquals(Step.PHONE, bridge.latest().auth().step());
    }

    @Test void dataDeadlineIsMonotonicAndLateDataDoesNotImplicitlyRetry() throws Exception {
        ready(); Frame request = bridge.request(Target.CHATS, 0, 20);
        seconds.set(-1000); // Wall clock changes do not extend network-request deadlines.
        nanos.addAndGet(Duration.ofSeconds(11).toNanos()); bridge.drain();
        assertEquals(0, bridge.latest().pendingRequests()); assertEquals(BridgeData.Error.NOT_AVAILABLE, bridge.latest().lastError());
        assertFalse(bridge.latest().disposed()); post(new Chats(List.of(new Chat(1, marker, ChatKind.USER)), 0), request.requestId());
        assertTrue(bridge.latest().chats().isEmpty()); assertEquals(0, bridge.latest().pendingRequests());
    }

    @ParameterizedTest @ValueSource(longs = {9_999_999_999L, 10_000_000_000L, 11_000_000_000L})
    void queuedDataCannotConsumeItsDeadlineBeforeItIsChecked(long elapsed) throws Exception {
        ready(); Frame request = bridge.request(Target.CHATS, 0, 20); nanos.set(elapsed);
        bridge.offer(new Frame(generation, ++sequence, request.requestId(), new Chats(List.of(new Chat(1, marker, ChatKind.USER)), 0)));
        var state = bridge.drain();
        assertEquals(0, state.pendingRequests()); assertFalse(state.disposed());
        if (elapsed < Duration.ofSeconds(10).toNanos()) { assertEquals(1, state.chats().size()); assertNull(state.lastError()); }
        else { assertTrue(state.chats().isEmpty()); assertEquals(BridgeData.Error.NOT_AVAILABLE, state.lastError()); }
    }

    @ParameterizedTest @ValueSource(longs = {29_999_999_999L, 30_000_000_000L, 31_000_000_000L})
    void queuedAuthCannotConsumeItsDeadlineBeforeItIsChecked(long elapsed) throws Exception {
        post(auth(Step.PHONE)); long request;
        try (var submission = bridge.authenticate(marker.getBytes(StandardCharsets.UTF_8))) { request = submission.requestId(); }
        nanos.set(elapsed); bridge.offer(new Frame(generation, ++sequence, request, auth(Step.CODE)));
        if (elapsed < Duration.ofSeconds(30).toNanos()) {
            var state = bridge.drain(); assertEquals(Step.CODE, state.auth().step()); assertFalse(state.disposed());
            assertEquals(0, state.pendingRequests());
        } else {
            assertEquals(BridgeException.Code.TIMEOUT, assertThrows(BridgeException.class, bridge::drain).code());
            assertEquals(BridgeException.Code.TIMEOUT, bridge.latest().failure()); assertTrue(bridge.latest().disposed());
            assertNull(bridge.latest().auth()); assertNull(bridge.latest().identity()); assertEquals(0, bridge.queued());
        }
    }

    @Test void expiryIsCheckedBetweenQueuedFramesNotOnlyAtDrainStart() throws Exception {
        AtomicLong ticks = new AtomicLong(); boolean[] ticking = {false};
        Clock advancingClock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochSecond(ticking[0] ? 1001 + ticks.getAndIncrement() : 1000); }
        };
        try (var other = new BridgeCoordinator(generation, advancingClock, nanos::get)) {
            other.offer(new Frame(generation, 1, 0, auth(Step.READY)));
            other.offer(new Frame(generation, 2, 0, new Identity(1, 42, marker))); other.drain();
            Frame initial = other.request(Target.SAVED_MESSAGES, 0, 10);
            other.offer(new Frame(generation, 3, initial.requestId(), new Messages(List.of(message(3, marker, 1002)), 0))); other.drain();
            Frame stale = other.request(Target.SAVED_MESSAGES, 0, 10);
            other.offer(new Frame(generation, 4, 0, new Connection(Phase.OFFLINE)));
            other.offer(new Frame(generation, 5, stale.requestId(), new Messages(List.of(message(3, "stale-expiry", 0)), 0)));
            ticking[0] = true;
            var state = other.drain(); assertTrue(state.messages().isEmpty()); assertEquals(0, state.retainedMessages());
            assertEquals(0, state.pendingRequests()); assertFalse(state.disposed()); assertEquals(5, state.sequence());
        }
    }

    @Test void cancelAuthAndUnsupportedStatesNeverFallBackOrClaimEngineClose() throws Exception {
        post(auth(Step.EMAIL)); long id;
        try (var input = bridge.authenticate(marker.getBytes(StandardCharsets.UTF_8))) { id = input.requestId(); }
        Frame cancel = bridge.control(Action.CANCEL, id); assertEquals(Action.CANCEL, ((Control) cancel.payload()).action());
        assertTrue(bridge.latest().disposed()); assertNull(bridge.latest().identity()); assertNull(bridge.latest().auth());
        assertThrows(BridgeException.class, () -> bridge.authenticate(marker.getBytes(StandardCharsets.UTF_8)));
        var other = new BridgeCoordinator(generation);
        other.offer(new Frame(generation, 1, 0, new Auth(Step.UNSUPPORTED, UUID.randomUUID(), 0, Unsupported.REGISTRATION))); other.drain();
        assertThrows(BridgeException.class, () -> other.authenticate(marker.getBytes(StandardCharsets.UTF_8)));
        other.offer(new Frame(generation, 2, 0, auth(Step.PHONE))); assertThrows(BridgeException.class, other::drain);
    }
}
