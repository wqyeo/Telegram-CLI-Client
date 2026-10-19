package com.gitlab.wqyeo.telegramcli.session.bridge;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Transport-only PUBLIC fixture negotiation/peer-close, not old lifecycle or actual TDLib Close. */
public final class BridgePipeProtocol {
    public static final int BYTES = 34;
    public enum Kind { HELLO, CLOSED }
    public enum Namespace { PIPE, TYPED, AUTH }
    public record Control(Kind kind, UUID generation, long requestId) {
        public Control {
            if (kind == null || generation == null || (kind == Kind.HELLO ? requestId != 0 : requestId < 1)) {
                throw new IllegalArgumentException("Invalid pipe control.");
            }
        }
    }
    private static final byte[] MAGIC = "TGCPIP01".getBytes(StandardCharsets.US_ASCII);
    private BridgePipeProtocol() {}
    public static void write(OutputStream output, Control value) throws BridgeException {
        try {
            output.write(ByteBuffer.allocate(BYTES).put(MAGIC).put((byte) 1).put((byte) (value.kind().ordinal() + 1))
                    .putLong(value.generation().getMostSignificantBits()).putLong(value.generation().getLeastSignificantBits())
                    .putLong(value.requestId()).array()); output.flush();
        } catch (IOException | RuntimeException failure) { throw invalid(); }
    }
    public static Control read(InputStream input) throws BridgeException {
        try {
            byte[] raw = input.readNBytes(BYTES);
            if (raw.length != BYTES) { throw invalid(); }
            var buffer = ByteBuffer.wrap(raw); byte[] magic = new byte[8]; buffer.get(magic);
            int mode = Byte.toUnsignedInt(buffer.get()), kind = Byte.toUnsignedInt(buffer.get());
            if (!Arrays.equals(magic, MAGIC) || mode != 1 || kind < 1 || kind > 2) { throw invalid(); }
            return new Control(Kind.values()[kind - 1], new UUID(buffer.getLong(), buffer.getLong()), buffer.getLong());
        } catch (IOException | RuntimeException failure) { throw invalid(); }
    }
    /** Boundary EOF only; fixed lookahead, never guesses payload lengths or puts auth into JSON. */
    public static Namespace peek(PushbackInputStream input) throws BridgeException {
        try {
            byte[] magic = input.readNBytes(8);
            if (magic.length == 0) { return null; }
            if (magic.length != 8) { throw invalid(); }
            input.unread(magic);
            if (Arrays.equals(magic, MAGIC)) { return Namespace.PIPE; }
            if (Arrays.equals(magic, "TGCBRG01".getBytes(StandardCharsets.US_ASCII))
                    || Arrays.equals(magic, "TGCBRG02".getBytes(StandardCharsets.US_ASCII))) { return Namespace.TYPED; }
            if (Arrays.equals(magic, "TGCAUT01".getBytes(StandardCharsets.US_ASCII))) { return Namespace.AUTH; }
            throw invalid();
        } catch (IOException failure) { throw invalid(); }
    }
    private static BridgeException invalid() { return new BridgeException(BridgeException.Code.PROTOCOL_INVALID); }
}
