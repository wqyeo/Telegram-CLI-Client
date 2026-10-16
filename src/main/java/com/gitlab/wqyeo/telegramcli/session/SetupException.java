package com.gitlab.wqyeo.telegramcli.session;

import java.io.IOException;

/** Fixed public codes only. Never attach provider causes or input snippets. */
public final class SetupException extends IOException {
    public enum Code { CONSOLE_REQUIRED, CANCELLED, INVALID_INPUT, INPUT_UNAVAILABLE }
    private final Code code;
    public SetupException(Code code) { super("Local setup failed: " + code.name() + "."); this.code = code; }
    public Code code() { return code; }
}
