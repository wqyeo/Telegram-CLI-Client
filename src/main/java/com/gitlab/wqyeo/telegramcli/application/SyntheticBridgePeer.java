package com.gitlab.wqyeo.telegramcli.application;

import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import com.fasterxml.jackson.annotation.JsonValue;
import com.gitlab.wqyeo.telegramcli.session.bridge.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.function.LongSupplier;

/** In-memory fixture server, NOT a native worker or Telegram adapter. All source rows are public synthetic data. */
final class SyntheticBridgePeer implements AutoCloseable {
    static final long SAVED_ID = 42;
    @FunctionalInterface interface Sink { void accept(Frame frame) throws BridgeException; }
    private final UUID generation;
    private final Clock clock;
    private final LongSupplier nanos;
    private final Sink sink;
    private final SyntheticReadOnlySession.Scenario scenario;
    private final List<Chat> chats = new ArrayList<>();
    private final NavigableMap<Long, Message> messages = new TreeMap<>();
    private Auth auth;
    private long sequence, receivedSequence, receivedRequest, retryStarted;
    private int authAttempts, readRequests, controlRequests;
    private boolean retried, closed, offline;

    SyntheticBridgePeer(UUID generation, Clock clock, LongSupplier nanos, Sink sink, SyntheticReadOnlySession.Scenario scenario) {
        this.generation = generation; this.clock = clock; this.nanos = nanos; this.sink = sink; this.scenario = scenario;
        chats.add(new Chat(SAVED_ID, "Saved Messages", ChatKind.USER));
        for (int i = 1; i < MAX_CHATS; i++) {
            chats.add(new Chat(-i, i % 7 == 0 ? "Synthetic 团队 " + i : "Synthetic chat " + i,
                    i % 9 == 0 ? ChatKind.UNSUPPORTED : i % 2 == 0 ? ChatKind.GROUP : ChatKind.USER));
        }
        for (int i = 1; i <= MAX_MESSAGES; i++) {
            String text = i == 100 ? "Synthetic Saved Messages. Nothing here came from an account.\n你好 🙂 e\u0301 👩‍💻"
                    : i == 99 ? "Control fixture: \u001b[2J \u0007 \u202e visible, never terminal instructions."
                    : i == 98 ? "Bounded synthetic text. ".repeat(60)
                    : "Synthetic note #" + i + ". Read-only fixture; no message was sent.";
            messages.put((long) i, new Message(i, "Synthetic self", text, 1_700_000_000L + i, 0, false));
        }
    }
    void start() throws BridgeException { prompt(Step.PHONE, 0); }
    static byte[] fixture(Step step) { return ("PUBLIC_SYNTHETIC_" + step.name()).getBytes(StandardCharsets.US_ASCII); }

    void authenticate(AuthSubmission submission) throws BridgeException {
        open();
        if (auth == null || !auth.step().input() || submission.requestId() <= receivedRequest) { throw invalid(); }
        if (retried && auth.step() == Step.PHONE && nanos.getAsLong() - retryStarted < 1_000_000_000L) {
            throw new BridgeException(BridgeException.Code.TIMEOUT);
        }
        byte[] bytes = null, expected = fixture(auth.step());
        try (var output = new WipingOutput()) {
            AuthTransfer.write(output, submission); bytes = output.toByteArray();
            try (var decoded = AuthTransfer.read(new ByteArrayInputStream(bytes), generation, submission.requestId(), auth.token(), auth.step())) {
                if (decoded == null || !decoded.use(value -> Arrays.equals(value, expected))) { throw invalid(); }
            }
            receivedRequest = submission.requestId(); authAttempts = Math.incrementExact(authAttempts);
            Step from = auth.step(); auth = null; // Consume before responding; old tokens cannot be used twice.
            switch (from) {
                case PHONE -> {
                    if (scenario == SyntheticReadOnlySession.Scenario.RETRY && !retried) {
                        retried = true; retryStarted = nanos.getAsLong();
                        auth = new Auth(Step.RETRY, UUID.randomUUID(), 1, Unsupported.NONE); emit(auth, receivedRequest);
                        prompt(Step.PHONE, 0); // Deliberately early prompt; both sides still enforce retry-after.
                    } else { prompt(Step.CODE, receivedRequest); }
                }
                case CODE -> {
                    if (scenario == SyntheticReadOnlySession.Scenario.UNSUPPORTED) { unsupported(Unsupported.REGISTRATION); }
                    else { prompt(Step.EMAIL, receivedRequest); }
                }
                case EMAIL -> prompt(Step.EMAIL_CODE, receivedRequest);
                case EMAIL_CODE -> prompt(Step.PASSWORD, receivedRequest);
                case PASSWORD -> {
                    prompt(Step.READY, receivedRequest);
                    emit(new Identity(1, SAVED_ID, "Synthetic operator"), 0); emit(new Connection(Phase.READY), 0);
                }
                default -> throw invalid();
            }
        } catch (RuntimeException failure) { throw invalid(); }
        finally { Arrays.fill(expected, (byte) 0); if (bytes != null) { Arrays.fill(bytes, (byte) 0); } }
    }
    void receive(Frame original) throws BridgeException {
        open(); Frame frame = copy(original);
        if (!generation.equals(frame.generation()) || frame.sequence() != receivedSequence + 1 || frame.requestId() <= receivedRequest) { throw invalid(); }
        receivedSequence = frame.sequence(); receivedRequest = frame.requestId();
        switch (frame.payload()) {
            case Request request -> {
                readRequests = Math.incrementExact(readRequests);
                if (auth == null || auth.step() != Step.READY || offline) { emit(new Failure(BridgeData.Error.NOT_AVAILABLE), frame.requestId()); return; }
                if (request.target() == Target.CHATS) {
                    if (request.cursor() > chats.size()) { emit(new Failure(BridgeData.Error.NOT_AVAILABLE), frame.requestId()); return; }
                    int start = (int) request.cursor(), end = Math.min(chats.size(), start + request.limit());
                    emit(new Chats(chats.subList(start, end), end < chats.size() ? end : 0), frame.requestId());
                } else {
                    if (request.cursor() > MAX_MESSAGES + 1L) { emit(new Failure(BridgeData.Error.NOT_AVAILABLE), frame.requestId()); return; }
                    long before = request.cursor() == 0 ? MAX_MESSAGES + 1L : request.cursor();
                    var rows = messages.headMap(before, false).descendingMap().values().stream().filter(value -> !expired(value)).limit(request.limit()).toList();
                    long next = !rows.isEmpty() && messages.headMap(rows.getLast().id(), false).values().stream().anyMatch(value -> !expired(value))
                            ? rows.getLast().id() : 0;
                    emit(new Messages(rows, next), frame.requestId());
                }
            }
            case Control control -> {
                controlRequests = Math.incrementExact(controlRequests);
                switch (control.action()) {
                    case CLOSE -> close(); // No engine acknowledgment is fabricated.
                    case CANCEL -> { /* Synchronous fixture requests have no lingering tasks. */ }
                    case RESYNC -> { ready(); emit(new Connection(Phase.LOADING), 0); emit(new Connection(offline ? Phase.OFFLINE : Phase.READY), 0); }
                }
            }
            default -> throw invalid();
        }
    }
    void tick() throws BridgeException {
        if (closed || auth == null || auth.step() != Step.READY) { return; }
        var expired = messages.values().stream().filter(this::expired).map(Message::id).toList();
        if (!expired.isEmpty()) { for (long id : expired) { messages.remove(id); } emit(new Removed(expired, true), 0); }
    }
    private boolean expired(Message message) { return message.expiresAt() != 0 && message.expiresAt() <= clock.instant().getEpochSecond(); }
    void edit(long id, String text, long expiresAt) throws BridgeException {
        ready(); Message old = messages.get(id); if (old == null) { throw invalid(); }
        final Message changed;
        try { changed = new Message(id, old.sender(), text, old.date(), expiresAt, true); }
        catch (RuntimeException failure) { throw invalid(); }
        messages.put(id, changed); emit(new Changed(changed), 0);
    }
    void remove(long id) throws BridgeException { ready(); messages.remove(id); emit(new Removed(List.of(id), false), 0); }
    void connection(boolean online) throws BridgeException { ready(); offline = !online; emit(new Connection(online ? Phase.READY : Phase.OFFLINE), 0); }
    void expireAuthorization() throws BridgeException { ready(); auth = null; emit(new Connection(Phase.AUTH_EXPIRED), 0); }
    void unsupported(Unsupported reason) throws BridgeException {
        open();
        try { auth = new Auth(Step.UNSUPPORTED, UUID.randomUUID(), 0, reason); }
        catch (RuntimeException failure) { throw invalid(); }
        emit(auth, 0);
    }
    private void prompt(Step step, long request) throws BridgeException { auth = new Auth(step, UUID.randomUUID(), 0, Unsupported.NONE); emit(auth, request); }
    private void emit(Payload payload, long request) throws BridgeException { sink.accept(copy(new Frame(generation, ++sequence, request, payload))); }
    static Frame copy(Frame frame) throws BridgeException {
        byte[] bytes = null;
        try (var output = new WipingOutput()) {
            BridgeProtocol.write(output, frame); bytes = output.toByteArray();
            Frame result = BridgeProtocol.read(new ByteArrayInputStream(bytes)); if (result == null) { throw invalid(); } return result;
        } finally { if (bytes != null) { Arrays.fill(bytes, (byte) 0); } }
    }
    SyntheticReadOnlySession.Activity activity() { return new SyntheticReadOnlySession.Activity(authAttempts, readRequests, controlRequests); }
    int retainedFixtureMessages() { return messages.size(); }
    private void open() throws BridgeException { if (closed) { throw invalid(); } }
    private void ready() throws BridgeException { open(); if (auth == null || auth.step() != Step.READY) { throw invalid(); } }
    private static BridgeException invalid() { return new BridgeException(BridgeException.Code.PROTOCOL_INVALID); }
    @Override public void close() { closed = true; auth = null; chats.clear(); messages.clear(); }
    @JsonValue public String redacted() { return "[REDACTED synthetic peer]"; }
    @Override public String toString() { return redacted(); }
    private static final class WipingOutput extends ByteArrayOutputStream {
        @Override public void close() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
