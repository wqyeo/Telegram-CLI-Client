package com.gitlab.wqyeo.telegramcli.session;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SecretMaterialTest {
    private final byte[] marker = "SYNTHETIC_DO_NOT_PRINT".getBytes(StandardCharsets.UTF_8);

    @Test void inputIsCopiedTemporaryCopiesAreErasedAndCloseRefusesFurtherAccess() {
        byte[] input = marker.clone();
        var material = new SecretMaterial(input);
        input[0] = 0;
        byte[] temporary = material.use(bytes -> { assertArrayEquals(marker, bytes); return bytes; });
        assertArrayEquals(new byte[marker.length], temporary);
        material.close(); material.close();
        assertThrows(IllegalStateException.class, () -> material.use(bytes -> bytes.length));
        assertEquals("[REDACTED]", material.toString());
    }
    @Test void callbackFailureStillErasesItsCopy() {
        try (var material = new SecretMaterial(marker)) {
            var observed = new AtomicReference<byte[]>();
            var exception = assertThrows(IllegalStateException.class, () -> material.use(bytes -> {
                observed.set(bytes); throw new IllegalStateException("SYNTHETIC_DO_NOT_PRINT");
            }));
            assertArrayEquals(new byte[marker.length], observed.get());
            assertFalse(exception.toString().contains("SYNTHETIC")); assertNull(exception.getCause());
        }
    }
    @Test void normalAndFieldVisibleJsonAreAlwaysRedacted() throws Exception {
        try (var material = new SecretMaterial(marker)) {
            var mapper = JsonMapper.builder().build();
            assertEquals("\"[REDACTED]\"", mapper.writeValueAsString(material));
            mapper.setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
                    com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY);
            assertFalse(mapper.writeValueAsString(java.util.Map.of("secret", material)).contains("SYNTHETIC"));
        }
    }
    @Test void invalidLengthsAreRefusedWithoutRevealingInput() {
        assertThrows(IllegalArgumentException.class, () -> new SecretMaterial(null));
        assertThrows(IllegalArgumentException.class, () -> new SecretMaterial(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new SecretMaterial(new byte[65_537]));
    }
    @Test void fakeProvidersExposeExplicitMissingCancelledAndUnavailableOutcomes() {
        for (var reason : SecretProvider.Failure.values()) {
            SecretProvider provider = kind -> { throw new SecretProvider.Unavailable(reason); };
            var exception = assertThrows(SecretProvider.Unavailable.class, () -> provider.acquire(SecretProvider.Kind.DATABASE_KEY));
            assertEquals(reason, exception.failure()); assertNull(exception.getCause());
        }
        SecretProvider fake = kind -> new SecretMaterial(marker);
        assertDoesNotThrow(() -> { try (var secret = fake.acquire(SecretProvider.Kind.API_HASH)) {
            assertEquals(marker.length, secret.use(bytes -> Integer.valueOf(bytes.length)).intValue());
        } });
    }
}
