package com.gitlab.wqyeo.telegramcli.session;

/** Explicit input owner; no automatic selection. Returned arrays transfer ownership to the caller. */
public interface CredentialInput extends AutoCloseable {
    enum Prompt {
        API_ID("API ID (hidden, positive int32): ", 1, 10),
        API_HASH("API hash (hidden, 32 lowercase hex characters): ", 32, 32),
        PASSPHRASE("Vault passphrase (hidden, 12..1024 UTF-16 units): ", 12, 1024),
        CONFIRM("Confirm vault passphrase (hidden): ", 12, 1024);
        private final String label;
        private final int minimum, maximum;
        Prompt(String label, int minimum, int maximum) { this.label = label; this.minimum = minimum; this.maximum = maximum; }
        public String label() { return label; }
        public int minimum() { return minimum; }
        public int maximum() { return maximum; }
    }
    @FunctionalInterface interface Factory { CredentialInput open() throws SetupException; }
    /** Null means EOF/cancel. Implementations must not log or include values in errors. */
    char[] read(Prompt prompt) throws SetupException;
    @Override default void close() throws SetupException { /* Never close global System.in/out. */ }
}
