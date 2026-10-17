package com.gitlab.wqyeo.telegramcli.session.bridge;

import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Bounded application state, not a native transport/session. No SDK, database or secret retention. */
public final class BridgeCoordinator implements AutoCloseable {
    public static final int QUEUE_CAPACITY = 256;
    public static final int MAX_PENDING = 8;
    private static final long REQUEST_NANOS = Duration.ofSeconds(10).toNanos();
    private static final long AUTH_REPLY_NANOS = Duration.ofSeconds(30).toNanos();
    public record Snapshot(UUID generation, long sequence, Auth auth, Identity identity, Phase connection,
                           List<Chat> chats, List<Message> messages, long nextChats, long nextMessages, long acceptedSavedRequest,
                           int retainedMessages, int pendingRequests, int retryAfterSeconds, BridgeData.Error lastError,
                           BridgeException.Code failure, boolean disposed) {
        public Snapshot { chats = List.copyOf(chats); messages = List.copyOf(messages); }
        @JsonValue public String redacted() { return "[REDACTED bridge snapshot]"; }
        @Override public String toString() { return redacted(); }
    }
    private record Pending(Target target, long started) {}
    private final UUID generation;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private final Map<Long, Pending> pending = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Chat> chats = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Message> messages = new LinkedHashMap<>();
    private final List<Long> active = new ArrayList<>();
    private long incoming, outgoing, issued, authRequest, nextChats, nextMessages, acceptedSavedRequest, authStarted, retryStarted, retryNanos;
    private Auth auth;
    private Identity identity;
    private Phase connection = Phase.OFFLINE;
    private BridgeData.Error lastError;
    private BridgeException.Code failure;
    private boolean consumed, disposed;
    private volatile Snapshot snapshot;

    public BridgeCoordinator(UUID generation) { this(generation, Clock.systemUTC(), System::nanoTime); }
    public BridgeCoordinator(UUID generation, Clock clock, LongSupplier nanoTime) {
        this.generation = Objects.requireNonNull(generation); this.clock = Objects.requireNonNull(clock);
        this.nanoTime = Objects.requireNonNull(nanoTime); publish();
    }
    public Snapshot latest() { return snapshot; }
    public synchronized int queued() { return queue.size(); }

    /** Producer offer never waits and never silently drops a current-generation transition. */
    public synchronized void offer(Frame frame) throws BridgeException {
        if (frame != null && !generation.equals(frame.generation())) { return; }
        checkOpen();
        if (frame == null) { throw fail(BridgeException.Code.STATE_INVALID); }
        if (queue.size() == QUEUE_CAPACITY) { throw fail(BridgeException.Code.OVERFLOW); }
        queue.addLast(frame);
    }
    /** One ordered consumer; idle calls retain snapshot identity for changed-cell rendering. */
    public synchronized Snapshot drain() throws BridgeException {
        if (disposed) { return snapshot; }
        boolean changed = false;
        try {
            while (!queue.isEmpty()) {
                // Expiry/deadlines must win before a queued reply can replace cached text
                // or consume its pending request. Recheck between frames as clocks advance.
                changed |= expire();
                Frame frame = queue.removeFirst();
                if (frame.sequence() != Math.addExact(incoming, 1)) { throw BridgeData.invalid(); }
                incoming = frame.sequence(); apply(frame); changed = true;
            }
            changed |= expire();
            if (changed) { publish(); }
            return snapshot;
        } catch (RuntimeException failure) { throw fail(BridgeException.Code.STATE_INVALID); }
    }
    public synchronized Frame request(Target target, long cursor, int limit) throws BridgeException {
        checkReady();
        if (pending.size() >= MAX_PENDING) { throw fail(BridgeException.Code.OVERFLOW); }
        try {
            var payload = new Request(target, cursor, limit); Frame frame = outgoing(payload);
            pending.put(frame.requestId(), new Pending(target, nanoTime.getAsLong())); lastError = null; publish(); return frame;
        } catch (RuntimeException failure) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
    }
    /** Reserve exactly one attempt. A failed/unknown write must not blindly replay a password/code. */
    public synchronized AuthSubmission authenticate(byte[] value) throws BridgeException {
        checkOpen();
        if (auth == null || !auth.step().input() || consumed) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
        if (retryRemaining() != 0) { throw new BridgeException(BridgeException.Code.TIMEOUT); }
        final long id;
        try { id = Math.addExact(issued, 1); }
        catch (ArithmeticException failure) { throw fail(BridgeException.Code.STATE_INVALID); }
        AuthSubmission submission = AuthSubmission.copyOf(generation, id, auth.token(), auth.step(), value);
        issued = id; consumed = true; authRequest = id; authStarted = nanoTime.getAsLong(); lastError = null; publish(); return submission;
    }
    public synchronized Frame control(Action action, long target) throws BridgeException {
        checkOpen();
        final Control command;
        try { command = new Control(action, target); }
        catch (RuntimeException failure) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
        if (action == Action.RESYNC) { checkReady(); clearData(); connection = Phase.LOADING; }
        boolean cancelAuth = action == Action.CANCEL && target == authRequest && authRequest != 0;
        if (action == Action.CANCEL) {
            if (target > issued) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
            pending.remove(target);
        }
        Frame frame = outgoing(command);
        if (action == Action.CLOSE || cancelAuth) { close(); } else { publish(); }
        return frame; // Local disposal is NOT an engine-close acknowledgment.
    }
    private Frame outgoing(Payload payload) throws BridgeException {
        try { outgoing = Math.addExact(outgoing, 1); issued = Math.addExact(issued, 1); return new Frame(generation, outgoing, issued, payload); }
        catch (RuntimeException failure) { throw fail(BridgeException.Code.STATE_INVALID); }
    }

    private void apply(Frame frame) {
        switch (frame.payload()) {
            case Auth value -> {
                if (!reply(frame.requestId(), authRequest)) { return; }
                if (auth != null && auth.token().equals(value.token())) {
                    if (!auth.equals(value)) { throw BridgeData.invalid(); }
                    return; // A repeated prompt must NEVER re-arm a consumed token.
                }
                if (!transition(auth == null ? null : auth.step(), value.step())) { throw BridgeData.invalid(); }
                auth = value; consumed = false; authRequest = 0; lastError = null;
                if (value.step() == Step.RETRY) { retryStarted = nanoTime.getAsLong(); retryNanos = Duration.ofSeconds(value.retryAfterSeconds()).toNanos(); }
                if (value.step() == Step.READY || value.step() == Step.CLOSED || value.step() == Step.UNSUPPORTED) { retryNanos = 0; }
                if (value.step() != Step.READY) { clearData(); identity = null; connection = Phase.OFFLINE; }
            }
            case Identity value -> { if (frame.requestId() != 0) { throw BridgeData.invalid(); } ready(); identity = value; }
            case Connection value -> {
                if (frame.requestId() != 0) { throw BridgeData.invalid(); }
                if (value.phase() == Phase.READY) { ready(); if (identity == null) { throw BridgeData.invalid(); } }
                connection = value.phase();
                if (connection == Phase.AUTH_EXPIRED) { clearData(); identity = null; auth = null; authRequest = 0; consumed = true; retryNanos = 0; }
            }
            case Chats value -> {
                if (!response(frame.requestId(), Target.CHATS)) { return; }
                for (var chat : value.rows()) { chats.put(chat.id(), chat); }
                while (chats.size() > MAX_CHATS) { chats.pollFirstEntry(); }
                nextChats = value.nextCursor(); lastError = null;
            }
            case Messages value -> {
                if (!response(frame.requestId(), Target.SAVED_MESSAGES)) { return; }
                active.clear();
                for (var message : value.rows()) {
                    if (!expired(message)) { messages.remove(message.id()); messages.put(message.id(), message); active.add(message.id()); }
                    else { messages.remove(message.id()); }
                }
                while (messages.size() > MAX_MESSAGES) { messages.pollFirstEntry(); }
                nextMessages = value.nextCursor(); acceptedSavedRequest = frame.requestId(); lastError = null;
            }
            case Changed value -> {
                if (frame.requestId() != 0) { throw BridgeData.invalid(); }
                pending.clear(); // In-flight pages must not resurrect pre-edit private text.
                if (auth == null || auth.step() != Step.READY) { return; }
                Message message = value.message();
                if (messages.containsKey(message.id())) {
                    if (expired(message)) { messages.remove(message.id()); active.remove(message.id()); }
                    else { messages.put(message.id(), message); }
                }
            }
            case Removed value -> {
                if (frame.requestId() != 0) { throw BridgeData.invalid(); }
                pending.clear(); // Bounded causal invalidation, not an unbounded tombstone set.
                for (long id : value.ids()) { messages.remove(id); active.remove(id); }
            }
            case Failure value -> {
                if (frame.requestId() == authRequest && authRequest != 0) { authRequest = 0; lastError = value.error(); }
                else if (pending.remove(frame.requestId()) != null || frame.requestId() == 0) { lastError = value.error(); }
                else if (frame.requestId() > issued) { throw BridgeData.invalid(); }
            }
            // The update ingress cannot act as a generic application/TDLib request dispatcher.
            case Control ignored -> throw BridgeData.invalid();
            case Request ignored -> throw BridgeData.invalid();
        }
    }
    private boolean reply(long id, long expected) {
        if (id == 0) { return true; }
        if (id > issued) { throw BridgeData.invalid(); }
        return id == expected && expected != 0;
    }
    private boolean response(long id, Target target) {
        Pending value = pending.get(id);
        if (value == null) { if (id > issued) { throw BridgeData.invalid(); } return false; }
        ready(); if (identity == null || value.target() != target) { throw BridgeData.invalid(); }
        pending.remove(id); return true;
    }
    private boolean expire() throws BridgeException {
        boolean changed = messages.values().removeIf(this::expired);
        active.removeIf(id -> !messages.containsKey(id));
        if (changed) { pending.clear(); }
        if (retryNanos != 0 && retryRemaining() == 0) { retryNanos = 0; changed = true; }
        long now = nanoTime.getAsLong();
        if (authRequest != 0 && now - authStarted >= AUTH_REPLY_NANOS) { throw fail(BridgeException.Code.TIMEOUT); }
        if (pending.values().removeIf(value -> now - value.started() >= REQUEST_NANOS)) { lastError = BridgeData.Error.NOT_AVAILABLE; changed = true; }
        return changed;
    }
    private boolean expired(Message message) { return message.expiresAt() != 0 && message.expiresAt() <= clock.instant().getEpochSecond(); }
    private int retryRemaining() {
        if (retryNanos == 0) { return 0; }
        long elapsed = nanoTime.getAsLong() - retryStarted;
        long remaining = elapsed <= 0 ? retryNanos : Math.max(0, retryNanos - elapsed);
        return (int) ((remaining + 999_999_999L) / 1_000_000_000L);
    }
    private static boolean transition(Step from, Step to) {
        if (from == Step.CLOSED || from == Step.UNSUPPORTED) { return to == Step.CLOSED; }
        if (to == Step.CLOSED || to == Step.UNSUPPORTED) { return true; }
        if (from == null) { return to == Step.PHONE || to == Step.EMAIL || to == Step.READY; }
        return switch (from) {
            case PHONE -> to == Step.CODE || to == Step.EMAIL || to == Step.RETRY || to == Step.PHONE;
            case CODE, EMAIL_CODE -> to == Step.PASSWORD || to == Step.READY || to == Step.RETRY || to == from || to == Step.EMAIL;
            case EMAIL -> to == Step.EMAIL_CODE || to == Step.RETRY || to == Step.EMAIL;
            case PASSWORD -> to == Step.READY || to == Step.RETRY || to == Step.PASSWORD;
            case RETRY -> to.input();
            case READY -> to == Step.READY;
            case UNSUPPORTED, CLOSED -> false;
        };
    }
    private void ready() { if (auth == null || auth.step() != Step.READY) { throw BridgeData.invalid(); } }
    private void checkReady() throws BridgeException {
        checkOpen(); if (auth == null || auth.step() != Step.READY || identity == null) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
    }
    private void checkOpen() throws BridgeException { if (disposed) { throw new BridgeException(BridgeException.Code.STATE_INVALID); } }
    private void clearData() { pending.clear(); chats.clear(); messages.clear(); active.clear(); nextChats = 0; nextMessages = 0; acceptedSavedRequest = 0; }
    private void publish() {
        var visible = active.stream().map(messages::get).filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(Message::date).thenComparingLong(Message::id)).toList();
        snapshot = new Snapshot(generation, incoming, auth, identity, connection, List.copyOf(chats.values()), visible,
                nextChats, nextMessages, acceptedSavedRequest, messages.size(), pending.size() + (authRequest == 0 ? 0 : 1), retryRemaining(), lastError, failure, disposed);
    }
    private BridgeException fail(BridgeException.Code code) { failure = code; close(); return new BridgeException(code); }
    @Override public synchronized void close() {
        disposed = true; queue.clear(); clearData(); auth = null; identity = null; authRequest = 0; retryNanos = 0; connection = Phase.OFFLINE; publish();
    }
}
