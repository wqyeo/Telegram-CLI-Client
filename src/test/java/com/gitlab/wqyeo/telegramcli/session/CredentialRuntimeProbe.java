package com.gitlab.wqyeo.telegramcli.session;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Explicit synthetic shaded-JAR/jlink probe, not a production command or JUnit test. */
public final class CredentialRuntimeProbe {
    private CredentialRuntimeProbe() {}
    public static void main(String[] args) throws Exception {
        char[] passphrase = "SYNTHETIC-ONLY-runtime-unlock".toCharArray();
        byte[] hash = "00000000000000000000000000000000".getBytes(StandardCharsets.US_ASCII);
        byte[] original = null, decoded = null;
        try (var material = CredentialMaterial.generate(1, hash)) {
            byte[] envelope = CredentialEnvelope.seal("synthetic", passphrase, material);
            try (var unlocked = CredentialEnvelope.open("synthetic", passphrase, envelope)) {
                original = material.encode(); decoded = unlocked.encode();
                if (!Arrays.equals(original, decoded)) { throw new IllegalStateException("Synthetic runtime check failed."); }
            }
        } finally {
            Arrays.fill(passphrase, '\0'); Arrays.fill(hash, (byte) 0);
            if (original != null) { Arrays.fill(original, (byte) 0); }
            if (decoded != null) { Arrays.fill(decoded, (byte) 0); }
        }
        System.out.println("{\"schemaVersion\":1,\"mode\":\"synthetic-envelope-runtime-check\",\"status\":\"ok\",\"accountDatabaseCreated\":false}");
    }
}
