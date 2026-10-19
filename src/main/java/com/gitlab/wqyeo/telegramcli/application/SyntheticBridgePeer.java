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
    private final int version;
    private final Map<Long, NavigableMap<Long, ReaderMessage>> readerMessages = new LinkedHashMap<>();
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

    SyntheticBridgePeer(UUID generation, Clock clock, LongSupplier nanos, Sink sink, SyntheticReadOnlySession.Scenario scenario) { this(generation, clock, nanos, sink, scenario, 1); }
    SyntheticBridgePeer(UUID generation, Clock clock, LongSupplier nanos, Sink sink, SyntheticReadOnlySession.Scenario scenario, int version) {
        this.version = version;
        this.generation = generation; this.clock = clock; this.nanos = nanos; this.sink = sink; this.scenario = scenario;
        if (version == 2) {
            chats.add(new Chat(1001, "PUBLIC Alex", ChatKind.USER)); chats.add(new Chat(1002, "PUBLIC Blair", ChatKind.USER));
            chats.add(new Chat(-77, "PUBLIC unsupported group", ChatKind.GROUP));
            for (long chat : List.of(1001L, 1002L)) {
                var rows = new TreeMap<Long, ReaderMessage>(); readerMessages.put(chat, rows);
                for (long id = 1; id <= 25; id++) { rows.put(id, ReaderMessage.project(chat, id, chat == 1001 ? "PUBLIC Alex" : "PUBLIC Blair",
                        "PUBLIC conversation " + chat + " message " + id, 1_700_000_000L + id, 0, false, Content.TEXT)); }
            }
            return;
        }
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

    /** Test child private channel: validate current step/token/next request before reading its bounded body. */
    void authenticateFrom(InputStream input) throws BridgeException {
        open();
        if (auth == null || !auth.step().input()) { throw invalid(); }
        try (var submission = AuthTransfer.read(input, generation, Math.addExact(receivedRequest, 1), auth.token(), auth.step())) {
            if (submission == null) { throw invalid(); }
            authenticate(submission);
        }
    }
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
                    emit(version == 2 ? new Account(1, "PUBLIC operator") : new Identity(1, SAVED_ID, "Synthetic operator"), 0);
                    emit(new Connection(Phase.READY), 0);
                }
                default -> throw invalid();
            }
        } catch (RuntimeException failure) { throw invalid(); }
        finally { Arrays.fill(expected, (byte) 0); if (bytes != null) { Arrays.fill(bytes, (byte) 0); } }
    }
    void receive(Frame original) throws BridgeException {
        open(); Frame frame = copy(original);
        if (frame.version() != version || !generation.equals(frame.generation()) || frame.sequence() != receivedSequence + 1 || frame.requestId() <= receivedRequest) { throw invalid(); }
        receivedSequence = frame.sequence(); receivedRequest = frame.requestId();
        switch (frame.payload()) {
            case Request request -> {
                readRequests = Math.incrementExact(readRequests);
                if (auth == null || auth.step() != Step.READY || offline) { emit(new Failure(BridgeData.Error.NOT_AVAILABLE), frame.requestId()); return; }
                if (request.target() == Target.CHATS) {
                    if (request.cursor() > chats.size()) { emit(new Failure(BridgeData.Error.NOT_AVAILABLE), frame.requestId()); return; }
                    int start = (int) request.cursor(), end = Math.min(chats.size(), start + request.limit());
                    emit(version == 2 ? new Directory(chats.subList(start, end), end < chats.size()
                            ? new Continuation(ContinuationState.AVAILABLE, end) : Continuation.end())
                            : new Chats(chats.subList(start, end), end < chats.size() ? end : 0), frame.requestId());
                } else {
                    if (request.cursor() > MAX_MESSAGES + 1L) { emit(new Failure(BridgeData.Error.NOT_AVAILABLE), frame.requestId()); return; }
                    long before = request.cursor() == 0 ? MAX_MESSAGES + 1L : request.cursor();
                    var rows = messages.headMap(before, false).descendingMap().values().stream().filter(value -> !expired(value)).limit(request.limit()).toList();
                    long next = !rows.isEmpty() && messages.headMap(rows.getLast().id(), false).values().stream().anyMatch(value -> !expired(value))
                            ? rows.getLast().id() : 0;
                    emit(new Messages(rows, next), frame.requestId());
                }
            }
            case HistoryRequest query -> {
                readRequests = Math.incrementExact(readRequests);
                if (auth == null || auth.step() != Step.READY || offline || !readerMessages.containsKey(query.chatId())) {
                    emit(new Failure(BridgeData.Error.NOT_AVAILABLE), frame.requestId()); return;
                }
                var source = readerMessages.get(query.chatId());
                var rows = (query.cursor() == 0 ? source : source.headMap(query.cursor(), false)).descendingMap().values().stream()
                        .filter(m -> !expired(m)).limit(query.limit()).toList();
                boolean more = !rows.isEmpty() && source.headMap(rows.getLast().id(), false).values().stream().anyMatch(m -> !expired(m));
                emit(new HistoryPage(query.chatId(), query.selection(), query.window(), rows, more
                        ? new Continuation(ContinuationState.AVAILABLE, rows.getLast().id()) : Continuation.end()), frame.requestId());
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
        if (version == 2) {
            for (var entry : readerMessages.entrySet()) {
                var ids = entry.getValue().values().stream().filter(this::expired).map(ReaderMessage::id).toList();
                if (!ids.isEmpty()) { for (long id : ids) { entry.getValue().remove(id); } emit(new ChatRemoved(entry.getKey(), ids, true), 0); }
            }
            return;
        }
        var expired = messages.values().stream().filter(this::expired).map(Message::id).toList();
        if (!expired.isEmpty()) { for (long id : expired) { messages.remove(id); } emit(new Removed(expired, true), 0); }
    }
    private boolean expired(ReaderMessage message) { return message.expiresAt() != 0 && message.expiresAt() <= clock.instant().getEpochSecond(); }
    void readerUpdate(long chatId, long id, String text, long expiresAt, Update update) throws BridgeException {
        ready(); var rows = readerMessages.get(chatId); if (version != 2 || rows == null || (update == Update.EDIT && !rows.containsKey(id))) { throw invalid(); }
        var message = ReaderMessage.project(chatId, id, chatId == 1001 ? "PUBLIC Alex" : "PUBLIC Blair", text, 1_700_000_000L + id,
                expiresAt, update == Update.EDIT, Content.TEXT);
        rows.put(id, message); emit(new MessageUpdate(update, message), 0);
    }
    void readerRemove(long chatId, long id) throws BridgeException {
        ready(); var rows = readerMessages.get(chatId); if (version != 2 || rows == null) { throw invalid(); }
        rows.remove(id); emit(new ChatRemoved(chatId, List.of(id), false), 0);
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
    private void emit(Payload payload, long request) throws BridgeException { sink.accept(copy(new Frame(version, generation, sequence = Math.incrementExact(sequence), request, payload))); }
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
    @Override public void close() { closed = true; auth = null; chats.clear(); messages.clear(); readerMessages.clear(); }
    @JsonValue public String redacted() { return "[REDACTED synthetic peer]"; }
    @Override public String toString() { return redacted(); }
    private static final class WipingOutput extends ByteArrayOutputStream {
        @Override public void close() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
