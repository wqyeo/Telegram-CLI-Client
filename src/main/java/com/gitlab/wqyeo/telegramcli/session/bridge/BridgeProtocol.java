package com.gitlab.wqyeo.telegramcli.session.bridge;

import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Explicit PRIVATE data codec. Not ordinary diagnostics, old lifecycle IPC or a TDLib dispatcher. */
public final class BridgeProtocol {
    private static final byte[] MAGIC = "TGCBRG01".getBytes(StandardCharsets.US_ASCII);
    public static final int HEADER_BYTES = 45;
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(6).maxStringLength(16384).maxNumberLength(20).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private BridgeProtocol() {}

    /** Null only at a frame boundary. Partial/truncated/JSON-null is an error, never clean closure. */
    public static Frame read(InputStream input) throws BridgeException {
        byte[] bytes = null;
        try {
            int first = input.read();
            if (first == -1) { return null; }
            byte[] rest = input.readNBytes(HEADER_BYTES - 1);
            if (rest.length != HEADER_BYTES - 1) { throw invalid(); }
            byte[] header = new byte[HEADER_BYTES]; header[0] = (byte) first;
            System.arraycopy(rest, 0, header, 1, rest.length);
            var buffer = ByteBuffer.wrap(header);
            byte[] magic = new byte[MAGIC.length]; buffer.get(magic);
            if (!Arrays.equals(MAGIC, magic)) { throw invalid(); }
            Kind kind = Kind.fromCode(Byte.toUnsignedInt(buffer.get()));
            int length = buffer.getInt();
            UUID generation = new UUID(buffer.getLong(), buffer.getLong());
            long sequence = buffer.getLong(), request = buffer.getLong();
            if (length < 1 || length > kind.maxBytes() || sequence < 1 || request < 0) { throw invalid(); }
            bytes = input.readNBytes(length);
            if (bytes.length != length) { throw invalid(); }
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return new Frame(generation, sequence, request, decode(kind, MAPPER.readTree(json)));
        } catch (IOException | RuntimeException failure) { throw new BridgeException(BridgeException.Code.PROTOCOL_INVALID); }
        finally { if (bytes != null) { Arrays.fill(bytes, (byte) 0); } }
    }

    public static void write(OutputStream output, Frame frame) throws BridgeException {
        byte[] bytes = null;
        try {
            if (frame == null) { throw invalid(); }
            bytes = MAPPER.writeValueAsBytes(encode(frame.payload()));
            if (bytes.length < 1 || bytes.length > frame.payload().kind().maxBytes()) { throw invalid(); }
            byte[] header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put((byte) frame.payload().kind().code()).putInt(bytes.length)
                    .putLong(frame.generation().getMostSignificantBits()).putLong(frame.generation().getLeastSignificantBits())
                    .putLong(frame.sequence()).putLong(frame.requestId()).array();
            output.write(header); output.write(bytes); output.flush();
        } catch (IOException | RuntimeException failure) { throw new BridgeException(BridgeException.Code.PROTOCOL_INVALID); }
        finally { if (bytes != null) { Arrays.fill(bytes, (byte) 0); } }
    }

    // Manual serialization is deliberate: default Jackson/toString redacts these values.
    private static ObjectNode encode(Payload payload) {
        ObjectNode node = MAPPER.createObjectNode();
        switch (payload) {
            case Control p -> { node.put("action", p.action().name()); node.put("targetRequest", p.targetRequest()); }
            case Auth p -> { node.put("step", p.step().name()); node.put("token", p.token().toString()); node.put("retryAfterSeconds", p.retryAfterSeconds()); node.put("unsupported", p.unsupported().name()); }
            case Request p -> { node.put("target", p.target().name()); node.put("cursor", p.cursor()); node.put("limit", p.limit()); }
            case Identity p -> { node.put("userId", p.userId()); node.put("savedChatId", p.savedChatId()); node.put("displayName", p.displayName()); }
            case Chats p -> {
                var rows = node.putArray("rows");
                for (var chat : p.rows()) { rows.addObject().put("id", chat.id()).put("title", chat.title()).put("chatKind", chat.chatKind().name()); }
                node.put("nextCursor", p.nextCursor());
            }
            case Messages p -> {
                var rows = node.putArray("rows");
                for (var message : p.rows()) {
                    rows.addObject().put("id", message.id()).put("sender", message.sender()).put("text", message.text())
                            .put("date", message.date()).put("expiresAt", message.expiresAt()).put("edited", message.edited());
                }
                node.put("nextCursor", p.nextCursor());
            }
            case Changed p -> {
                var message = p.message();
                node.put("id", message.id()).put("sender", message.sender()).put("text", message.text())
                        .put("date", message.date()).put("expiresAt", message.expiresAt()).put("edited", message.edited());
            }
            case Removed p -> { var ids = node.putArray("ids"); for (long id : p.ids()) { ids.add(id); } node.put("expired", p.expired()); }
            case Connection p -> node.put("phase", p.phase().name());
            case Failure p -> node.put("error", p.error().name());
        }
        return node;
    }

    private static Payload decode(Kind kind, JsonNode node) {
        if (node == null || !node.isObject()) { throw invalid(); }
        return switch (kind) {
            case CONTROL -> { fields(node, "action", "targetRequest"); yield new Control(Action.valueOf(string(node, "action")), number(node, "targetRequest")); }
            case AUTH -> {
                fields(node, "step", "token", "retryAfterSeconds", "unsupported");
                String token = string(node, "token"); UUID uuid = UUID.fromString(token);
                if (!uuid.toString().equals(token)) { throw invalid(); }
                yield new Auth(Step.valueOf(string(node, "step")), uuid, integer(node, "retryAfterSeconds"), Unsupported.valueOf(string(node, "unsupported")));
            }
            case REQUEST -> { fields(node, "target", "cursor", "limit"); yield new Request(Target.valueOf(string(node, "target")), number(node, "cursor"), integer(node, "limit")); }
            case IDENTITY -> { fields(node, "userId", "savedChatId", "displayName"); yield new Identity(number(node, "userId"), number(node, "savedChatId"), string(node, "displayName")); }
            case CHATS -> {
                fields(node, "rows", "nextCursor"); var rows = array(node, "rows", CHAT_PAGE); var chats = new ArrayList<Chat>();
                for (var row : rows) {
                    fields(row, "id", "title", "chatKind");
                    chats.add(new Chat(number(row, "id"), string(row, "title"), ChatKind.valueOf(string(row, "chatKind"))));
                }
                yield new Chats(chats, number(node, "nextCursor"));
            }
            case MESSAGES -> {
                fields(node, "rows", "nextCursor"); var rows = array(node, "rows", MESSAGE_PAGE); var messages = new ArrayList<Message>();
                for (var row : rows) {
                    fields(row, "id", "sender", "text", "date", "expiresAt", "edited");
                    messages.add(new Message(number(row, "id"), string(row, "sender"), string(row, "text"), number(row, "date"), number(row, "expiresAt"), bool(row, "edited")));
                }
                yield new Messages(messages, number(node, "nextCursor"));
            }
            case CHANGED -> {
                fields(node, "id", "sender", "text", "date", "expiresAt", "edited");
                yield new Changed(new Message(number(node, "id"), string(node, "sender"), string(node, "text"), number(node, "date"), number(node, "expiresAt"), bool(node, "edited")));
            }
            case REMOVED -> {
                fields(node, "ids", "expired"); var rows = array(node, "ids", MAX_MESSAGES); var ids = new ArrayList<Long>();
                for (var id : rows) { ids.add(longValue(id)); }
                yield new Removed(ids, bool(node, "expired"));
            }
            case CONNECTION -> { fields(node, "phase"); yield new Connection(Phase.valueOf(string(node, "phase"))); }
            case FAILURE -> { fields(node, "error"); yield new Failure(BridgeData.Error.valueOf(string(node, "error"))); }
        };
    }
    private static void fields(JsonNode node, String... names) {
        if (node == null || !node.isObject() || node.size() != names.length
                || !Arrays.stream(names).allMatch(node::has)) { throw invalid(); }
    }
    private static String string(JsonNode node, String key) {
        var value = node.get(key); if (value == null || !value.isTextual()) { throw invalid(); } return value.textValue();
    }
    private static long longValue(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) { throw invalid(); } return node.longValue();
    }
    private static long number(JsonNode node, String key) { return longValue(node.get(key)); }
    private static int integer(JsonNode node, String key) { return Math.toIntExact(number(node, key)); }
    private static boolean bool(JsonNode node, String key) {
        var value = node.get(key); if (value == null || !value.isBoolean()) { throw invalid(); } return value.booleanValue();
    }
    private static JsonNode array(JsonNode node, String key, int limit) {
        var value = node.get(key); if (value == null || !value.isArray() || value.size() > limit) { throw invalid(); } return value;
    }
}
