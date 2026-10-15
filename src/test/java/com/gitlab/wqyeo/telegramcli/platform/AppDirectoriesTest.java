package com.gitlab.wqyeo.telegramcli.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppDirectoriesTest {
    @TempDir Path temporary;

    @Test void linuxDefaultsAreXdgStyleWithoutCreatingFiles() {
        var paths = AppDirectories.resolve("Linux", temporary, Map.of());
        assertEquals(temporary.resolve(".config/telegram-cli"), paths.config());
        assertEquals(temporary.resolve(".local/share/telegram-cli"), paths.data());
        assertFalse(Files.exists(paths.config()));
        assertFalse(Files.exists(paths.data()));
    }

    @Test void linuxHonorsAbsoluteXdgOverridesAndIgnoresRelativeOnes() {
        var paths = AppDirectories.resolve("Linux", temporary,
                Map.of("XDG_CONFIG_HOME", temporary.resolve("settings").toString(), "XDG_DATA_HOME", "relative"));
        assertEquals(temporary.resolve("settings/telegram-cli"), paths.config());
        assertEquals(temporary.resolve(".local/share/telegram-cli"), paths.data());
    }

    @Test void windowsUsesRoamingConfigAndLocalData() {
        var paths = AppDirectories.resolve("Windows 11", temporary,
                Map.of("APPDATA", temporary.resolve("roaming").toString(),
                        "LOCALAPPDATA", temporary.resolve("local").toString()));
        assertEquals(temporary.resolve("roaming/telegram-cli"), paths.config());
        assertEquals(temporary.resolve("local/telegram-cli"), paths.data());
    }

    @Test void windowsHasHomeBasedFallbacks() {
        var paths = AppDirectories.resolve("Windows 11", temporary, Map.of());
        assertEquals(temporary.resolve("AppData/Roaming/telegram-cli"), paths.config());
        assertEquals(temporary.resolve("AppData/Local/telegram-cli"), paths.data());
    }

    @Test void unsupportedPlatformsAreNotMistakenForWindows() {
        assertThrows(UnsupportedOperationException.class,
                () -> AppDirectories.resolve("Darwin", temporary, Map.of()));
    }
}
