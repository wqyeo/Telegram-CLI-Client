package com.gitlab.wqyeo.telegramcli.session.bridge;

import com.fasterxml.jackson.annotation.JsonValue;
import com.gitlab.wqyeo.telegramcli.session.SecretMaterial;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.Function;

/** Scoped private input, never part of BridgeData.Payload or ordinary JSON/state. */
public final class AuthSubmission implements AutoCloseable {
    public static final int MAX_BYTES = 4096;
    private final UUID generation;
    private final long requestId;
    private final UUID token;
    private final BridgeData.Step step;
    private final SecretMaterial secret;

    private AuthSubmission(UUID generation, long requestId, UUID token, BridgeData.Step step, byte[] value)
            throws CharacterCodingException {
        if (generation == null || requestId < 1 || token == null || step == null || !step.input()
                || value == null || value.length < 1 || value.length > MAX_BYTES) { throw BridgeData.invalid(); }
        // Validate without an immutable password String. Wipe even partially decoded characters.
        var chars = CharBuffer.allocate(MAX_BYTES);
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            var result = decoder.decode(ByteBuffer.wrap(value), chars, true);
            if (result.isOverflow()) { throw BridgeData.invalid(); }
            if (result.isError()) { result.throwException(); }
            var flushed = decoder.flush(chars);
            if (!flushed.isUnderflow()) { flushed.throwException(); }
        } finally { Arrays.fill(chars.array(), '\0'); }
        this.generation = generation; this.requestId = requestId; this.token = token; this.step = step;
        secret = new SecretMaterial(value);
    }
    public static AuthSubmission copyOf(UUID generation, long requestId, UUID token, BridgeData.Step step, byte[] value)
            throws BridgeException {
        try { return new AuthSubmission(generation, requestId, token, step, value); }
        catch (CharacterCodingException | RuntimeException failure) { throw new BridgeException(BridgeException.Code.PROTOCOL_INVALID); }
    }
    public UUID generation() { return generation; }
    public long requestId() { return requestId; }
    public UUID token() { return token; }
    public BridgeData.Step step() { return step; }
    public <T> T use(Function<byte[], T> action) throws BridgeException {
        try { return secret.use(action); }
        catch (RuntimeException failure) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
    }
    @Override public void close() { secret.close(); }
    @JsonValue public String redacted() { return "[REDACTED auth submission]"; }
    @Override public String toString() { return redacted(); }
}
