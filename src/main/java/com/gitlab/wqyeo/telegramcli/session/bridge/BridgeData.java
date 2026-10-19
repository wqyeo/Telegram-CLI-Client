package com.gitlab.wqyeo.telegramcli.session.bridge;

import com.fasterxml.jackson.annotation.JsonValue;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Application-owned bounded values. No SDK objects/functions or authentication secrets. */
public final class BridgeData {
    public static final int MAX_CHATS = 100;
    public static final int MAX_MESSAGES = 100;
    public static final int CHAT_PAGE = 20;
    public static final int MESSAGE_PAGE = 10;
    private BridgeData() {}

    public enum Kind {
        CONTROL(1, 4096), AUTH(2, 4096), REQUEST(3, 4096), IDENTITY(4, 4096),
        CHATS(5, 65536), MESSAGES(6, 262144), REMOVED(7, 4096), CONNECTION(8, 4096), FAILURE(9, 4096), CHANGED(10, 16384),
        ACCOUNT(11, 4096), HISTORY_REQUEST(12, 4096), HISTORY_PAGE(13, 262144),
        MESSAGE_UPDATE(14, 16384), CHAT_REMOVED(15, 4096), DIRECTORY(16, 65536);
        private final int code;
        private final int maxBytes;
        Kind(int code, int maxBytes) { this.code = code; this.maxBytes = maxBytes; }
        public int code() { return code; }
        public int maxBytes() { return maxBytes; }
        static Kind fromCode(int code) {
            for (var kind : values()) { if (kind.code == code) { return kind; } }
            throw invalid();
        }
    }
    public enum Action { CLOSE, CANCEL, RESYNC }
    public enum Step {
        PHONE, CODE, EMAIL, EMAIL_CODE, PASSWORD, RETRY, READY, UNSUPPORTED, CLOSED;
        public boolean input() { return this == PHONE || this == CODE || this == EMAIL || this == EMAIL_CODE || this == PASSWORD; }
    }
    public enum Unsupported { NONE, REGISTRATION, TERMS, PAYMENT, OTHER_DEVICE, UNKNOWN }
    public enum Target { CHATS, SAVED_MESSAGES }
    public enum ChatKind { USER, GROUP, UNSUPPORTED }
    public enum Phase { OFFLINE, LOADING, READY, AUTH_EXPIRED }
    public enum Error { INVALID_CODE, INVALID_PASSWORD, RETRY_LATER, UNSUPPORTED, CANCELLED, NOT_AVAILABLE }

    public sealed interface Payload permits Control, Auth, Request, Identity, Chats, Messages, Changed, Removed, Connection, Failure,
            Account, HistoryRequest, HistoryPage, MessageUpdate, ChatRemoved, Directory {
        Kind kind();
        // Ordinary Jackson/debug output must not accidentally dump private response text.
        @JsonValue default String redacted() { return "[REDACTED bridge payload]"; }
    }
    public record Frame(int version, UUID generation, long sequence, long requestId, Payload payload) {
        /** Preserve the original PUBLIC preview wire/API unchanged. */
        public Frame(UUID generation, long sequence, long requestId, Payload payload) { this(1, generation, sequence, requestId, payload); }
        public Frame {
            if (generation == null || sequence < 1 || requestId < 0 || payload == null || !supports(version, payload.kind())
                    || (version == 2 && payload instanceof Request r && r.target() != Target.CHATS)
                    || ((payload instanceof Request || payload instanceof Chats || payload instanceof Messages
                         || payload instanceof HistoryRequest || payload instanceof HistoryPage || payload instanceof Directory) && requestId == 0)) { throw invalid(); }
        }
        @JsonValue public String redacted() { return "[REDACTED bridge frame]"; }
        @Override public String toString() { return redacted(); }
    }
    public record Control(Action action, long targetRequest) implements Payload {
        public Control {
            if (action == null || (action == Action.CANCEL ? targetRequest < 1 : targetRequest != 0)) { throw invalid(); }
        }
        @Override public Kind kind() { return Kind.CONTROL; }
        @Override public String toString() { return redacted(); }
    }
    public record Auth(Step step, UUID token, int retryAfterSeconds, Unsupported unsupported) implements Payload {
        public Auth {
            if (step == null || token == null || unsupported == null || retryAfterSeconds < 0 || retryAfterSeconds > 86400
                    || (step != Step.RETRY && retryAfterSeconds != 0)
                    || ((step == Step.UNSUPPORTED) != (unsupported != Unsupported.NONE))) { throw invalid(); }
        }
        @Override public Kind kind() { return Kind.AUTH; }
        @Override public String toString() { return redacted(); }
    }
    public record Request(Target target, long cursor, int limit) implements Payload {
        public Request {
            if (target == null || cursor < 0 || limit < 1 || limit > (target == Target.CHATS ? CHAT_PAGE : MESSAGE_PAGE)) { throw invalid(); }
        }
        @Override public Kind kind() { return Kind.REQUEST; }
        @Override public String toString() { return redacted(); }
    }
    public record Identity(long userId, long savedChatId, String displayName) implements Payload {
        public Identity {
            if (userId < 1 || savedChatId < 1) { throw invalid(); }
            text(displayName, 256);
        }
        @Override public Kind kind() { return Kind.IDENTITY; }
        @Override public String toString() { return redacted(); }
    }
    public record Chat(long id, String title, ChatKind chatKind) {
        public Chat { if (id == 0 || chatKind == null) { throw invalid(); } text(title, 256); }
        public static Chat project(long id, String title, ChatKind kind) { return new Chat(id, displayName(title), kind); }
        @JsonValue public String redacted() { return "[REDACTED chat]"; }
        @Override public String toString() { return redacted(); }
    }
    public record Chats(List<Chat> rows, long nextCursor) implements Payload {
        public Chats {
            if (rows == null || rows.size() > CHAT_PAGE || nextCursor < 0 || rows.stream().anyMatch(Objects::isNull)
                    || rows.stream().map(Chat::id).distinct().count() != rows.size()) { throw invalid(); }
            rows = List.copyOf(rows);
        }
        @Override public Kind kind() { return Kind.CHATS; }
        @Override public String toString() { return redacted(); }
    }
    public record Message(long id, String sender, String text, long date, long expiresAt, boolean edited) {
        public Message {
            if (id < 1 || date < 0 || expiresAt < 0) { throw invalid(); }
            BridgeData.text(sender, 128); BridgeData.text(text, 2048);
        }
        @JsonValue public String redacted() { return "[REDACTED message]"; }
        @Override public String toString() { return redacted(); }
    }
    public record Messages(List<Message> rows, long nextCursor) implements Payload {
        public Messages {
            if (rows == null || rows.size() > MESSAGE_PAGE || nextCursor < 0 || rows.stream().anyMatch(Objects::isNull)
                    || rows.stream().map(Message::id).distinct().count() != rows.size()) { throw invalid(); }
            rows = List.copyOf(rows);
        }
        @Override public Kind kind() { return Kind.MESSAGES; }
        @Override public String toString() { return redacted(); }
    }
    public record Changed(Message message) implements Payload {
        public Changed { if (message == null) { throw invalid(); } }
        @Override public Kind kind() { return Kind.CHANGED; }
        @Override public String toString() { return redacted(); }
    }
    public record Removed(List<Long> ids, boolean expired) implements Payload {
        public Removed {
            if (ids == null || ids.size() > MAX_MESSAGES || ids.stream().anyMatch(id -> id == null || id < 1)
                    || ids.stream().distinct().count() != ids.size()) { throw invalid(); }
            ids = List.copyOf(ids);
        }
        @Override public Kind kind() { return Kind.REMOVED; }
        @Override public String toString() { return redacted(); }
    }
    public record Connection(Phase phase) implements Payload {
        public Connection { if (phase == null) { throw invalid(); } }
        @Override public Kind kind() { return Kind.CONNECTION; }
        @Override public String toString() { return redacted(); }
    }
    public record Failure(Error error) implements Payload {
        public Failure { if (error == null) { throw invalid(); } }
        @Override public Kind kind() { return Kind.FAILURE; }
        @Override public String toString() { return redacted(); }
    }
    /** V2 deliberately shares only lifecycle/auth/control and directory-request shapes with v1. */
    static boolean supports(int version, Kind kind) {
        return version == 1 ? kind.code() <= 10 : version == 2 && (kind.code() >= 11
                || kind == Kind.CONTROL || kind == Kind.AUTH || kind == Kind.REQUEST || kind == Kind.CONNECTION || kind == Kind.FAILURE);
    }
    public enum ContinuationState { AVAILABLE, END, UNKNOWN }
    public record Continuation(ContinuationState state, long cursor) {
        public Continuation {
            if (state == null || (state == ContinuationState.AVAILABLE ? cursor < 1 : cursor != 0)) { throw invalid(); }
        }
        public static Continuation unknown() { return new Continuation(ContinuationState.UNKNOWN, 0); }
        public static Continuation end() { return new Continuation(ContinuationState.END, 0); }
        @JsonValue public String redacted() { return "[REDACTED continuation]"; }
        @Override public String toString() { return redacted(); }
    }
    public record Account(long userId, String displayName) implements Payload {
        public Account { if (userId < 1) { throw invalid(); } text(displayName, 256); }
        public static Account project(long userId, String name) { return new Account(userId, BridgeData.displayName(name)); }
        @Override public Kind kind() { return Kind.ACCOUNT; }
        @Override public String toString() { return redacted(); }
    }
    public record Directory(List<Chat> rows, Continuation next) implements Payload {
        public Directory { rows = new Chats(rows, 0).rows(); Objects.requireNonNull(next); }
        @Override public Kind kind() { return Kind.DIRECTORY; }
        @Override public String toString() { return redacted(); }
    }
    public record HistoryRequest(long chatId, long selection, long window, long cursor, int limit) implements Payload {
        public HistoryRequest {
            if (chatId == 0 || selection < 1 || window < 1 || cursor < 0 || limit < 1 || limit > MESSAGE_PAGE) { throw invalid(); }
        }
        @Override public Kind kind() { return Kind.HISTORY_REQUEST; }
        @Override public String toString() { return redacted(); }
    }
    public record MessageKey(long chatId, long messageId) {
        public MessageKey { if (chatId == 0 || messageId < 1) { throw invalid(); } }
        @JsonValue public String redacted() { return "[REDACTED message key]"; }
        @Override public String toString() { return redacted(); }
    }
    public enum Content { TEXT, UNSUPPORTED }
    public record ReaderMessage(long chatId, long id, String sender, String text, long date, long expiresAt,
                                boolean edited, boolean truncated, Content content) {
        public ReaderMessage {
            if (chatId == 0 || content == null) { throw invalid(); }
            new Message(id, sender, text, date, expiresAt, edited);
        }
        public MessageKey key() { return new MessageKey(chatId, id); }
        /** Bounded display projection only, never normalization of native persisted values. */
        public static ReaderMessage project(long chatId, long id, String sender, String text, long date, long expiresAt, boolean edited, Content content) {
            String name = clip(sender, 128), body = content == Content.UNSUPPORTED ? "[Unsupported content]" : clip(text, 2048);
            return new ReaderMessage(chatId, id, name, body, date, expiresAt, edited,
                    !name.equals(sender) || (content == Content.TEXT && !body.equals(text)), content);
        }
        @JsonValue public String redacted() { return "[REDACTED reader message]"; }
        @Override public String toString() { return redacted(); }
    }
    public record HistoryPage(long chatId, long selection, long window, List<ReaderMessage> rows, Continuation next) implements Payload {
        public HistoryPage {
            if (chatId == 0 || selection < 1 || window < 1 || rows == null || rows.size() > MESSAGE_PAGE || next == null
                    || rows.stream().anyMatch(m -> m == null || m.chatId() != chatId)
                    || rows.stream().map(ReaderMessage::id).distinct().count() != rows.size()) { throw invalid(); }
            rows = List.copyOf(rows);
        }
        @Override public Kind kind() { return Kind.HISTORY_PAGE; }
        @Override public String toString() { return redacted(); }
    }
    public enum Update { NEW, EDIT }
    public record MessageUpdate(Update update, ReaderMessage message) implements Payload {
        public MessageUpdate { if (update == null || message == null) { throw invalid(); } }
        @Override public Kind kind() { return Kind.MESSAGE_UPDATE; }
        @Override public String toString() { return redacted(); }
    }
    public record ChatRemoved(long chatId, List<Long> ids, boolean expired) implements Payload {
        public ChatRemoved { if (chatId == 0) { throw invalid(); } ids = new Removed(ids, expired).ids(); }
        @Override public Kind kind() { return Kind.CHAT_REMOVED; }
        @Override public String toString() { return redacted(); }
    }
    private static String displayName(String value) {
        String prefix = clip(value, 256);
        return prefix.length() == value.length() ? prefix : clip(value, 253) + "…";
    }
    /** Unicode-safe prefix without encoding/allocating the complete unbounded source string. */
    public static String clip(String value, int maxBytes) {
        if (value == null || maxBytes < 1 || maxBytes > 2048) { throw invalid(); }
        int end = 0, bytes = 0;
        while (end < value.length()) {
            int cp = value.codePointAt(end);
            if (cp >= 0xd800 && cp <= 0xdfff) { throw invalid(); }
            int size = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + size > maxBytes) { break; }
            bytes += size; end += Character.charCount(cp);
        }
        return end == value.length() ? value : value.substring(0, end);
    }
    static void text(String value, int maxBytes) {
        // Valid UTF-8 needs at least one byte per UTF-16 unit. Bound input before
        // the encoder or getBytes can allocate a caller-sized intermediate copy.
        if (value == null || value.length() > maxBytes || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) { throw invalid(); }
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid bridge value."); }
}
