package com.gitlab.wqyeo.telegramcli.application;

import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gitlab.wqyeo.telegramcli.session.bridge.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** No console, provider, SDK, process or account. Public fixture input is selected by explicit actions. */
class SyntheticReadOnlySessionTest {
    private final AtomicLong nanos = new AtomicLong(), seconds = new AtomicLong(1000);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(seconds.get()); }
    };
    private SyntheticReadOnlySession session(SyntheticReadOnlySession.Scenario scenario) throws BridgeException {
        return new SyntheticReadOnlySession(scenario, clock, nanos::get);
    }
    private void ready(SyntheticReadOnlySession session) throws BridgeException {
        for (Step step : List.of(Step.PHONE, Step.CODE, Step.EMAIL, Step.EMAIL_CODE, Step.PASSWORD)) {
            assertEquals(step, session.latest().auth().step()); session.advanceAuthorization();
        }
        assertEquals(Step.READY, session.latest().auth().step());
    }

    @Test void eachAuthorizationStepIsExplicitAndInitialReadPagesUseTheWireCodecs() throws Exception {
        try (var session = session(SyntheticReadOnlySession.Scenario.FULL)) {
            var original = session.latest(); nanos.set(Duration.ofHours(1).toNanos());
            assertSame(original, session.tick()); assertEquals(0, session.activity().authAttempts());
            ready(session);
            assertEquals(5, session.activity().authAttempts()); assertEquals(2, session.activity().readRequests());
            assertEquals(0, session.latest().pendingRequests()); assertEquals(Phase.READY, session.latest().connection());
            assertEquals(20, session.latest().chats().size()); assertEquals(10, session.latest().messages().size());
            assertEquals(42, session.latest().identity().savedChatId()); assertEquals(91, session.latest().messages().getFirst().id());
            var current = session.latest(); assertSame(current, session.tick());
            assertThrows(BridgeException.class, session::advanceAuthorization); assertEquals(5, session.activity().authAttempts());
            assertFalse(session.latest().disposed());
        }
    }

    @Test void retryHasNoAutomaticResubmissionAndBothSidesRespectTheDelay() throws Exception {
        try (var session = session(SyntheticReadOnlySession.Scenario.RETRY)) {
            session.advanceAuthorization();
            assertEquals(Step.PHONE, session.latest().auth().step()); assertEquals(1, session.latest().retryAfterSeconds());
            assertEquals(BridgeException.Code.TIMEOUT, assertThrows(BridgeException.class, session::advanceAuthorization).code());
            assertFalse(session.latest().disposed()); assertEquals(1, session.activity().authAttempts());
            nanos.set(Duration.ofSeconds(2).toNanos()); session.tick();
            assertEquals(0, session.latest().retryAfterSeconds()); assertEquals(1, session.activity().authAttempts());
            ready(session); assertEquals(6, session.activity().authAttempts()); assertEquals(2, session.activity().readRequests());
        }
    }

    @Test void unsupportedRegistrationAndOtherSensitiveFlowsNeverFallBack() throws Exception {
        try (var session = session(SyntheticReadOnlySession.Scenario.UNSUPPORTED)) {
            session.advanceAuthorization(); session.advanceAuthorization();
            assertEquals(Step.UNSUPPORTED, session.latest().auth().step());
            assertEquals(Unsupported.REGISTRATION, session.latest().auth().unsupported());
            assertThrows(BridgeException.class, session::advanceAuthorization); assertEquals(0, session.activity().readRequests());
            assertNull(session.latest().identity()); assertTrue(session.latest().messages().isEmpty());
        }
        for (Unsupported reason : Unsupported.values()) {
            if (reason == Unsupported.NONE) { continue; }
            try (var session = session(SyntheticReadOnlySession.Scenario.FULL)) {
                ready(session); session.fixtureUnsupported(reason);
                assertEquals(reason, session.latest().auth().unsupported()); assertNull(session.latest().identity());
                assertTrue(session.latest().messages().isEmpty()); assertTrue(session.latest().chats().isEmpty());
                int attempts = session.activity().authAttempts(); assertThrows(BridgeException.class, session::advanceAuthorization);
                session.tick(); assertEquals(attempts, session.activity().authAttempts());
            }
        }
    }

    @Test void finitePaginationRetainsAtMostTheAgreedWindowsAndStopsWithoutExtraRequests() throws Exception {
        try (var session = session(SyntheticReadOnlySession.Scenario.FULL)) {
            ready(session); int chatPages = 1, savedPages = 1;
            while (session.moreChats()) { chatPages++; assertTrue(session.latest().chats().size() <= MAX_CHATS); }
            assertEquals(5, chatPages); assertEquals(MAX_CHATS, session.latest().chats().size());
            assertEquals(MAX_CHATS, session.latest().chats().stream().map(Chat::id).distinct().count());
            while (session.olderSavedPage()) {
                savedPages++; assertEquals(10, session.latest().messages().size()); assertTrue(session.latest().retainedMessages() <= MAX_MESSAGES);
            }
            assertEquals(10, savedPages); assertEquals(MAX_MESSAGES, session.latest().retainedMessages());
            assertEquals(1, session.latest().messages().getFirst().id()); assertEquals(10, session.latest().messages().getLast().id());
            assertEquals(15, session.activity().readRequests());
            assertFalse(session.moreChats()); assertFalse(session.olderSavedPage()); assertEquals(15, session.activity().readRequests());
            session.savedPage(0); assertEquals(100, session.latest().messages().getLast().id()); assertEquals(MAX_MESSAGES, session.latest().retainedMessages());
        }
    }

    @Test void fixtureEditsDeletionsExpiryAndEmptyPagesDoNotResurrectOldText() throws Exception {
        try (var session = session(SyntheticReadOnlySession.Scenario.FULL)) {
            ready(session); session.fixtureEdit(100, "Public synthetic edited text", 1005);
            assertEquals("Public synthetic edited text", session.latest().messages().getLast().text());
            assertTrue(session.latest().messages().getLast().edited());
            session.fixtureRemove(99); assertTrue(session.latest().messages().stream().noneMatch(value -> value.id() == 99));
            session.refresh(); assertTrue(session.latest().messages().stream().noneMatch(value -> value.id() == 99));
            seconds.set(1006); session.tick(); assertTrue(session.latest().messages().stream().noneMatch(value -> value.id() == 100));
            session.refresh(); assertTrue(session.latest().messages().stream().noneMatch(value -> value.id() >= 99));
            for (int id = 1; id < 99; id++) { session.fixtureRemove(id); }
            session.refresh(); assertTrue(session.latest().messages().isEmpty()); assertEquals(0, session.latest().retainedMessages());
            assertEquals(0, session.latest().nextMessages()); assertEquals(0, session.latest().pendingRequests());
        }
    }

    @Test void offlineAndExpiredAuthorizationAreHonestStatesWithoutImplicitReconnectOrReauth() throws Exception {
        try (var session = session(SyntheticReadOnlySession.Scenario.FULL)) {
            ready(session); session.fixtureConnection(false); var cached = session.latest().messages();
            var offline = session.latest(); assertSame(offline, session.tick()); assertEquals(Phase.OFFLINE, offline.connection());
            assertThrows(BridgeException.class, session::refresh); assertEquals(cached, session.latest().messages());
            session.olderSavedPage(); assertEquals(BridgeData.Error.NOT_AVAILABLE, session.latest().lastError());
            assertEquals(cached, session.latest().messages()); assertFalse(session.latest().disposed());
            session.fixtureConnection(true); session.refresh(); assertNull(session.latest().lastError());
            session.fixtureAuthorizationExpired(); assertEquals(Phase.AUTH_EXPIRED, session.latest().connection());
            assertNull(session.latest().auth()); assertNull(session.latest().identity()); assertTrue(session.latest().messages().isEmpty());
            assertTrue(session.latest().chats().isEmpty()); assertEquals(0, session.latest().retainedMessages());
            var activity = session.activity(); session.tick(); assertEquals(activity, session.activity());
            assertThrows(BridgeException.class, session::advanceAuthorization); assertEquals(activity, session.activity());
        }
    }

    @Test void peerRejectsReplayedTokensWrongValuesGenerationsAndRequestDirection() throws Exception {
        UUID generation = UUID.randomUUID(); var frames = new ArrayList<Frame>();
        try (var peer = new SyntheticBridgePeer(generation, clock, nanos::get, frames::add, SyntheticReadOnlySession.Scenario.FULL)) {
            peer.start(); Auth phone = (Auth) frames.getLast().payload(); byte[] phoneValue = SyntheticBridgePeer.fixture(Step.PHONE);
            try (var input = AuthSubmission.copyOf(generation, 1, phone.token(), Step.PHONE, phoneValue)) {
                peer.authenticate(input); assertThrows(BridgeException.class, () -> peer.authenticate(input));
            } finally { Arrays.fill(phoneValue, (byte) 0); }
            Auth code = (Auth) frames.getLast().payload(); byte[] codeValue = SyntheticBridgePeer.fixture(Step.CODE);
            try {
                try (var stale = AuthSubmission.copyOf(generation, 2, phone.token(), Step.PHONE, codeValue)) { assertThrows(BridgeException.class, () -> peer.authenticate(stale)); }
                try (var wrongGen = AuthSubmission.copyOf(UUID.randomUUID(), 2, code.token(), Step.CODE, codeValue)) { assertThrows(BridgeException.class, () -> peer.authenticate(wrongGen)); }
                byte[] wrongValue = "PUBLIC_NON_FIXTURE_DO_NOT_PRINT".getBytes(StandardCharsets.UTF_8);
                try (var wrong = AuthSubmission.copyOf(generation, 2, code.token(), Step.CODE, wrongValue)) {
                    var failure = assertThrows(BridgeException.class, () -> peer.authenticate(wrong));
                    assertNull(failure.getCause()); assertFalse(failure.toString().contains("DO_NOT_PRINT"));
                } finally { Arrays.fill(wrongValue, (byte) 0); }
                assertEquals(1, peer.activity().authAttempts());
                try (var valid = AuthSubmission.copyOf(generation, 2, code.token(), Step.CODE, codeValue)) { peer.authenticate(valid); }
            } finally { Arrays.fill(codeValue, (byte) 0); }
            assertEquals(2, peer.activity().authAttempts());
            assertThrows(BridgeException.class, () -> peer.receive(new Frame(generation, 1, 3, new Identity(1, 42, "Not a request"))));
            assertEquals(0, peer.activity().readRequests());
        }
    }

    @Test void closeIsIdempotentPurgesOwnedStateAndDefaultSerializationIsRedacted() throws Exception {
        var session = session(SyntheticReadOnlySession.Scenario.FULL); ready(session);
        assertEquals("\"[REDACTED synthetic read-only session]\"", JsonMapper.builder().build().writeValueAsString(session));
        assertFalse(session.toString().contains("Synthetic operator"));
        session.close(); var closed = session.latest(); session.close(); assertSame(closed, session.tick());
        assertTrue(closed.disposed()); assertNull(closed.auth()); assertNull(closed.identity());
        assertTrue(closed.chats().isEmpty()); assertTrue(closed.messages().isEmpty()); assertEquals(0, closed.retainedMessages());
        assertThrows(BridgeException.class, session::advanceAuthorization); assertThrows(BridgeException.class, session::olderSavedPage);
    }
}
