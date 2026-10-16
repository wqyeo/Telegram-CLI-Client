package com.gitlab.wqyeo.telegramcli.session;

import static org.junit.jupiter.api.Assertions.*;
import static com.gitlab.wqyeo.telegramcli.session.CredentialException.Code.*;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

@Timeout(60)
class CredentialEnvelopeTest {
    // Public synthetic fixtures, not Telegram credentials or a usable account.
    static final byte[] HASH = "deadbeefdeadbeefdeadbeefdeadbeef".getBytes(StandardCharsets.US_ASCII);
    static char[] password() { return "SYNTHETIC-unlock-not-a-real-password".toCharArray(); }
    static CredentialMaterial material() { return new CredentialMaterial(12345, HASH, filled(32, 7)); }
    static byte[] filled(int length, int value) { var result = new byte[length]; Arrays.fill(result, (byte) value); return result; }

    @Test void fixedFormatFreshSaltNonceAndAuthenticatedRoundTrip() throws Exception {
        try (var original = material()) {
            byte[] first = CredentialEnvelope.seal("work", password(), original);
            byte[] second = CredentialEnvelope.seal("work", password(), original);
            assertEquals(136, first.length); assertFalse(Arrays.equals(first, second));
            var header = ByteBuffer.wrap(first);
            assertEquals(0x544743564c543031L, header.getLong());
            assertEquals(0x13, header.getInt()); assertEquals(65536, header.getInt());
            assertEquals(3, header.getInt()); assertEquals(1, header.getInt());
            assertFalse(Arrays.equals(Arrays.copyOfRange(first, 24, 40), Arrays.copyOfRange(second, 24, 40)));
            assertFalse(Arrays.equals(Arrays.copyOfRange(first, 40, 52), Arrays.copyOfRange(second, 40, 52)));
            assertFalse(new String(first, StandardCharsets.ISO_8859_1).contains(new String(HASH, StandardCharsets.US_ASCII)));
            try (var unlocked = CredentialEnvelope.open("work", password(), first)) { assertArrayEquals(original.encode(), unlocked.encode()); }
        }
    }
    @Test void wrongPasswordProfileAndCorruptionNeverYieldMaterialOrRawCause() throws Exception {
        try (var original = material()) {
            byte[] encoded = CredentialEnvelope.seal("work", password(), original);
            failure(UNLOCK_FAILED, () -> CredentialEnvelope.open("work", "WRONG_SYNTHETIC_DO_NOT_PRINT".toCharArray(), encoded));
            failure(UNLOCK_FAILED, () -> CredentialEnvelope.open("other", password(), encoded));
            for (int index : new int[] {0, 8, 12, 16, 20, 24, 40, 52, encoded.length - 1}) {
                byte[] damaged = encoded.clone(); damaged[index] ^= 1;
                failure(index < 24 ? ENVELOPE_INVALID : UNLOCK_FAILED, () -> CredentialEnvelope.open("work", password(), damaged));
            }
        }
    }
    @Test void malformedLengthsAndCostsAreRefusedWithoutExpensiveWork() throws Exception {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            for (int length : new int[] {0, 1, 135, 137, 65536}) {
                failure(ENVELOPE_INVALID, () -> CredentialEnvelope.open("work", password(), new byte[length]));
            }
            for (int offset : new int[] {8, 12, 16, 20}) {
                byte[] bad = new byte[136]; ByteBuffer.wrap(bad).putLong(0x544743564c543031L).putInt(19).putInt(65536).putInt(3).putInt(1);
                ByteBuffer.wrap(bad).putInt(offset, Integer.MAX_VALUE);
                failure(ENVELOPE_INVALID, () -> CredentialEnvelope.open("work", password(), bad));
            }
        });
    }
    @Test void unicodeIsStrictAndCallerOwnsPassphraseDisposal() throws Exception {
        try (var original = material()) {
            char[] passphrase = "Synthetic-密碼-🙂-DO_NOT_PRINT".toCharArray(), before = passphrase.clone();
            byte[] encrypted = CredentialEnvelope.seal("work", passphrase, original);
            assertArrayEquals(before, passphrase);
            try (var unlocked = CredentialEnvelope.open("work", passphrase, encrypted)) { assertArrayEquals(original.encode(), unlocked.encode()); }
            Arrays.fill(passphrase, '\0');
            for (char[] invalid : new char[][] {null, new char[0], new char[11], new char[1025], "SYNTHETIC-bad-\uD800".toCharArray()}) {
                failure(INVALID_INPUT, () -> CredentialEnvelope.seal("work", invalid, original));
            }
            failure(INVALID_INPUT, () -> CredentialEnvelope.seal("../DO_NOT_PRINT", password(), original));
        }
    }
    @Test void redactionDisposalAndIndependentlyGeneratedKeys() throws Exception {
        try (var first = CredentialMaterial.generate(12345, HASH); var second = CredentialMaterial.generate(12345, HASH)) {
            assertNotEquals(Arrays.toString(first.encode()), Arrays.toString(second.encode()));
            assertEquals("[REDACTED]", first.toString());
            var mapper = new ObjectMapper().setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
            assertEquals("\"[REDACTED]\"", mapper.writeValueAsString(first));
            try (var hash = first.copy(SecretProvider.Kind.API_HASH)) { assertArrayEquals(HASH, hash.use(byte[]::clone)); }
            assertEquals(SecretProvider.Failure.MISSING, assertThrows(SecretProvider.Unavailable.class,
                    () -> first.copy(SecretProvider.Kind.AUTHORIZATION_INPUT)).failure());
            first.close(); assertThrows(IllegalStateException.class, first::encode);
            assertEquals(SecretProvider.Failure.UNAVAILABLE, assertThrows(SecretProvider.Unavailable.class,
                    () -> first.copy(SecretProvider.Kind.DATABASE_KEY)).failure());
        }
        assertThrows(IllegalArgumentException.class, () -> new CredentialMaterial(0, HASH, new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> new CredentialMaterial(1, new byte[32], new byte[32]));
    }
    @Test void argon2idMatchesPublicRfc9106Section53Vector() {
        // Independent public RFC reference, not a wrapper round-trip or real credential.
        var parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(19)
                .withMemoryAsKB(32).withIterations(3).withParallelism(4)
                .withSalt(filled(16, 2)).withSecret(filled(8, 3)).withAdditional(filled(12, 4)).build();
        var generator = new Argon2BytesGenerator(); generator.init(parameters);
        byte[] actual = new byte[32]; generator.generateBytes(filled(32, 1), actual); parameters.clear();
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", HexFormat.of().formatHex(actual));
    }
    private static void failure(CredentialException.Code code, org.junit.jupiter.api.function.Executable operation) {
        var error = assertThrows(CredentialException.class, operation);
        assertEquals(code, error.code()); assertNull(error.getCause()); assertFalse(error.toString().contains("DO_NOT_PRINT"));
    }
}
