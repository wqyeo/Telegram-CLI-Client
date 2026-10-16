package com.gitlab.wqyeo.telegramcli.session;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.Function;

/** Best-effort lifetime control, not protection from privileged/same-user inspection. */
@JsonSerialize(using = SecretMaterial.RedactedSerializer.class)
public final class SecretMaterial implements AutoCloseable {
    private final byte[] bytes;
    private boolean closed;

    public SecretMaterial(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 65_536) {
            throw new IllegalArgumentException("Secret material has an invalid length.");
        }
        this.bytes = bytes.clone();
    }

    /** The callback must not retain/copy the temporary array; it is erased on return. */
    public synchronized <T> T use(Function<byte[], T> action) {
        if (closed) { throw new IllegalStateException("Secret material is closed."); }
        byte[] copy = bytes.clone();
        try { return action.apply(copy); }
        catch (RuntimeException exception) { throw new IllegalStateException("Secret operation failed."); }
        finally { Arrays.fill(copy, (byte) 0); }
    }

    @Override public synchronized void close() { Arrays.fill(bytes, (byte) 0); closed = true; }
    @Override public String toString() { return "[REDACTED]"; }

    public static final class RedactedSerializer extends JsonSerializer<SecretMaterial> {
        @Override public void serialize(SecretMaterial value, JsonGenerator output, SerializerProvider provider) throws IOException {
            output.writeString("[REDACTED]");
        }
    }
}
