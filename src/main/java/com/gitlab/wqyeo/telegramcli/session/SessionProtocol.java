package com.gitlab.wqyeo.telegramcli.session;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.UUID;

/** Small foundation protocol: no arbitrary text, secrets, messages or TDLib objects. */
public final class SessionProtocol {
    public static final int MAX_BYTES = 4096;
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public record Frame(int schemaVersion, UUID generation, long sequence, SessionState.Signal signal) {
        public Frame {
            if (schemaVersion != 1 || generation == null || sequence < 1 || signal == null) {
                throw new IllegalArgumentException("Invalid session frame.");
            }
        }
    }

    private SessionProtocol() {}

    /** Null means boundary EOF, not a clean engine shutdown. */
    public static Frame read(InputStream input) throws SessionException {
        try {
            int first = input.read();
            if (first == -1) { return null; }
            byte[] rest = input.readNBytes(3);
            if (rest.length != 3) { throw new IOException(); }
            int size = ByteBuffer.wrap(new byte[] {(byte) first, rest[0], rest[1], rest[2]}).getInt();
            if (size < 1 || size > MAX_BYTES) { throw new IOException(); }
            byte[] bytes = input.readNBytes(size);
            if (bytes.length != size) { throw new IOException(); }
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Frame frame = MAPPER.readValue(json, Frame.class);
            if (frame == null) { throw new IOException(); } // JSON null is not boundary EOF.
            return frame;
        } catch (IOException | RuntimeException exception) {
            throw new SessionException(SessionException.Code.PROTOCOL_INVALID);
        }
    }

    public static void write(OutputStream output, Frame frame) throws SessionException {
        try {
            if (frame == null) { throw new IOException(); }
            byte[] bytes = MAPPER.writeValueAsBytes(frame);
            if (bytes.length > MAX_BYTES) { throw new IOException(); }
            output.write(ByteBuffer.allocate(4).putInt(bytes.length).array());
            output.write(bytes);
            output.flush();
        } catch (IOException | RuntimeException exception) {
            throw new SessionException(SessionException.Code.PROTOCOL_INVALID);
        }
    }
}
