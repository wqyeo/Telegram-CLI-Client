package com.gitlab.wqyeo.telegramcli.application;

import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import com.fasterxml.jackson.annotation.JsonValue;
import com.gitlab.wqyeo.telegramcli.session.bridge.*;
import java.util.*;

/** PUBLIC fixture-only process-backed preview. No launcher/provider/vault/SDK/account API.
 * Test-only harness owns literal process creation; the accepted frontend is shared, not copied.
 */
public final class ProcessReadOnlySession implements ReadOnlyPreviewSession {
    private final BridgeCoordinator coordinator;
    private final ProcessBridgeTransport transport;
    private boolean initialized, closed;
    public ProcessReadOnlySession(Process process, UUID generation, ProcessBridgeTransport.Budgets budgets) throws BridgeException {
        coordinator = new BridgeCoordinator(generation);
        try { transport = ProcessBridgeTransport.attachPublicFixture(process, generation, coordinator::offer, budgets); }
        catch (BridgeException failure) { coordinator.close(); throw new BridgeException(failure.code()); }
    }
    @Override public BridgeCoordinator.Snapshot latest() { return coordinator.latest(); }
    @Override public synchronized BridgeCoordinator.Snapshot tick() throws BridgeException {
        if (closed) { return latest(); }
        try {
            transport.check(); coordinator.drain();
            if (!initialized && latest().auth() != null && latest().auth().step() == Step.READY
                    && latest().identity() != null && latest().connection() == Phase.READY) {
                initialized = true; loadInitial();
            }
            return latest();
        } catch (BridgeException failure) { throw failed(failure.code()); }
    }
    @Override public synchronized void advanceAuthorization() throws BridgeException {
        open(); tick(); var auth = latest().auth();
        if (auth == null || !auth.step().input()) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
        byte[] bytes = SyntheticBridgePeer.fixture(auth.step());
        final AuthSubmission submission;
        try { submission = coordinator.authenticate(bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
        try (submission) { transport.authenticate(submission); }
        catch (BridgeException failure) { throw failed(failure.code()); }
    }
    private void loadInitial() throws BridgeException { chats(0); savedPage(0); }
    private void chats(long cursor) throws BridgeException { send(coordinator.request(Target.CHATS, cursor, CHAT_PAGE)); }
    @Override public synchronized boolean moreChats() throws BridgeException {
        open(); if (!initialized) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
        long next = latest().nextChats(); if (next == 0) { return false; } chats(next); return true;
    }
    @Override public synchronized long savedPage(long beforeMessageId) throws BridgeException {
        open(); Frame frame = coordinator.request(Target.SAVED_MESSAGES, beforeMessageId, MESSAGE_PAGE);
        send(frame); return frame.requestId();
    }
    @Override public synchronized void refresh() throws BridgeException {
        open(); if (!initialized || latest().connection() != Phase.READY) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
        send(coordinator.control(Action.RESYNC, 0)); loadInitial();
    }
    private void send(Frame frame) throws BridgeException {
        try { transport.send(frame); }
        catch (BridgeException failure) { throw failed(failure.code()); }
    }
    private BridgeException failed(BridgeException.Code code) { closed = true; transport.close(); coordinator.close(); return new BridgeException(code); }
    private void open() throws BridgeException { if (closed) { throw new BridgeException(BridgeException.Code.STATE_INVALID); } }
    @Override public synchronized void close() throws BridgeException {
        if (closed) { return; }
        closed = true;
        try { transport.closeUsing(() -> coordinator.control(Action.CLOSE, 0)); }
        finally { coordinator.close(); }
    }
    public boolean peerCleanlyClosed() { return transport.cleanlyClosed(); }
    @JsonValue public String redacted() { return "[REDACTED process read-only preview]"; }
    @Override public String toString() { return redacted(); }
}
