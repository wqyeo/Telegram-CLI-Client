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
                           BridgeException.Code failure, boolean disposed, ReaderView reader) {
        public Snapshot { chats = List.copyOf(chats); messages = List.copyOf(messages); }
        @JsonValue public String redacted() { return "[REDACTED bridge snapshot]"; }
        @Override public String toString() { return redacted(); }
    }
    /** PUBLIC reader projection only; disposal never asserts native-stop acceptance. */
    public record ReaderView(Account account, long chatId, long selection, long window, boolean latestWindow,
                             List<ReaderMessage> messages, Continuation next, Continuation directoryNext,
                             long requestedHistoryRequest, long acceptedHistoryRequest, List<Chat> searchResults, String searchQuery, boolean loading, int incomingIndicator, int retainedMessages) {
        public ReaderView { messages = List.copyOf(messages); searchResults = List.copyOf(searchResults); }
        public String searchCoverage() { return "LOADED_ONLY"; }
        public Set<ChatKind> supportedChatKinds() { return Set.of(ChatKind.USER); }
        public boolean nativeStopAccepted() { return false; }
        @JsonValue public String redacted() { return "[REDACTED PUBLIC reader view]"; }
        @Override public String toString() { return redacted(); }
    }
    private record Pending(Target target, long started, HistoryRequest history) {
        private Pending(Target target, long started) { this(target, started, null); }
    }
    private final int version;
    private Account account;
    private final LinkedHashMap<MessageKey, ReaderMessage> readerMessages = new LinkedHashMap<>();
    private final List<MessageKey> readerActive = new ArrayList<>();
    private long selectedChat, selectionCounter, selection, windowCounter, window, requestedHistoryRequest, acceptedHistoryRequest;
    private boolean latestWindow;
    private int incomingIndicator;
    private String searchQuery = "";
    private Continuation readerNext = Continuation.unknown(), directoryNext = Continuation.unknown();
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
    public BridgeCoordinator(UUID generation, Clock clock, LongSupplier nanoTime) { this(generation, clock, nanoTime, 1); }
    public static BridgeCoordinator publicReader(UUID generation) { return publicReader(generation, Clock.systemUTC(), System::nanoTime); }
    public static BridgeCoordinator publicReader(UUID generation, Clock clock, LongSupplier nanoTime) { return new BridgeCoordinator(generation, clock, nanoTime, 2); }
    private BridgeCoordinator(UUID generation, Clock clock, LongSupplier nanoTime, int version) {
        this.version = version;
        this.generation = Objects.requireNonNull(generation); this.clock = Objects.requireNonNull(clock);
        this.nanoTime = Objects.requireNonNull(nanoTime); publish();
    }
    public int protocolVersion() { return version; }
    public Snapshot latest() { return snapshot; }
    public synchronized int queued() { return queue.size(); }

    /** Producer offer never waits and never silently drops a current-generation transition. */
    public synchronized void offer(Frame frame) throws BridgeException {
        if (frame != null && !generation.equals(frame.generation())) { return; }
        checkOpen();
        if (frame == null || frame.version() != version) { throw fail(BridgeException.Code.STATE_INVALID); }
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
        if (version == 2 && (target != Target.CHATS || pending.values().stream().anyMatch(p -> p.target() == Target.CHATS))) {
            throw new BridgeException(BridgeException.Code.STATE_INVALID);
        }
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
        try { outgoing = Math.addExact(outgoing, 1); issued = Math.addExact(issued, 1); return new Frame(version, generation, outgoing, issued, payload); }
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
                if (value.step() != Step.READY) { clearData(); identity = null; account = null; connection = Phase.OFFLINE; }
            }
            case Identity value -> { if (frame.requestId() != 0) { throw BridgeData.invalid(); } ready(); identity = value; }
            case Connection value -> {
                if (frame.requestId() != 0) { throw BridgeData.invalid(); }
                if (value.phase() == Phase.READY) { ready(); if (!hasIdentity()) { throw BridgeData.invalid(); } }
                connection = value.phase();
                if (connection == Phase.AUTH_EXPIRED) { clearData(); identity = null; account = null; auth = null; authRequest = 0; consumed = true; retryNanos = 0; }
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
            case Account value -> {
                unsolicited(frame); ready();
                if (account != null && account.userId() != value.userId()) { throw BridgeData.invalid(); }
                account = value;
            }
            case Directory value -> {
                if (!response(frame.requestId(), Target.CHATS)) { return; }
                for (var chat : value.rows()) { chats.put(chat.id(), chat); }
                while (chats.size() > MAX_CHATS) { chats.pollFirstEntry(); }
                directoryNext = value.next(); lastError = null;
            }
            case HistoryPage value -> applyHistory(frame.requestId(), value);
            case MessageUpdate value -> { unsolicited(frame); applyUpdate(value); }
            case ChatRemoved value -> {
                unsolicited(frame); invalidateHistory(value.chatId());
                for (long id : value.ids()) { var key = new MessageKey(value.chatId(), id); readerMessages.remove(key); readerActive.remove(key); }
            }
            case HistoryRequest ignored -> throw BridgeData.invalid();
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
        ready(); if (!hasIdentity() || value.target() != target || value.history() != null) { throw BridgeData.invalid(); }
        pending.remove(id); return true;
    }
    private boolean expire() throws BridgeException {
        boolean changed = messages.values().removeIf(this::expired);
        active.removeIf(id -> !messages.containsKey(id));
        if (changed) { pending.clear(); }
        var expiredKeys = readerMessages.values().stream().filter(this::expired).map(ReaderMessage::key).toList();
        for (var key : expiredKeys) { invalidateHistory(key.chatId()); readerMessages.remove(key); readerActive.remove(key); }
        changed |= !expiredKeys.isEmpty();
        if (retryNanos != 0 && retryRemaining() == 0) { retryNanos = 0; changed = true; }
        long now = nanoTime.getAsLong();
        if (authRequest != 0 && now - authStarted >= AUTH_REPLY_NANOS) { throw fail(BridgeException.Code.TIMEOUT); }
        if (pending.values().removeIf(value -> now - value.started() >= REQUEST_NANOS)) { lastError = BridgeData.Error.NOT_AVAILABLE; changed = true; }
        return changed;
    }
    private boolean expired(ReaderMessage message) { return message.expiresAt() != 0 && message.expiresAt() <= clock.instant().getEpochSecond(); }
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
        checkOpen(); if (auth == null || auth.step() != Step.READY || !hasIdentity() || (version == 2 && connection != Phase.READY)) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
    }
    private void checkOpen() throws BridgeException { if (disposed) { throw new BridgeException(BridgeException.Code.STATE_INVALID); } }
    private boolean hasIdentity() { return version == 1 ? identity != null : account != null; }
    private void clearData() {
        pending.clear(); chats.clear(); messages.clear(); active.clear(); nextChats = 0; nextMessages = 0; acceptedSavedRequest = 0;
        readerMessages.clear(); readerActive.clear(); selectedChat = 0; selection = 0; window = 0; incomingIndicator = 0;
        requestedHistoryRequest = 0; acceptedHistoryRequest = 0; latestWindow = false;
        searchQuery = ""; readerNext = Continuation.unknown(); directoryNext = Continuation.unknown();
    }
    private void publish() {
        var visible = active.stream().map(messages::get).filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(Message::date).thenComparingLong(Message::id)).toList();
        snapshot = new Snapshot(generation, incoming, auth, identity, connection, List.copyOf(chats.values()), visible,
                nextChats, nextMessages, acceptedSavedRequest, messages.size(), pending.size() + (authRequest == 0 ? 0 : 1), retryRemaining(), lastError, failure, disposed, readerView());
    }
    private void readerReady() throws BridgeException {
        checkReady(); if (version != 2) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
    }
    public synchronized void searchLoaded(String query) throws BridgeException {
        readerReady();
        try { BridgeData.text(query, 256); } catch (RuntimeException invalid) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
        searchQuery = query; publish();
    }
    public synchronized Frame directoryPage(boolean first) throws BridgeException {
        readerReady();
        if (!first && directoryNext.state() != ContinuationState.AVAILABLE) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
        return request(Target.CHATS, first ? 0 : directoryNext.cursor(), CHAT_PAGE);
    }
    public synchronized Frame selectChat(long chatId) throws BridgeException {
        readerReady(); Chat chat = chats.get(chatId);
        if (chat == null || chat.chatKind() != ChatKind.USER) {
            lastError = chat == null ? BridgeData.Error.NOT_AVAILABLE : BridgeData.Error.UNSUPPORTED; publish();
            throw new BridgeException(BridgeException.Code.STATE_INVALID);
        }
        try { selectionCounter = Math.incrementExact(selectionCounter); }
        catch (ArithmeticException overflow) { throw fail(BridgeException.Code.STATE_INVALID); }
        selectedChat = chatId; selection = selectionCounter; incomingIndicator = 0;
        return historyPage(true);
    }
    public synchronized Frame historyPage(boolean latest) throws BridgeException {
        readerReady();
        if (selectedChat == 0 || (!latest && readerNext.state() != ContinuationState.AVAILABLE)) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
        long cursor = latest ? 0 : readerNext.cursor();
        pending.values().removeIf(p -> p.history() != null);
        if (pending.size() >= MAX_PENDING) { throw fail(BridgeException.Code.OVERFLOW); }
        try {
            windowCounter = Math.incrementExact(windowCounter); window = windowCounter;
            var query = new HistoryRequest(selectedChat, selection, window, cursor, MESSAGE_PAGE);
            Frame frame = outgoing(query); pending.put(frame.requestId(), new Pending(null, nanoTime.getAsLong(), query));
            requestedHistoryRequest = frame.requestId();
            latestWindow = latest; readerActive.clear(); readerNext = Continuation.unknown(); lastError = null;
            if (latest) { incomingIndicator = 0; }
            publish(); return frame;
        } catch (ArithmeticException overflow) { throw fail(BridgeException.Code.STATE_INVALID); }
    }
    public synchronized Frame cancelRead(long requestId) throws BridgeException {
        checkOpen();
        if (requestId == authRequest && authRequest != 0) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
        return control(Action.CANCEL, requestId);
    }
    private void unsolicited(Frame frame) { if (frame.requestId() != 0) { throw BridgeData.invalid(); } }
    private void applyHistory(long requestId, HistoryPage page) {
        Pending p = pending.get(requestId);
        if (p == null) { if (requestId > issued) { throw BridgeData.invalid(); } return; }
        HistoryRequest q = p.history(); ready();
        if (q == null || !hasIdentity() || page.chatId() != q.chatId() || page.selection() != q.selection() || page.window() != q.window()) { throw BridgeData.invalid(); }
        pending.remove(requestId);
        if (page.chatId() != selectedChat || page.selection() != selection || page.window() != window) { return; }
        if (q.cursor() != 0 && page.next().state() == ContinuationState.AVAILABLE && page.next().cursor() == q.cursor()) { throw BridgeData.invalid(); }
        readerActive.clear();
        for (var message : page.rows()) {
            if (expired(message)) { readerMessages.remove(message.key()); }
            else { readerMessages.remove(message.key()); readerMessages.put(message.key(), message); readerActive.add(message.key()); }
        }
        readerNext = page.next(); acceptedHistoryRequest = requestId; trimReaderCache(); lastError = null;
    }
    private void invalidateHistory(long chatId) {
        pending.values().removeIf(p -> p.history() != null && p.history().chatId() == chatId);
        if (chatId == selectedChat) { readerNext = Continuation.unknown(); }
    }
    private void applyUpdate(MessageUpdate event) {
        if (auth == null || auth.step() != Step.READY || account == null) { return; }
        ReaderMessage m = event.message(); boolean known = readerMessages.containsKey(m.key());
        if (event.update() == Update.EDIT && !known) { invalidateHistory(m.chatId()); return; }
        if (event.update() == Update.NEW && known) { return; } // A duplicate arrival is not an edit or an additional incoming message.
        Chat chat = chats.get(m.chatId());
        if (!known && m.chatId() != selectedChat && (chat == null || chat.chatKind() != ChatKind.USER)) { return; }
        invalidateHistory(m.chatId());
        if (expired(m)) { readerMessages.remove(m.key()); readerActive.remove(m.key()); return; }
        readerMessages.remove(m.key()); readerMessages.put(m.key(), m);
        if (m.chatId() == selectedChat && event.update() == Update.NEW && !known) {
            if (latestWindow) {
                readerActive.add(m.key());
                readerActive.sort(Comparator.comparingLong((MessageKey key) -> readerMessages.get(key).date()).thenComparingLong(MessageKey::messageId));
                while (readerActive.size() > MESSAGE_PAGE) { readerActive.removeFirst(); }
            } else { incomingIndicator = Math.min(MAX_MESSAGES, incomingIndicator + 1); }
        }
        trimReaderCache();
    }
    private void trimReaderCache() {
        while (readerMessages.size() > MAX_MESSAGES) {
            var key = readerMessages.keySet().stream().filter(k -> !readerActive.contains(k)).findFirst().orElseThrow();
            readerMessages.remove(key);
        }
    }
    private ReaderView readerView() {
        if (version != 2) { return null; }
        var visible = readerActive.stream().map(readerMessages::get).filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(ReaderMessage::date).thenComparingLong(ReaderMessage::id)).toList();
        String term = searchQuery.toLowerCase(Locale.ROOT);
        var found = chats.values().stream().filter(c -> c.title().toLowerCase(Locale.ROOT).contains(term)).toList();
        return new ReaderView(account, selectedChat, selection, window, latestWindow, visible, readerNext, directoryNext,
                requestedHistoryRequest, acceptedHistoryRequest, found, searchQuery, pending.values().stream().anyMatch(p -> p.history() != null), incomingIndicator, readerMessages.size());
    }
    private BridgeException fail(BridgeException.Code code) { failure = code; close(); return new BridgeException(code); }
    @Override public synchronized void close() {
        disposed = true; queue.clear(); clearData(); auth = null; identity = null; account = null; authRequest = 0; retryNanos = 0; connection = Phase.OFFLINE; publish();
    }
}
