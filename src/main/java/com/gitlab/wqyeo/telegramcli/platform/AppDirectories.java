package com.gitlab.wqyeo.telegramcli.platform;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Resolves paths only. Does not create directories or claim to secure sessions. */
public record AppDirectories(Path config, Path data) {
    private static final String APP = "telegram-cli";

    public static AppDirectories current() {
        return resolve(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System.getenv());
    }

    public static AppDirectories resolve(String osName, Path home, Map<String, String> environment) {
        if (osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
            return new AppDirectories(
                    absoluteOrDefault(environment.get("APPDATA"), home.resolve("AppData/Roaming")).resolve(APP),
                    absoluteOrDefault(environment.get("LOCALAPPDATA"), home.resolve("AppData/Local")).resolve(APP));
        }
        if (osName.equalsIgnoreCase("linux")) {
            return new AppDirectories(
                    absoluteOrDefault(environment.get("XDG_CONFIG_HOME"), home.resolve(".config")).resolve(APP),
                    absoluteOrDefault(environment.get("XDG_DATA_HOME"), home.resolve(".local/share")).resolve(APP));
        }
        throw new UnsupportedOperationException("Only Windows and Linux are currently targeted.");
    }

    private static Path absoluteOrDefault(String value, Path fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        var path = Path.of(value);
        return path.isAbsolute() ? path : fallback;
    }
}
