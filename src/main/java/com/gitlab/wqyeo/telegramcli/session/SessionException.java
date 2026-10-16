package com.gitlab.wqyeo.telegramcli.session;

import java.io.IOException;

/** Deliberately carries no raw input, paths, parser exceptions or worker output. */
public final class SessionException extends IOException {
    public enum Code { PROFILE_INVALID, PROFILE_BUSY, STORAGE_UNSUPPORTED, PROTOCOL_INVALID,
        START_FAILED, WORKER_FAILED, CLOSE_FAILED, INTERRUPTED }

    private final Code code;

    public SessionException(Code code) {
        super("Session operation failed: " + code.name() + ".");
        this.code = code;
    }

    public Code code() { return code; }
}
