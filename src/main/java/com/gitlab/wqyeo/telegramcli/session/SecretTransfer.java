package com.gitlab.wqyeo.telegramcli.session;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

/** Private stdin bootstrap namespace, v1/request 1 only. NEVER stdout/event JSON. */
final class SecretTransfer {
    private static final long MAGIC = 0x5447435343525431L; // TGCSCRT1
    private static final int SIZE = 8 + 4 + 16 + 8 + CredentialMaterial.PAYLOAD_BYTES;
    private SecretTransfer() {}

    static void write(OutputStream output, UUID generation, CredentialMaterial material) throws SessionException {
        byte[] payload = null, frame = null;
        try {
            payload = material.encode();
            frame = ByteBuffer.allocate(4 + SIZE).putInt(SIZE).putLong(MAGIC).putInt(1)
                    .putLong(generation.getMostSignificantBits()).putLong(generation.getLeastSignificantBits())
                    .putLong(1).put(payload).array();
            output.write(frame); output.flush();
        } catch (IOException | RuntimeException exception) { throw invalid(); }
        finally { erase(payload); erase(frame); }
    }

    static CredentialMaterial read(InputStream input, UUID expectedGeneration) throws SessionException {
        byte[] frame = null, payload = new byte[CredentialMaterial.PAYLOAD_BYTES];
        try {
            if (new DataInputStream(input).readInt() != SIZE) { throw invalid(); }
            frame = input.readNBytes(SIZE);
            if (frame.length != SIZE) { throw invalid(); }
            var buffer = ByteBuffer.wrap(frame);
            if (buffer.getLong() != MAGIC || buffer.getInt() != 1) { throw invalid(); }
            var generation = new UUID(buffer.getLong(), buffer.getLong());
            if (!generation.equals(expectedGeneration) || buffer.getLong() != 1) { throw invalid(); }
            buffer.get(payload);
            return CredentialMaterial.decode(payload);
        } catch (IOException | RuntimeException exception) { throw invalid(); }
        finally { erase(frame); erase(payload); }
    }
    private static SessionException invalid() { return new SessionException(SessionException.Code.PROTOCOL_INVALID); }
    private static void erase(byte[] bytes) { if (bytes != null) { Arrays.fill(bytes, (byte) 0); } }
}
