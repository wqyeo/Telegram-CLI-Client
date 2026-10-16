package com.gitlab.wqyeo.telegramcli.session;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

/** A disposable provisioning value, never application state or ordinary JSON. */
@JsonSerialize(using = CredentialMaterial.Redactor.class)
public final class CredentialMaterial implements AutoCloseable {
    static final int PAYLOAD_BYTES = 4 + 32 + 32;
    private final int apiId;
    private final SecretMaterial apiHash;
    private final SecretMaterial databaseKey;
    private boolean closed;

    public CredentialMaterial(int apiId, byte[] apiHash, byte[] databaseKey) {
        if (apiId < 1 || apiHash == null || apiHash.length != 32 || databaseKey == null || databaseKey.length != 32) {
            throw new IllegalArgumentException("Invalid credential material.");
        }
        for (byte value : apiHash) {
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                throw new IllegalArgumentException("Invalid credential material.");
            }
        }
        this.apiId = apiId;
        this.apiHash = new SecretMaterial(apiHash);
        this.databaseKey = new SecretMaterial(databaseKey);
    }

    /** Only explicit provisioning calls this; existing commands never do. */
    public static CredentialMaterial generate(int apiId, byte[] apiHash) {
        byte[] key = new byte[32];
        try { new SecureRandom().nextBytes(key); return new CredentialMaterial(apiId, apiHash, key); }
        finally { Arrays.fill(key, (byte) 0); }
    }

    public synchronized SecretMaterial copy(SecretProvider.Kind kind) throws SecretProvider.Unavailable {
        if (closed) { throw new SecretProvider.Unavailable(SecretProvider.Failure.UNAVAILABLE); }
        return switch (kind) {
            case API_HASH -> apiHash.use(SecretMaterial::new);
            case DATABASE_KEY -> databaseKey.use(SecretMaterial::new);
            case AUTHORIZATION_INPUT -> throw new SecretProvider.Unavailable(SecretProvider.Failure.MISSING);
        };
    }

    synchronized byte[] encode() {
        if (closed) { throw new IllegalStateException("Credential material is closed."); }
        var buffer = ByteBuffer.allocate(PAYLOAD_BYTES).putInt(apiId);
        apiHash.use(bytes -> { buffer.put(bytes); return null; });
        databaseKey.use(bytes -> { buffer.put(bytes); return null; });
        return buffer.array();
    }

    static CredentialMaterial decode(byte[] bytes) {
        if (bytes.length != PAYLOAD_BYTES) { throw new IllegalArgumentException("Invalid credential material."); }
        var buffer = ByteBuffer.wrap(bytes);
        int apiId = buffer.getInt();
        byte[] hash = new byte[32], key = new byte[32];
        try {
            buffer.get(hash); buffer.get(key);
            return new CredentialMaterial(apiId, hash, key);
        } finally { Arrays.fill(hash, (byte) 0); Arrays.fill(key, (byte) 0); }
    }

    @Override public synchronized void close() { apiHash.close(); databaseKey.close(); closed = true; }
    @Override public String toString() { return "[REDACTED]"; }

    public static final class Redactor extends JsonSerializer<CredentialMaterial> {
        @Override public void serialize(CredentialMaterial value, JsonGenerator output, SerializerProvider provider) throws IOException {
            output.writeString("[REDACTED]");
        }
    }
}
