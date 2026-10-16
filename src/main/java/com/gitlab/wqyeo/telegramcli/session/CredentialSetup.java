package com.gitlab.wqyeo.telegramcli.session;

import java.io.IOError;
import java.nio.file.Path;
import java.util.Arrays;

/** Explicit local provisioning/check only. Never returns unlocked values or starts a worker/client. */
public final class CredentialSetup {
    private final Path configWorkspace, dataWorkspace;
    private final String profile;
    public CredentialSetup(Path configWorkspace, Path dataWorkspace, String profile) {
        this.configWorkspace = configWorkspace; this.dataWorkspace = dataWorkspace; this.profile = profile;
    }
    public void create(CredentialInput.Factory factory) throws CredentialException, SetupException { run(factory, true); }
    public void check(CredentialInput.Factory factory) throws CredentialException, SetupException { run(factory, false); }

    private void run(CredentialInput.Factory factory, boolean creating) throws CredentialException, SetupException {
        char[] id = null, hash = null, passphrase = null, confirmation = null;
        byte[] encodedHash = null;
        try {
            var store = new CredentialStore(configWorkspace, dataWorkspace);
            store.preflight(profile, creating); // BEFORE selecting or opening any input implementation.
            if (factory == null) { throw new SetupException(SetupException.Code.INPUT_UNAVAILABLE); }
            try (var input = factory.open()) {
                if (input == null) { throw new SetupException(SetupException.Code.INPUT_UNAVAILABLE); }
                if (creating) {
                    id = read(input, CredentialInput.Prompt.API_ID); int apiId = parseId(id); wipe(id);
                    hash = read(input, CredentialInput.Prompt.API_HASH); encodedHash = new byte[32];
                    for (int i = 0; i < hash.length; i++) {
                        char c = hash[i];
                        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) { throw new SetupException(SetupException.Code.INVALID_INPUT); }
                        encodedHash[i] = (byte) c;
                    }
                    wipe(hash);
                    passphrase = read(input, CredentialInput.Prompt.PASSPHRASE);
                    confirmation = read(input, CredentialInput.Prompt.CONFIRM);
                    if (!Arrays.equals(passphrase, confirmation)) { throw new SetupException(SetupException.Code.INVALID_INPUT); }
                    wipe(confirmation);
                    try (var material = CredentialMaterial.generate(apiId, encodedHash)) { store.create(profile, passphrase, material); }
                } else {
                    passphrase = read(input, CredentialInput.Prompt.PASSPHRASE);
                    // Recheck/hold the existing profile after human input; never open an account DB.
                    try (var lease = ProfileStore.acquire(dataWorkspace, profile);
                         var material = store.open(profile, passphrase)) { /* Dispose immediately, no unlocked cache. */ }
                    catch (SessionException failure) { throw new CredentialException(CredentialException.Code.STORAGE_INVALID); }
                }
            }
        } catch (CredentialException failure) { throw new CredentialException(failure.code()); }
        catch (SetupException failure) { throw new SetupException(failure.code()); }
        catch (RuntimeException | IOError failure) { throw new SetupException(SetupException.Code.INPUT_UNAVAILABLE); }
        finally {
            wipe(id); wipe(hash); wipe(passphrase); wipe(confirmation);
            if (encodedHash != null) { Arrays.fill(encodedHash, (byte) 0); }
        }
        // Fresh exceptions above intentionally discard provider causes AND suppressed close errors.
        // A failed create can retain a private partial/complete vault; never automatically delete it.
    }
    private static char[] read(CredentialInput input, CredentialInput.Prompt prompt) throws SetupException {
        char[] value = input.read(prompt);
        if (value == null) { throw new SetupException(SetupException.Code.CANCELLED); }
        if (value.length < prompt.minimum() || value.length > prompt.maximum()) {
            wipe(value); throw new SetupException(SetupException.Code.INVALID_INPUT);
        }
        return value;
    }
    private static int parseId(char[] value) throws SetupException {
        int result = 0;
        try {
            for (char c : value) {
                if (c < '0' || c > '9') { throw new SetupException(SetupException.Code.INVALID_INPUT); }
                result = Math.addExact(Math.multiplyExact(result, 10), c - '0');
            }
        } catch (ArithmeticException failure) { throw new SetupException(SetupException.Code.INVALID_INPUT); }
        if (result == 0) { throw new SetupException(SetupException.Code.INVALID_INPUT); }
        return result;
    }
    private static void wipe(char[] value) { if (value != null) { Arrays.fill(value, '\0'); } }
    @Override public String toString() { return "[REDACTED local setup]"; }
}
