package com.gitlab.wqyeo.telegramcli.session;

/** Explicit providers only; no startup selection or console/keyring reads. Tests use synthetic material. */
@FunctionalInterface
public interface SecretProvider {
    enum Kind { API_HASH, DATABASE_KEY, AUTHORIZATION_INPUT }
    enum Failure { MISSING, CANCELLED, UNAVAILABLE }

    /**
     * Caller owns and must close returned material. Never put secrets in state,
     * ordinary JSON or diagnostics. Only the narrowly typed private child-stdin
     * bootstrap transport may carry them; that pipe is not encryption or a sandbox.
     */
    SecretMaterial acquire(Kind kind) throws Unavailable;

    final class Unavailable extends Exception {
        private final Failure failure;
        public Unavailable(Failure failure) {
            super("Secret provisioning failed: " + failure.name() + ".");
            this.failure = failure;
        }
        public Failure failure() { return failure; }
    }
}
