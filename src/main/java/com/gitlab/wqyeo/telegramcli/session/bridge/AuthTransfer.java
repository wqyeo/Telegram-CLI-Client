package com.gitlab.wqyeo.telegramcli.session.bridge;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Separate private binary namespace. Not credentials-vault bootstrap or BridgeProtocol JSON. */
public final class AuthTransfer {
    public static final int HEADER_BYTES = 53;
    private static final byte[] MAGIC = "TGCAUT01".getBytes(StandardCharsets.US_ASCII);
    private AuthTransfer() {}

    public static void write(OutputStream output, AuthSubmission submission) throws BridgeException {
        try {
            if (submission == null) { throw BridgeData.invalid(); }
            submission.use(value -> {
                byte[] header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(value.length)
                        .putLong(submission.generation().getMostSignificantBits()).putLong(submission.generation().getLeastSignificantBits())
                        .putLong(submission.requestId()).putLong(submission.token().getMostSignificantBits()).putLong(submission.token().getLeastSignificantBits())
                        .put((byte) code(submission.step())).array();
                try { output.write(header); output.write(value); output.flush(); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
                return null;
            });
        } catch (IOException | RuntimeException failure) { throw new BridgeException(BridgeException.Code.PROTOCOL_INVALID); }
    }

    /** Validate expected generation/request/step before accepting bytes. Caller must consume the token once. */
    public static AuthSubmission read(InputStream input, UUID generation, long request, UUID token, BridgeData.Step step)
            throws BridgeException {
        byte[] value = null;
        try {
            if (generation == null || request < 1 || token == null || step == null || !step.input()) { throw BridgeData.invalid(); }
            int first = input.read(); if (first == -1) { return null; }
            byte[] rest = input.readNBytes(HEADER_BYTES - 1);
            if (rest.length != HEADER_BYTES - 1) { throw BridgeData.invalid(); }
            byte[] header = new byte[HEADER_BYTES]; header[0] = (byte) first;
            System.arraycopy(rest, 0, header, 1, rest.length);
            var bytes = ByteBuffer.wrap(header); byte[] magic = new byte[MAGIC.length]; bytes.get(magic);
            int length = bytes.getInt(); UUID actualGeneration = new UUID(bytes.getLong(), bytes.getLong());
            long actualRequest = bytes.getLong(); UUID actualToken = new UUID(bytes.getLong(), bytes.getLong());
            int actualStep = Byte.toUnsignedInt(bytes.get());
            if (!Arrays.equals(MAGIC, magic) || length < 1 || length > AuthSubmission.MAX_BYTES
                    || !generation.equals(actualGeneration) || request != actualRequest || !token.equals(actualToken)
                    || code(step) != actualStep) { throw BridgeData.invalid(); }
            value = input.readNBytes(length);
            if (value.length != length) { throw BridgeData.invalid(); }
            return AuthSubmission.copyOf(generation, request, token, step, value);
        } catch (IOException | RuntimeException failure) { throw new BridgeException(BridgeException.Code.PROTOCOL_INVALID); }
        finally { if (value != null) { Arrays.fill(value, (byte) 0); } }
    }
    private static int code(BridgeData.Step step) {
        return switch (step) {
            case PHONE -> 1; case CODE -> 2; case EMAIL -> 3; case EMAIL_CODE -> 4; case PASSWORD -> 5;
            default -> throw BridgeData.invalid();
        };
    }
}
