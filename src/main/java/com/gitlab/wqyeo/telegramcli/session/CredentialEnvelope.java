package com.gitlab.wqyeo.telegramcli.session;

import static com.gitlab.wqyeo.telegramcli.session.CredentialException.Code.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Fixed v1 Argon2id/AES-GCM format. Not a sandbox or a perfect-erasure guarantee. */
public final class CredentialEnvelope {
    private static final long MAGIC = 0x544743564c543031L; // TGCVLT01
    private static final int MEMORY_KIB = 65536, PASSES = 3, LANES = 1;
    private static final int HEADER_BYTES = 8 + 4 * 4 + 16 + 12;
    public static final int ENVELOPE_BYTES = HEADER_BYTES + CredentialMaterial.PAYLOAD_BYTES + 16;

    private CredentialEnvelope() {}

    public static byte[] seal(String profile, char[] passphrase, CredentialMaterial material) throws CredentialException {
        byte[] key = null, plaintext = null;
        try {
            validate(profile, passphrase);
            byte[] salt = new byte[16], nonce = new byte[12];
            var random = new SecureRandom(); random.nextBytes(salt); random.nextBytes(nonce);
            byte[] header = ByteBuffer.allocate(HEADER_BYTES).putLong(MAGIC).putInt(Argon2Parameters.ARGON2_VERSION_13)
                    .putInt(MEMORY_KIB).putInt(PASSES).putInt(LANES).put(salt).put(nonce).array();
            key = derive(passphrase, salt);
            plaintext = material.encode();
            var cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, header, profile);
            return ByteBuffer.allocate(ENVELOPE_BYTES).put(header).put(cipher.doFinal(plaintext)).array();
        } catch (CredentialException exception) { throw exception; }
        catch (GeneralSecurityException | CharacterCodingException | RuntimeException exception) { throw new CredentialException(INVALID_INPUT); }
        finally { erase(key); erase(plaintext); }
    }

    public static CredentialMaterial open(String profile, char[] passphrase, byte[] envelope) throws CredentialException {
        byte[] key = null, plaintext = null;
        try {
            validate(profile, passphrase);
            if (envelope == null || envelope.length != ENVELOPE_BYTES) { throw new CredentialException(ENVELOPE_INVALID); }
            var input = ByteBuffer.wrap(envelope);
            // Refuse untrusted cost parameters BEFORE invoking the KDF.
            if (input.getLong() != MAGIC || input.getInt() != Argon2Parameters.ARGON2_VERSION_13
                    || input.getInt() != MEMORY_KIB || input.getInt() != PASSES || input.getInt() != LANES) {
                throw new CredentialException(ENVELOPE_INVALID);
            }
            byte[] salt = new byte[16], nonce = new byte[12]; input.get(salt); input.get(nonce);
            byte[] header = Arrays.copyOf(envelope, HEADER_BYTES);
            key = derive(passphrase, salt);
            var cipher = cipher(Cipher.DECRYPT_MODE, key, nonce, header, profile);
            plaintext = cipher.doFinal(envelope, HEADER_BYTES, envelope.length - HEADER_BYTES);
            return CredentialMaterial.decode(plaintext);
        } catch (CredentialException exception) { throw exception; }
        catch (GeneralSecurityException | CharacterCodingException | RuntimeException exception) { throw new CredentialException(UNLOCK_FAILED); }
        finally { erase(key); erase(plaintext); }
    }

    private static void validate(String profile, char[] passphrase) throws CredentialException {
        if (profile == null || !profile.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
                || passphrase == null || passphrase.length < 12 || passphrase.length > 1024) {
            throw new CredentialException(INVALID_INPUT);
        }
    }

    private static byte[] derive(char[] passphrase, byte[] salt) throws CharacterCodingException {
        var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(passphrase));
        byte[] password = new byte[encoded.remaining()], output = new byte[32];
        Argon2Parameters parameters = null;
        boolean success = false;
        try {
            encoded.get(password);
            parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(MEMORY_KIB)
                    .withIterations(PASSES).withParallelism(LANES).withSalt(salt).build();
            var generator = new Argon2BytesGenerator(); generator.init(parameters);
            generator.generateBytes(password, output);
            success = true;
            return output;
        } finally {
            erase(password);
            if (encoded.hasArray()) { erase(encoded.array()); }
            if (parameters != null) { parameters.clear(); }
            if (!success) { erase(output); }
        }
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, byte[] header, String profile) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(header);
        cipher.updateAAD(profile.getBytes(StandardCharsets.US_ASCII));
        return cipher;
    }

    private static void erase(byte[] bytes) { if (bytes != null) { Arrays.fill(bytes, (byte) 0); } }
}
