package com.gitlab.wqyeo.telegramcli.session;

/** No operational provider is supplied. Tests inject synthetic material only. */
@FunctionalInterface
public interface SecretProvider {
    enum Kind { API_HASH, DATABASE_KEY, AUTHORIZATION_INPUT }
    enum Failure { MISSING, CANCELLED, UNAVAILABLE }

    /** Caller owns and must close returned material. Never place it in state or IPC. */
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
