package com.gitlab.wqyeo.telegramcli.session;

/** Explicit ownership of an unlocked envelope; never selected/loaded implicitly. */
public final class UnlockedCredentialProvider implements SecretProvider, AutoCloseable {
    private final CredentialMaterial material;
    private boolean closed;
    private UnlockedCredentialProvider(CredentialMaterial material) { this.material = material; }

    public static UnlockedCredentialProvider open(CredentialStore store, String profile, char[] passphrase) throws CredentialException {
        return new UnlockedCredentialProvider(store.open(profile, passphrase));
    }

    @Override public synchronized SecretMaterial acquire(Kind kind) throws Unavailable {
        if (closed) { throw new Unavailable(Failure.UNAVAILABLE); }
        return material.copy(kind);
    }

    @Override public synchronized void close() { material.close(); closed = true; }
    @Override public String toString() { return "[REDACTED]"; }
}
