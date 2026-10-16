package com.gitlab.wqyeo.telegramcli.session;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionProtocolTest {
    private final UUID generation = UUID.randomUUID();
    private String json() {
        return "{\"schemaVersion\":1,\"generation\":\"" + generation + "\",\"sequence\":1,\"signal\":\"STARTED\"}";
    }
    private byte[] packet(byte[] body) throws IOException {
        var output = new ByteArrayOutputStream();
        output.write(ByteBuffer.allocate(4).putInt(body.length).array()); output.write(body);
        return output.toByteArray();
    }
    private void invalid(byte[] bytes) {
        var exception = assertThrows(SessionException.class, () -> SessionProtocol.read(new ByteArrayInputStream(bytes)));
        assertEquals(SessionException.Code.PROTOCOL_INVALID, exception.code());
        assertNull(exception.getCause());
        assertFalse(exception.toString().contains("DO_NOT_PRINT"));
    }
    @Test void roundTripAndBoundaryEof() throws Exception {
        var frame = new SessionProtocol.Frame(1, generation, 1, SessionState.Signal.STARTED);
        var output = new ByteArrayOutputStream();
        SessionProtocol.write(output, frame); SessionProtocol.write(output, frame);
        var input = new ByteArrayInputStream(output.toByteArray());
        assertEquals(frame, SessionProtocol.read(input)); assertEquals(frame, SessionProtocol.read(input));
        assertNull(SessionProtocol.read(input));
    }
    @ParameterizedTest @ValueSource(ints = {-1, 0, 4097, Integer.MAX_VALUE})
    void refusesLengthBeforeReadingOrAllocatingBody(int size) {
        invalid(ByteBuffer.allocate(4).putInt(size).array());
    }
    @Test void rejectsTruncatedHeaderBodyAndInvalidUtf8() throws Exception {
        invalid(new byte[] {0}); invalid(new byte[] {0, 0, 0});
        byte[] body = packet(json().getBytes(StandardCharsets.UTF_8));
        invalid(java.util.Arrays.copyOf(body, body.length - 1));
        invalid(packet(new byte[] {(byte) 0xc0, (byte) 0xaf}));
    }
    @ParameterizedTest @ValueSource(strings = {
        "{\"schemaVersion\":1,\"schemaVersion\":1}", "null", "[]", "{}",
        "{\"DO_NOT_PRINT\":\"DO_NOT_PRINT\"}", "not JSON DO_NOT_PRINT"})
    void malformedInputIsRedacted(String json) throws Exception {
        invalid(packet(json.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void rejectsUnknownFieldsEnumsVersionsCoercionAndTrailingData() throws Exception {
        for (String bad : new String[] {
                json().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                json().replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                json().replace("\"sequence\":1", "\"sequence\":1.5"),
                json().replace("\"sequence\":1", "\"sequence\":0"),
                json().replace("\"signal\":\"STARTED\"", "\"signal\":\"DO_NOT_PRINT\""),
                json().replace("\"signal\":\"STARTED\"", "\"signal\":null"),
                json().replace("\"schemaVersion\":1,", ""),
                json().replace("}", ",\"DO_NOT_PRINT\":true}"), json() + " {}"}) {
            invalid(packet(bad.getBytes(StandardCharsets.UTF_8)));
        }
    }
    @Test void invalidOutgoingFramesAndIoFailuresAreRedacted() {
        assertThrows(IllegalArgumentException.class, () -> new SessionProtocol.Frame(1, generation, 0, SessionState.Signal.STARTED));
        assertThrows(SessionException.class, () -> SessionProtocol.write(OutputStream.nullOutputStream(), null));
        var output = new OutputStream() { public void write(int value) throws IOException { throw new IOException("DO_NOT_PRINT"); } };
        var exception = assertThrows(SessionException.class, () -> SessionProtocol.write(output,
                new SessionProtocol.Frame(1, generation, 1, SessionState.Signal.STARTED)));
        assertFalse(exception.toString().contains("DO_NOT_PRINT")); assertNull(exception.getCause());
    }
}
