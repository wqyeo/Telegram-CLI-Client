package com.gitlab.wqyeo.telegramcli.session.bridge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AuthTransferTest {
    private final UUID generation = UUID.randomUUID(), token = UUID.randomUUID();
    private final byte[] fixture = "PUBLIC_SYNTHETIC-🔑-e\u0301".getBytes(StandardCharsets.UTF_8);
    private AuthSubmission submission() throws Exception { return AuthSubmission.copyOf(generation, 17, token, BridgeData.Step.PASSWORD, fixture); }
    private byte[] encoded() throws Exception {
        try (var value = submission()) { var out = new ByteArrayOutputStream(); AuthTransfer.write(out, value); return out.toByteArray(); }
    }
    private AuthSubmission read(InputStream input) throws Exception { return AuthTransfer.read(input, generation, 17, token, BridgeData.Step.PASSWORD); }
    private void refused(byte[] bytes) {
        var error = assertThrows(BridgeException.class, () -> read(new ByteArrayInputStream(bytes)));
        assertEquals(BridgeException.Code.PROTOCOL_INVALID, error.code()); assertNull(error.getCause());
        assertFalse(error.toString().contains("PUBLIC_SYNTHETIC"));
    }

    @Test void exactSeparateFrameRoundTripAndFollowingPrivateDataBoundary() throws Exception {
        byte[] bytes = encoded(); assertEquals(AuthTransfer.HEADER_BYTES + fixture.length, bytes.length);
        assertEquals("TGCAUT01", new String(bytes, 0, 8, StandardCharsets.US_ASCII));
        assertEquals(fixture.length, ByteBuffer.wrap(bytes).getInt(8));
        var all = new ByteArrayOutputStream(); all.write(bytes);
        var event = new BridgeData.Frame(generation, 1, 0, new BridgeData.Connection(BridgeData.Phase.READY));
        BridgeProtocol.write(all, event);
        var input = new ByteArrayInputStream(all.toByteArray());
        try (var material = read(input)) {
            assertNotNull(material); material.use(value -> { assertArrayEquals(fixture, value); return null; });
            assertEquals(generation, material.generation()); assertEquals(17, material.requestId());
            assertEquals(token, material.token()); assertEquals(BridgeData.Step.PASSWORD, material.step());
        }
        assertEquals(event, BridgeProtocol.read(input)); assertNull(read(input));
        assertThrows(BridgeException.class, () -> BridgeProtocol.read(new ByteArrayInputStream(bytes)));
    }

    @Test void everyPartialFrameIsRejectedAndBoundaryEofIsNotAChallenge() throws Exception {
        byte[] bytes = encoded();
        for (int size = 1; size < bytes.length; size++) { refused(Arrays.copyOf(bytes, size)); }
        assertNull(read(InputStream.nullInputStream()));
        byte[] invalidUtf8 = bytes.clone(); invalidUtf8[invalidUtf8.length - 1] = (byte) 255; refused(invalidUtf8);
    }

    @Test void lengthVersionIdentityRequestStepAndTokenFailBeforeReadingSecretBody() throws Exception {
        byte[] valid = encoded(); var headers = new ArrayList<byte[]>();
        for (int offset : new int[] {0, 7, 12, 36, 52}) { byte[] bad = valid.clone(); bad[offset] ^= 0x7f; headers.add(bad); }
        for (int size : new int[] {0, -1, 4097, Integer.MAX_VALUE}) { byte[] bad = valid.clone(); ByteBuffer.wrap(bad).putInt(8, size); headers.add(bad); }
        byte[] request = valid.clone(); ByteBuffer.wrap(request).putLong(28, 18); headers.add(request);
        for (byte[] bad : headers) {
            byte[] header = Arrays.copyOf(bad, AuthTransfer.HEADER_BYTES);
            var input = new InputStream() {
                int position;
                @Override public int read() { if (position >= header.length) { throw new AssertionError("Rejected auth header read secret body"); } return header[position++] & 255; }
            };
            assertThrows(BridgeException.class, () -> read(input));
        }
        assertThrows(BridgeException.class, () -> AuthTransfer.read(new ByteArrayInputStream(valid), UUID.randomUUID(), 17, token, BridgeData.Step.PASSWORD));
        assertThrows(BridgeException.class, () -> AuthTransfer.read(new ByteArrayInputStream(valid), generation, 18, token, BridgeData.Step.PASSWORD));
        assertThrows(BridgeException.class, () -> AuthTransfer.read(new ByteArrayInputStream(valid), generation, 17, UUID.randomUUID(), BridgeData.Step.PASSWORD));
        assertThrows(BridgeException.class, () -> AuthTransfer.read(new ByteArrayInputStream(valid), generation, 17, token, BridgeData.Step.CODE));
    }

    @Test void boundedStrictUtf8AndOnlyInputStepsAreAccepted() throws Exception {
        for (byte[] bad : new byte[][] {new byte[0], new byte[4097], {(byte) 0xc0, (byte) 0x80}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80}}) {
            assertThrows(BridgeException.class, () -> AuthSubmission.copyOf(generation, 1, token, BridgeData.Step.PHONE, bad));
        }
        for (var step : BridgeData.Step.values()) {
            if (!step.input()) { assertThrows(BridgeException.class, () -> AuthSubmission.copyOf(generation, 1, token, step, fixture)); }
            else { try (var value = AuthSubmission.copyOf(generation, 1, token, step, fixture)) { assertNotNull(value); } }
        }
        assertThrows(BridgeException.class, () -> AuthSubmission.copyOf(generation, 0, token, BridgeData.Step.CODE, fixture));
        assertThrows(BridgeException.class, () -> AuthSubmission.copyOf(null, 1, token, BridgeData.Step.CODE, fixture));
    }

    @Test void ownedAndCallbackCopiesAreDisposedAndDefaultSerializationIsRedacted() throws Exception {
        byte[] caller = fixture.clone(); var value = AuthSubmission.copyOf(generation, 1, token, BridgeData.Step.PASSWORD, caller);
        Arrays.fill(caller, (byte) 0); var captured = new AtomicReference<byte[]>();
        value.use(copy -> { assertArrayEquals(fixture, copy); captured.set(copy); return null; });
        assertArrayEquals(new byte[fixture.length], captured.get());
        assertEquals("\"[REDACTED auth submission]\"", JsonMapper.builder().build().writeValueAsString(value));
        assertFalse(value.toString().contains("PUBLIC_SYNTHETIC"));
        var error = assertThrows(BridgeException.class, () -> value.use(copy -> { captured.set(copy); throw new IllegalStateException("PUBLIC_SYNTHETIC"); }));
        assertNull(error.getCause()); assertFalse(error.toString().contains("PUBLIC_SYNTHETIC"));
        assertArrayEquals(new byte[fixture.length], captured.get());
        value.close(); value.close();
        assertThrows(BridgeException.class, () -> value.use(copy -> null));
        assertThrows(BridgeException.class, () -> AuthTransfer.write(OutputStream.nullOutputStream(), value));
    }

    @Test void writingUsesTemporaryErasedBuffersAndIoCausesAreNotExposed() throws Exception {
        var captured = new AtomicReference<byte[]>();
        var output = new OutputStream() {
            @Override public void write(int value) {}
            @Override public void write(byte[] value) { captured.set(value); }
        };
        try (var value = submission()) { AuthTransfer.write(output, value); }
        assertArrayEquals(new byte[fixture.length], captured.get());
        try (var value = submission()) {
            var broken = new OutputStream() { @Override public void write(int byteValue) throws IOException { throw new IOException("PUBLIC_SYNTHETIC"); } };
            var error = assertThrows(BridgeException.class, () -> AuthTransfer.write(broken, value));
            assertNull(error.getCause()); assertFalse(error.getMessage().contains("PUBLIC_SYNTHETIC"));
        }
        var broken = new InputStream() { @Override public int read() throws IOException { throw new IOException("PUBLIC_SYNTHETIC"); } };
        assertNull(assertThrows(BridgeException.class, () -> read(broken)).getCause());
    }
}
