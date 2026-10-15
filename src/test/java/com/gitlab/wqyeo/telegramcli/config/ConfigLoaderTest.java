package com.gitlab.wqyeo.telegramcli.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigLoaderTest {
    @TempDir Path temporary;
    private final ConfigLoader loader = new ConfigLoader();

    @Test void loadsDefaults() throws IOException {
        assertEquals(new AppConfig("default", 100), loader.load(null));
    }

    @Test void mergesPartialOverrides() throws IOException {
        var file = temporary.resolve("config.json");
        Files.writeString(file, "{\"historyLimit\":7}");
        assertEquals(new AppConfig("default", 7), loader.load(file));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]", "null", "", "{} {}", "{", "{\"unknown\":1}",
            "{\"profile\":\"../escape\"}", "{\"profile\":null}",
            "{\"historyLimit\":0}", "{\"historyLimit\":1001}",
            "{\"historyLimit\":null}", "{\"historyLimit\":\"5\"}", "{\"historyLimit\":1.5}",
            "{\"historyLimit\":1,\"historyLimit\":2}"
    })
    void rejectsInvalidConfiguration(String json) throws IOException {
        var file = temporary.resolve("invalid.json");
        Files.writeString(file, json);
        assertThrows(IOException.class, () -> loader.load(file));
    }

    @Test void rejectsOversizedFiles() throws IOException {
        var file = temporary.resolve("oversized.json");
        Files.writeString(file, " ".repeat(65_537));
        assertThrows(IOException.class, () -> loader.load(file));
    }

    @Test void missingFilesAreErrors() {
        assertThrows(IOException.class, () -> loader.load(temporary.resolve("missing.json")));
    }
}
