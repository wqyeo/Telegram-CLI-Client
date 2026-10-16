package com.gitlab.wqyeo.telegramcli.session.bridge;

import java.io.IOException;

/** Never carries private data, parser/native causes, paths or server prose. */
public final class BridgeException extends IOException {
    public enum Code { PROTOCOL_INVALID, STATE_INVALID, OVERFLOW, CANCELLED, TIMEOUT }
    private final Code code;
    public BridgeException(Code code) {
        super("Bridge operation failed: " + code.name() + ".");
        this.code = code;
    }
    public Code code() { return code; }
}
