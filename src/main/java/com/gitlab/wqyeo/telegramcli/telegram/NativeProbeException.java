package com.gitlab.wqyeo.telegramcli.telegram;

/** Fixed diagnostic codes/messages only; never expose native loader or parser output. */
public final class NativeProbeException extends Exception {
    private final String code;
    public NativeProbeException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
