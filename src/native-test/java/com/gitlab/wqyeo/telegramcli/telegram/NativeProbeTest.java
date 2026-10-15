package com.gitlab.wqyeo.telegramcli.telegram;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Explicit -Pnative tests: missing bundles are failures, never silently skipped. */
class NativeProbeTest {
    @TempDir Path temporary;
    private Path bundle() throws Exception {
        return Path.of(System.getProperty("tgc.native.bundle", "native-build/bundles/" + NativeBundle.currentPlatform()));
    }
    @Test void executesAccountFreeFixtureWithUtf16Offsets() throws Exception {
        var result = new NativeProbe().run(bundle());
        assertEquals("ok", result.status());
        assertEquals(3, result.entities().size());
        assertEquals(NativeProbe.TEXT.indexOf("https://"), result.entities().getFirst().offset());
        assertEquals("https://example.com", result.text().substring(result.entities().getFirst().offset(),
                result.entities().getFirst().offset() + result.entities().getFirst().length()));
        assertTrue(result.entities().getFirst().offset()
                > result.text().codePointCount(0, result.entities().getFirst().offset()));
    }
    @Test void badNativeBinaryIsContainedInChildAndNeverLeaksLoaderDiagnostics() throws Exception {
        Path source = bundle().toRealPath();
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var destination = temporary.resolve(source.relativize(path));
                if (Files.isDirectory(path)) { Files.createDirectories(destination); }
                else { Files.copy(path, destination); }
            }
        }
        String library = NativeBundle.currentPlatform().startsWith("linux") ? "libtdjni.so" : "tdjni.dll";
        byte[] bytes = "not a native library".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(temporary.resolve(library), bytes);
        var json = JsonMapper.builder().build();
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(temporary.resolve("manifest.json").toFile());
        var entry = (com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("files").path(library);
        entry.put("size", bytes.length);
        entry.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        json.writeValue(temporary.resolve("manifest.json").toFile(), manifest);
        var exception = assertThrows(NativeProbeException.class, () -> new NativeProbe().run(temporary));
        assertEquals("native-process-failed", exception.code());
        assertFalse(exception.getMessage().contains(temporary.toString()));
        assertFalse(exception.getMessage().contains("UnsatisfiedLinkError"));
        try (var files = Files.list(temporary)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("hs_err")));
        }
    }
}
