package com.gitlab.wqyeo.telegramcli.session;

import java.io.IOException;

/** No paths, secret values, provider exceptions or authentication-tag details. */
public final class CredentialException extends IOException {
    public enum Code { INVALID_INPUT, ENVELOPE_INVALID, UNLOCK_FAILED, STORAGE_INVALID, ALREADY_EXISTS }
    private final Code code;
    public CredentialException(Code code) {
        super("Credential operation failed: " + code.name() + "."); this.code = code;
    }
    public Code code() { return code; }
}
