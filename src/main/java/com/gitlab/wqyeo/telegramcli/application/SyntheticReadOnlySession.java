package com.gitlab.wqyeo.telegramcli.application;

import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import com.fasterxml.jackson.annotation.JsonValue;
import com.gitlab.wqyeo.telegramcli.session.bridge.*;
import java.time.Clock;
import java.util.*;
import java.util.function.LongSupplier;

/** Synthetic-only rehearsal. No provider, filesystem, process, SDK, database or network dependency.
 * Authorization advances only on explicit actions and uses public fixture bytes, never caller input.
 * In-memory wire round trips are NOT native-transport, account authorization or engine-close proof.
 */
public final class SyntheticReadOnlySession implements AutoCloseable {
    public enum Scenario { FULL, RETRY, UNSUPPORTED }
    public record Activity(int authAttempts, int readRequests, int controlRequests) {}
    private final BridgeCoordinator coordinator;
    private final SyntheticBridgePeer peer;
    private boolean initialized, closed;
    public SyntheticReadOnlySession() throws BridgeException { this(Scenario.FULL, Clock.systemUTC(), System::nanoTime); }
    public SyntheticReadOnlySession(Scenario scenario, Clock clock, LongSupplier nanos) throws BridgeException {
        UUID generation = UUID.randomUUID();
        coordinator = new BridgeCoordinator(generation, clock, nanos);
        peer = new SyntheticBridgePeer(generation, clock, nanos, coordinator::offer, Objects.requireNonNull(scenario));
        exchange(peer::start);
    }
    public BridgeCoordinator.Snapshot latest() { return coordinator.latest(); }
    public synchronized Activity activity() { return peer.activity(); }
    public synchronized BridgeCoordinator.Snapshot tick() throws BridgeException {
        if (!closed) { exchange(peer::tick); }
        return latest();
    }
    /** Enter/continue means use ONE public fixture for this step. Real phone/code/password input is not accepted. */
    public synchronized void advanceAuthorization() throws BridgeException {
        open(); tick(); Auth auth = latest().auth();
        if (auth == null || !auth.step().input()) { throw state(); }
        byte[] fixture = SyntheticBridgePeer.fixture(auth.step());
        final AuthSubmission submission;
        try { submission = coordinator.authenticate(fixture); }
        finally { Arrays.fill(fixture, (byte) 0); }
        // Known local retry-delay/input refusal above does not close/re-arm or send anything.
        try (submission) { exchange(() -> peer.authenticate(submission)); }
        if (latest().auth() != null && latest().auth().step() == Step.READY && !initialized) {
            initialized = true; loadInitial();
        }
    }
    private void loadInitial() throws BridgeException { chats(0); savedPage(0); }
    private void chats(long cursor) throws BridgeException { exchange(() -> peer.receive(coordinator.request(Target.CHATS, cursor, CHAT_PAGE))); }
    public synchronized boolean moreChats() throws BridgeException {
        open(); if (!initialized) { throw state(); }
        long next = latest().nextChats(); if (next == 0) { return false; } chats(next); return true;
    }
    /** Saved-only page; no arbitrary chat ID, SDK function, send, edit, delete or media operation. */
    public synchronized void savedPage(long beforeMessageId) throws BridgeException {
        open(); exchange(() -> peer.receive(coordinator.request(Target.SAVED_MESSAGES, beforeMessageId, MESSAGE_PAGE)));
    }
    public synchronized boolean olderSavedPage() throws BridgeException {
        open(); if (!initialized) { throw state(); }
        long next = latest().nextMessages(); if (next == 0) { return false; } savedPage(next); return true;
    }
    public synchronized void refresh() throws BridgeException {
        open(); if (!initialized || latest().connection() != Phase.READY) { throw state(); }
        exchange(() -> peer.receive(coordinator.control(Action.RESYNC, 0))); loadInitial();
    }

    // Explicit fixture-server controls for tests/scenarios, NEVER viewer edit/delete/send bindings.
    public synchronized void fixtureEdit(long id, String text, long expiresAt) throws BridgeException { open(); exchange(() -> peer.edit(id, text, expiresAt)); }
    public synchronized void fixtureRemove(long id) throws BridgeException { open(); exchange(() -> peer.remove(id)); }
    public synchronized void fixtureConnection(boolean online) throws BridgeException { open(); exchange(() -> peer.connection(online)); }
    public synchronized void fixtureAuthorizationExpired() throws BridgeException { open(); exchange(peer::expireAuthorization); }
    public synchronized void fixtureUnsupported(Unsupported reason) throws BridgeException { open(); exchange(() -> peer.unsupported(reason)); }

    @FunctionalInterface private interface Exchange { void run() throws BridgeException; }
    private void exchange(Exchange operation) throws BridgeException {
        try { operation.run(); coordinator.drain(); }
        catch (BridgeException failure) { close(); throw new BridgeException(failure.code()); }
        catch (RuntimeException failure) { close(); throw new BridgeException(BridgeException.Code.PROTOCOL_INVALID); }
    }
    private void open() throws BridgeException { if (closed) { throw state(); } }
    private static BridgeException state() { return new BridgeException(BridgeException.Code.STATE_INVALID); }
    @Override public synchronized void close() {
        if (!closed) { closed = true; peer.close(); coordinator.close(); }
    }
    @JsonValue public String redacted() { return "[REDACTED synthetic read-only session]"; }
    @Override public String toString() { return redacted(); }
}
