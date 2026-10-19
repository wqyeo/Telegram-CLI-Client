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
    private long restoreChat;
    public ProcessReadOnlySession(Process process, UUID generation, ProcessBridgeTransport.Budgets budgets) throws BridgeException { this(process, generation, budgets, 1); }
    public static ProcessReadOnlySession ordinaryReader(Process process, UUID generation, ProcessBridgeTransport.Budgets budgets) throws BridgeException {
        return new ProcessReadOnlySession(process, generation, budgets, 2);
    }
    private ProcessReadOnlySession(Process process, UUID generation, ProcessBridgeTransport.Budgets budgets, int version) throws BridgeException {
        coordinator = version == 2 ? BridgeCoordinator.publicReader(generation) : new BridgeCoordinator(generation);
        try { transport = version == 2 ? ProcessBridgeTransport.attachPublicReaderFixture(process, generation, coordinator::offer, budgets)
                : ProcessBridgeTransport.attachPublicFixture(process, generation, coordinator::offer, budgets); }
        catch (BridgeException failure) { coordinator.close(); throw new BridgeException(failure.code()); }
    }
    @Override public BridgeCoordinator.Snapshot latest() { return coordinator.latest(); }
    @Override public synchronized BridgeCoordinator.Snapshot tick() throws BridgeException {
        if (closed) { return latest(); }
        try {
            transport.check(); coordinator.drain();
            if (coordinator.protocolVersion() == 2 && (latest().auth() == null || latest().auth().step() != Step.READY)) { initialized = false; restoreChat = 0; }
            if (!initialized && latest().auth() != null && latest().auth().step() == Step.READY
                    && (latest().identity() != null || latest().reader() != null && latest().reader().account() != null) && latest().connection() == Phase.READY) {
                initialized = true; loadInitial();
            }
            if (restoreChat != 0 && latest().pendingRequests() == 0 && latest().connection() == Phase.READY) {
                long selected = restoreChat; restoreChat = 0;
                if (latest().chats().stream().anyMatch(c -> c.id() == selected && c.chatKind() == ChatKind.USER)) { selectChat(selected); }
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
    private void loadInitial() throws BridgeException {
        if (coordinator.protocolVersion() == 2) { send(coordinator.directoryPage(true)); }
        else { chats(0); savedPage(0); }
    }
    private void chats(long cursor) throws BridgeException { send(coordinator.request(Target.CHATS, cursor, CHAT_PAGE)); }
    @Override public synchronized boolean moreChats() throws BridgeException {
        open(); if (!initialized) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
        if (coordinator.protocolVersion() == 2) {
            if (latest().reader().directoryNext().state() == ContinuationState.END) { return false; }
            send(coordinator.directoryPage(false)); return true;
        }
        long next = latest().nextChats(); if (next == 0) { return false; } chats(next); return true;
    }
    @Override public synchronized long savedPage(long beforeMessageId) throws BridgeException {
        open(); Frame frame = coordinator.request(Target.SAVED_MESSAGES, beforeMessageId, MESSAGE_PAGE);
        send(frame); return frame.requestId();
    }
    @Override public synchronized void searchLoaded(String query) throws BridgeException { open(); coordinator.searchLoaded(query); }
    @Override public synchronized long selectChat(long chatId) throws BridgeException { open(); Frame frame = coordinator.selectChat(chatId); send(frame); return frame.requestId(); }
    @Override public synchronized long latestPage() throws BridgeException { open(); Frame frame = coordinator.historyPage(true); send(frame); return frame.requestId(); }
    @Override public synchronized boolean olderPage() throws BridgeException {
        open(); if (latest().reader() == null) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
        if (latest().reader().next().state() == ContinuationState.END) { return false; }
        send(coordinator.historyPage(false)); return true;
    }
    @Override public synchronized void cancelRead(long requestId) throws BridgeException { open(); send(coordinator.cancelRead(requestId)); }
    @Override public synchronized void refresh() throws BridgeException {
        open(); if (!initialized || latest().connection() != Phase.READY) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
        if (coordinator.protocolVersion() == 2) {
            restoreChat = latest().reader().chatId(); initialized = false;
            send(coordinator.control(Action.RESYNC, 0)); // Wait for the explicit fixture READY before issuing the fresh directory request.
        } else { send(coordinator.control(Action.RESYNC, 0)); loadInitial(); }
    }
    private void send(Frame frame) throws BridgeException {
        try { transport.send(frame); }
        catch (BridgeException failure) { throw failed(failure.code()); }
    }
    private BridgeException failed(BridgeException.Code code) { closed = true; restoreChat = 0; transport.close(); coordinator.close(); return new BridgeException(code); }
    private void open() throws BridgeException { if (closed) { throw new BridgeException(BridgeException.Code.STATE_INVALID); } }
    @Override public synchronized void close() throws BridgeException {
        if (closed) { return; }
        closed = true; restoreChat = 0;
        try { transport.closeUsing(() -> coordinator.control(Action.CLOSE, 0)); }
        finally { coordinator.close(); }
    }
    public boolean peerCleanlyClosed() { return transport.cleanlyClosed(); }
    @JsonValue public String redacted() { return "[REDACTED process read-only preview]"; }
    @Override public String toString() { return redacted(); }
}
