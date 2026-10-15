package com.gitlab.wqyeo.telegramcli.telegram;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeBundleTest {
    @TempDir Path directory;
    private final JsonMapper json = JsonMapper.builder().build();
    private Map<String, Object> fixture() throws Exception {
        try (var output = new ZipOutputStream(Files.newOutputStream(directory.resolve("tdlib-java.jar")))) {
            for (var name : new String[] {"Client", "TdApi"}) {
                output.putNextEntry(new ZipEntry("org/drinkless/tdlib/" + name + ".class")); output.write(1); output.closeEntry();
            }
        }
        String library = NativeBundle.currentPlatform().startsWith("linux") ? "libtdjni.so" : "tdjni.dll";
        Files.writeString(directory.resolve(library), "not native code; preflight must not load this");
        Files.createDirectory(directory.resolve("licenses"));
        Files.writeString(directory.resolve("licenses/TDLib-LICENSE"), "synthetic test fixture");
        var files = new HashMap<String, Object>();
        for (var name : new String[] {"tdlib-java.jar", library, "licenses/TDLib-LICENSE"}) {
            byte[] bytes = Files.readAllBytes(directory.resolve(name));
            files.put(name, Map.of("size", bytes.length, "sha256",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
        }
        var lock = json.readTree(NativeBundle.class.getResourceAsStream("/native/tdlib.lock.json"));
        var manifest = new HashMap<String, Object>();
        manifest.put("schemaVersion", 1); manifest.put("platform", NativeBundle.currentPlatform());
        manifest.put("tdlibRevision", lock.get("revision").asText());
        manifest.put("tdlibSourceSha256", lock.get("sourceSha256").asText());
        manifest.put("builderImage", "fixture"); manifest.put("javaVersion", "fixture"); manifest.put("files", files);
        return manifest;
    }
    private void write(Map<String, Object> manifest) throws Exception {
        json.writeValue(directory.resolve("manifest.json").toFile(), manifest);
    }
    private void rejected() {
        var exception = assertThrows(NativeProbeException.class, () -> NativeBundle.verify(directory));
        assertEquals("bundle-invalid", exception.code());
        assertFalse(exception.getMessage().contains(directory.toString()));
    }
    @Test void checksHashesWithoutLoadingAnyNativeCode() throws Exception {
        write(fixture()); assertEquals(directory.toRealPath(), NativeBundle.verify(directory).directory());
    }
    @Test void detectsTampering() throws Exception {
        write(fixture()); Files.writeString(directory.resolve("licenses/TDLib-LICENSE"), "tampered"); rejected();
    }
    @Test void detectsRevisionMismatch() throws Exception {
        var manifest = fixture(); manifest.put("tdlibRevision", "wrong"); write(manifest); rejected();
    }
    @Test void detectsPlatformMismatch() throws Exception {
        var manifest = fixture(); manifest.put("platform", "other-platform"); write(manifest); rejected();
    }
    @Test void rejectsAMissingDeclaredDependencyBeforeLoadingAnything() throws Exception {
        var manifest = fixture();
        var descriptors = new HashMap<String, Object>();
        ((Map<?, ?>) manifest.get("files")).forEach((name, value) -> descriptors.put(name.toString(), value));
        descriptors.put("lib-required.so", Map.of("size", 1, "sha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[] {1}))));
        manifest.put("files", descriptors);
        write(manifest); rejected();
    }
    @Test void rejectsUnexpectedFiles() throws Exception {
        write(fixture()); Files.writeString(directory.resolve("unlisted.dll"), "unexpected"); rejected();
    }
    @Test void rejectsTraversalAndUnknownMetadata() throws Exception {
        var manifest = fixture(); manifest.put("extra", "DO_NOT_PRINT"); write(manifest); rejected();
        manifest.remove("extra");
        var descriptors = new HashMap<String, Object>();
        ((Map<?, ?>) manifest.get("files")).forEach((name, value) -> descriptors.put(name.toString(), value));
        descriptors.put("../escape", Map.of("sha256", "a".repeat(64), "size", 1));
        manifest.put("files", descriptors);
        write(manifest); rejected();
    }
    @Test void rejectsOversizedAndDuplicateMetadata() throws Exception {
        write(fixture());
        String valid = Files.readString(directory.resolve("manifest.json"));
        Files.writeString(directory.resolve("manifest.json"), " ".repeat(65_537)); rejected();
        Files.writeString(directory.resolve("manifest.json"), valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"));
        rejected();
    }
    @Test void missingManifestIsSanitized() { rejected(); }
}
