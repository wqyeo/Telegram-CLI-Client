package com.gitlab.wqyeo.telegramcli.session.bridge;

import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class BridgeProtocolTest {
    private final UUID generation = UUID.randomUUID();
    private final String marker = "PUBLIC_SYNTHETIC_DO_NOT_LOG";
    private Frame frame(Payload payload) { return new Frame(generation, 1, 1, payload); }
    private byte[] encode(Frame frame) throws Exception { var out = new ByteArrayOutputStream(); BridgeProtocol.write(out, frame); return out.toByteArray(); }
    private byte[] raw(Kind kind, String body) throws Exception {
        byte[] prefix = Arrays.copyOf(encode(frame(new Connection(Phase.OFFLINE))), BridgeProtocol.HEADER_BYTES);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer.wrap(prefix).put(8, (byte) kind.code()).putInt(9, bytes.length);
        var output = new ByteArrayOutputStream(); output.write(prefix); output.write(bytes); return output.toByteArray();
    }
    private void refused(byte[] bytes) {
        var error = assertThrows(BridgeException.class, () -> BridgeProtocol.read(new ByteArrayInputStream(bytes)));
        assertEquals(BridgeException.Code.PROTOCOL_INVALID, error.code()); assertNull(error.getCause());
        assertFalse(error.toString().contains(marker));
    }

    @Test void roundTripsEveryAllowlistedPayloadWithPrivateDefaultSerialization() throws Exception {
        var message = new Message(1, marker, "🙂 " + marker, 1, 2, true);
        var chat = new Chat(-123, marker, ChatKind.GROUP);
        var payloads = List.<Payload>of(new Control(Action.CANCEL, 3), new Auth(Step.CODE, UUID.randomUUID(), 0, Unsupported.NONE),
                new Auth(Step.RETRY, UUID.randomUUID(), 86400, Unsupported.NONE), new Auth(Step.UNSUPPORTED, UUID.randomUUID(), 0, Unsupported.OTHER_DEVICE),
                new Request(Target.SAVED_MESSAGES, 0, 10), new Identity(1, 2, marker), new Chats(List.of(chat), 20),
                new Messages(List.of(message), 1), new Changed(message), new Removed(List.of(1L), true),
                new Connection(Phase.OFFLINE), new Failure(BridgeData.Error.INVALID_CODE));
        var mapper = JsonMapper.builder().build();
        for (var payload : payloads) {
            Frame original = frame(payload); byte[] bytes = encode(original);
            assertEquals(original, BridgeProtocol.read(new ByteArrayInputStream(bytes)));
            assertFalse(original.toString().contains(marker)); assertFalse(payload.toString().contains(marker));
            assertFalse(mapper.writeValueAsString(original).contains(marker));
            assertEquals("\"[REDACTED bridge payload]\"", mapper.writeValueAsString(payload));
        }
        assertFalse(mapper.writeValueAsString(message).contains(marker)); assertFalse(mapper.writeValueAsString(chat).contains(marker));
    }

    @Test void fragmentedFramesPreserveTheFollowingBoundaryAndDistinguishEof() throws Exception {
        Frame first = frame(new Connection(Phase.LOADING)), second = new Frame(generation, 2, 0, new Connection(Phase.READY));
        var bytes = new ByteArrayOutputStream(); bytes.write(encode(first)); bytes.write(encode(second));
        var input = new FilterInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
            @Override public int read(byte[] target, int offset, int length) throws IOException { return super.read(target, offset, Math.min(1, length)); }
        };
        assertEquals(first, BridgeProtocol.read(input)); assertEquals(second, BridgeProtocol.read(input)); assertNull(BridgeProtocol.read(input));
        byte[] valid = encode(first);
        for (int size = 1; size < valid.length; size++) { refused(Arrays.copyOf(valid, size)); }
    }

    @Test void invalidHeadersAreRefusedBeforeReadingAnyBody() throws Exception {
        byte[] valid = encode(frame(new Connection(Phase.OFFLINE)));
        var badHeaders = new ArrayList<byte[]>();
        byte[] magic = valid.clone(); magic[0] ^= 1; badHeaders.add(magic);
        byte[] kind = valid.clone(); kind[8] = (byte) 255; badHeaders.add(kind);
        for (int length : new int[] {-1, 0, 4097, Integer.MAX_VALUE}) {
            byte[] bad = valid.clone(); ByteBuffer.wrap(bad).putInt(9, length); badHeaders.add(bad);
        }
        byte[] sequence = valid.clone(); ByteBuffer.wrap(sequence).putLong(29, 0); badHeaders.add(sequence);
        byte[] request = valid.clone(); ByteBuffer.wrap(request).putLong(37, -1); badHeaders.add(request);
        for (byte[] bad : badHeaders) {
            byte[] header = Arrays.copyOf(bad, BridgeProtocol.HEADER_BYTES);
            var input = new InputStream() {
                int position;
                @Override public int read() { if (position >= header.length) { throw new AssertionError("Invalid header attempted a body read"); } return header[position++] & 255; }
            };
            assertThrows(BridgeException.class, () -> BridgeProtocol.read(input));
        }
    }

    @Test void strictSchemaTypesDuplicatesUnicodeAndTrailingTokens() throws Exception {
        for (String body : new String[] {"null", "[]", "{}", "{\"phase\":null}", "{\"phase\":1}",
                "{\"phase\":\"OFFLINE\",\"extra\":\"" + marker + "\"}", "{\"phase\":\"OFFLINE\",\"phase\":\"READY\"}",
                "{\"phase\":\"OFFLINE\"} {}", "{\"phase\":\"UNKNOWN\"}", "{\"phase\":[[[[[[[[]]]]]]]]}", "{"}) {
            refused(raw(Kind.CONNECTION, body));
        }
        refused(raw(Kind.REQUEST, "{\"target\":\"CHATS\",\"cursor\":0.0,\"limit\":1}"));
        refused(raw(Kind.REQUEST, "{\"target\":\"CHATS\",\"cursor\":\"0\",\"limit\":1}"));
        refused(raw(Kind.REQUEST, "{\"target\":\"SEND\",\"cursor\":0,\"limit\":1}"));
        refused(raw(Kind.IDENTITY, "{\"userId\":1,\"savedChatId\":2,\"displayName\":\"\\ud800\"}"));
        byte[] utf8 = raw(Kind.CONNECTION, "{\"phase\":\"OFFLINE\"}"); utf8[utf8.length - 2] = (byte) 0xff; refused(utf8);
    }

    @Test void modelAndCollectionLimitsAreImmutableAndFailClosed() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new Request(Target.CHATS, 0, 21));
        assertThrows(IllegalArgumentException.class, () -> new Request(Target.SAVED_MESSAGES, 0, 11));
        assertThrows(IllegalArgumentException.class, () -> new Identity(0, 1, ""));
        assertThrows(IllegalArgumentException.class, () -> new Chat(1, "x".repeat(257), ChatKind.USER));
        assertThrows(IllegalArgumentException.class, () -> new Message(1, "", "🙂".repeat(513), 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new Frame(generation, 1, 0, new Request(Target.CHATS, 0, 1)));
        assertThrows(IllegalArgumentException.class, () -> new Auth(Step.PHONE, UUID.randomUUID(), 1, Unsupported.NONE));
        var chat = new Chat(1, "synthetic", ChatKind.USER);
        assertThrows(IllegalArgumentException.class, () -> new Chats(Collections.nCopies(21, chat), 0));
        assertThrows(IllegalArgumentException.class, () -> new Chats(List.of(chat, chat), 0));
        assertThrows(IllegalArgumentException.class, () -> new Removed(List.of(1L, 1L), false));
        var rows = new ArrayList<Chat>(List.of(chat)); var page = new Chats(rows, 0); rows.clear(); assertEquals(1, page.rows().size());
        assertThrows(UnsupportedOperationException.class, () -> page.rows().clear());
        // Maximum permitted JSON escaping expansion remains bounded and round-trips.
        var escaped = new Messages(Collections.singletonList(new Message(1, "\0".repeat(128), "\0".repeat(2048), 0, 0, false)), 0);
        assertEquals(frame(escaped), BridgeProtocol.read(new ByteArrayInputStream(encode(frame(escaped)))));
    }

    @Test void dtoTextRejectsLargeInputsAndEnforcesExactUtf8BoundsAndSurrogates() {
        for (int maximum : new int[] {128, 256, 2048}) {
            java.util.function.Function<String, Object> make = switch (maximum) {
                case 128 -> value -> new Message(1, value, "", 0, 0, false);
                case 256 -> value -> new Chat(1, value, ChatKind.USER);
                default -> value -> new Message(1, "", value, 0, 0, false);
            };
            for (String value : new String[] {"a".repeat(maximum), "🙂".repeat(maximum / 4),
                    "€".repeat(maximum / 3) + "a".repeat(maximum % 3)}) {
                assertDoesNotThrow(() -> make.apply(value));
            }
            for (String value : new String[] {"a".repeat(1_048_576), "🙂".repeat(262_144),
                    "a".repeat(maximum + 1), "🙂".repeat(maximum / 4 + 1), "€".repeat(maximum / 3 + 1), "\ud800", "\udc00"}) {
                var failure = assertThrows(IllegalArgumentException.class, () -> make.apply(value));
                assertEquals("Invalid bridge value.", failure.getMessage()); assertNull(failure.getCause());
            }
        }
    }

    @Test void ioFailuresAndNullWritesCannotExposeRawCauses() {
        var input = new InputStream() { @Override public int read() throws IOException { throw new IOException(marker); } };
        assertNull(assertThrows(BridgeException.class, () -> BridgeProtocol.read(input)).getCause());
        var output = new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException(marker); } };
        var error = assertThrows(BridgeException.class, () -> BridgeProtocol.write(output, frame(new Connection(Phase.READY))));
        assertNull(error.getCause()); assertFalse(error.getMessage().contains(marker));
        assertThrows(BridgeException.class, () -> BridgeProtocol.write(OutputStream.nullOutputStream(), null));
    }
}
