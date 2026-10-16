package com.gitlab.wqyeo.telegramcli.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gitlab.wqyeo.telegramcli.Main;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MainTest {
    @TempDir Path temporary;

    private record Result(int exit, String out, String err) {}

    private Result run(String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        int exit = Main.execute(args, new PrintWriter(out, true), new PrintWriter(err, true));
        return new Result(exit, out.toString(), err.toString());
    }

    @Test void noArgumentsShowsHelp() {
        var result = run();
        assertEquals(0, result.exit());
        assertTrue(result.out().contains("Usage: tgc"));
        assertEquals("", result.err());
    }

    @Test void versionComesFromTheBuild() {
        var result = run("--version");
        assertEquals(0, result.exit());
        assertTrue(result.out().startsWith("Unofficial Telegram CLI 0.1.0-SNAPSHOT"));
        assertFalse(result.out().contains("${"));
        assertEquals("", result.err());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "demo --help", "diagnostics --help", "tui --help", "native-check --help",
            "profile-init --help", "session-check --help", "vault-create --help", "vault-check --help"})
    void helpIsAvailable(String arguments) {
        var result = run(arguments.split(" "));
        assertEquals(0, result.exit());
        assertTrue(result.out().contains("Usage:"));
        assertEquals("", result.err());
        assertFalse(result.out().contains("\u001b"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"login", "send", "tui", "--unknown"})
    void unavailableCommandsAndInvalidOptionsFailInsteadOfPretendingSuccess(String argument) {
        var result = run(argument);
        assertEquals(2, result.exit());
        assertEquals("", result.out());
        assertFalse(result.err().isBlank());
    }

    @Test void diagnosticsJsonIsOneCleanObject() throws Exception {
        var result = run("diagnostics", "--json");
        assertEquals(0, result.exit());
        assertEquals("", result.err());
        var json = JsonMapper.builder().build().readTree(result.out());
        assertEquals(1, json.get("schemaVersion").asInt());
        assertEquals("offline", json.get("mode").asText());
        assertEquals(25, json.get("javaFeature").asInt());
        assertEquals("not-integrated", json.get("engine").asText());
        assertEquals("default", json.get("config").get("profile").asText());
    }

    @Test void demoIsClearlySyntheticAndClosed() throws Exception {
        var result = run("demo", "--json");
        assertEquals(0, result.exit());
        assertEquals("", result.err());
        var json = JsonMapper.builder().build().readTree(result.out());
        assertEquals("offline-demo", json.get("mode").asText());
        assertEquals("CLOSED", json.get("state").get("connection").asText());
        assertEquals(1, json.get("state").get("messages").size());
        assertTrue(json.toString().contains("Synthetic message"));
    }

    @Test void demoTextExplainsThatNoMessageWasSent() {
        var result = run("demo");
        assertEquals(0, result.exit());
        assertTrue(result.out().contains("OFFLINE DEMO"));
        assertTrue(result.out().contains("no Telegram connection or message send occurred"));
        assertEquals("", result.err());
    }

    @Test void configurationWorksBeforeOrAfterTheSubcommand() throws Exception {
        var file = temporary.resolve("settings.json");
        Files.writeString(file, "{\"profile\":\"work\"}");
        for (var result : new Result[] {
                run("--config", file.toString(), "diagnostics", "--json"),
                run("diagnostics", "--config", file.toString(), "--json")}) {
            assertEquals(0, result.exit());
            assertTrue(result.out().contains("\"profile\":\"work\""));
            assertEquals("", result.err());
        }
    }

    @Test void badConfigurationIsRedactedAndReturnsUsageFailure() throws Exception {
        var file = temporary.resolve("settings.json");
        Files.writeString(file, "{\"unexpected\":\"DO_NOT_PRINT_THIS\"}");
        var result = run("demo", "--config", file.toString());
        assertEquals(2, result.exit());
        assertEquals("", result.out());
        assertTrue(result.err().contains("Configuration is invalid or unreadable"));
        assertFalse(result.err().contains("DO_NOT_PRINT_THIS"));
        assertFalse(result.err().contains("Exception"));
    }

    @Test void nativeProbeFailureIsStructuredAndRedacted() throws Exception {
        var result = run("native-check", "--bundle", temporary.resolve("DO_NOT_PRINT").toString(), "--json");
        assertEquals(1, result.exit());
        assertEquals("", result.err());
        var report = JsonMapper.builder().build().readTree(result.out());
        assertEquals(1, report.get("schemaVersion").intValue());
        assertEquals("error", report.get("status").textValue());
        assertFalse(result.out().contains("DO_NOT_PRINT"));
        assertFalse(result.out().contains("Exception"));
    }

    @Test void profileInitializationIsExplicitAndNeverOverwrites() throws Exception {
        Path missing = temporary.resolve("missing-workspace");
        var refused = run("profile-init", "--workspace", missing.toString(), "--profile", "work", "--json");
        assertEquals(1, refused.exit()); assertFalse(Files.exists(missing));
        var initialized = run("profile-init", "--workspace", temporary.toString(), "--profile", "work", "--json");
        if (!System.getProperty("os.name").equalsIgnoreCase("linux")) {
            assertEquals(1, initialized.exit()); return;
        }
        assertEquals(0, initialized.exit()); assertEquals("", initialized.err());
        Path metadata = temporary.resolve("work/profile.json");
        byte[] before = Files.readAllBytes(metadata);
        assertEquals(1, run("profile-init", "--workspace", temporary.toString(), "--profile", "work", "--json").exit());
        assertArrayEquals(before, Files.readAllBytes(metadata));
        try (var files = Files.list(temporary.resolve("work"))) {
            assertEquals(java.util.Set.of("profile.json", "profile.lock"), files.map(file -> file.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test void workerCheckRefusesWithoutImplicitInitializationOrRawPaths() throws Exception {
        Path missing = temporary.resolve("DO_NOT_PRINT");
        var result = run("session-check", "--workspace", missing.toString(), "--profile", "work", "--bundle", missing.toString(), "--json");
        assertEquals(1, result.exit()); assertEquals("", result.err());
        var report = JsonMapper.builder().build().readTree(result.out());
        assertEquals("error", report.path("status").textValue());
        String expected = !com.gitlab.wqyeo.telegramcli.session.SessionWorker.integrated() ? "NATIVE_NOT_BUILT"
                : System.getProperty("os.name").equalsIgnoreCase("linux") ? "BUNDLE_INVALID" : "STORAGE_UNSUPPORTED";
        assertEquals(expected, report.path("code").textValue());
        assertFalse(result.out().contains("DO_NOT_PRINT")); assertFalse(Files.exists(missing));
        assertEquals(2, run("profile-init", "--workspace", temporary.toString()).exit());
        assertEquals(2, run("session-check", "--profile", "work").exit());
    }

    @Test void workerHoldDurationIsBoundedBeforeAnyWorkerOrFiles() throws Exception {
        for (String seconds : new String[] {"-1", "31", "2147483647"}) {
            Path missing = temporary.resolve("never-created");
            var result = run("session-check", "--workspace", missing.toString(), "--profile", "work", "--hold-seconds", seconds, "--json");
            assertEquals(2, result.exit()); assertEquals("", result.err());
            assertEquals("INVALID_ARGUMENT", JsonMapper.builder().build().readTree(result.out()).path("code").textValue());
            assertFalse(Files.exists(missing));
        }
    }

    @Test void terminalModesRequireOneTrueAcknowledgmentAndScenarioCannotChangeTheOldDemo() {
        for (String[] args : new String[][] {
                {"tui", "--demo", "--read-only-demo"}, {"tui", "--demo=false"}, {"tui", "--read-only-demo=false"},
                {"tui", "--demo", "--auth-scenario", "FULL"}, {"tui", "--auth-scenario", "FULL"}}) {
            var result = run(args); assertEquals(2, result.exit()); assertEquals("", result.out()); assertFalse(result.err().isBlank());
        }
        assertTrue(run("tui", "--help").out().contains("--read-only-demo"));
    }

    @Test void missingConfigurationFailsButDoesNotPreventHelp() {
        var missing = temporary.resolve("missing.json").toString();
        assertEquals(2, run("diagnostics", "--config", missing).exit());
        assertEquals(0, run("--config", missing, "--help").exit());
    }
}
