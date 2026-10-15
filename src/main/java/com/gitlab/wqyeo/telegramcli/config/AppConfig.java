package com.gitlab.wqyeo.telegramcli.config;

/** Non-secret settings only. Session credentials do not belong in this record. */
public record AppConfig(String profile, int historyLimit) {
    public AppConfig {
        if (profile == null || !profile.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid profile name.");
        }
        if (historyLimit < 1 || historyLimit > 1000) {
            throw new IllegalArgumentException("History limit must be between 1 and 1000.");
        }
    }
}
