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
        CHATS(5, 65536), MESSAGES(6, 262144), REMOVED(7, 4096), CONNECTION(8, 4096), FAILURE(9, 4096), CHANGED(10, 16384);
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

    public sealed interface Payload permits Control, Auth, Request, Identity, Chats, Messages, Changed, Removed, Connection, Failure {
        Kind kind();
        // Ordinary Jackson/debug output must not accidentally dump private response text.
        @JsonValue default String redacted() { return "[REDACTED bridge payload]"; }
    }
    public record Frame(UUID generation, long sequence, long requestId, Payload payload) {
        public Frame {
            if (generation == null || sequence < 1 || requestId < 0 || payload == null
                    || ((payload instanceof Request || payload instanceof Chats || payload instanceof Messages) && requestId == 0)) { throw invalid(); }
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
    private static void text(String value, int maxBytes) {
        // Valid UTF-8 needs at least one byte per UTF-16 unit. Bound input before
        // the encoder or getBytes can allocate a caller-sized intermediate copy.
        if (value == null || value.length() > maxBytes || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) { throw invalid(); }
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid bridge value."); }
}
