package com.gitlab.wqyeo.telegramcli.telegram;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;

/** Integrity/compatibility preflight, not a signature or protection against a local same-user attacker. */
public record NativeBundle(Path directory, String platform, String revision) {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> FIELDS = Set.of("schemaVersion", "platform", "tdlibRevision",
            "tdlibSourceSha256", "builderImage", "javaVersion", "files");

    public static String currentPlatform() throws NativeProbeException {
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!architecture.equals("amd64") && !architecture.equals("x86_64")) {
            throw new NativeProbeException("unsupported-platform", "The native probe requires an x86_64 bundle.");
        }
        if (os.startsWith("linux")) { return "linux-x86_64"; }
        if (os.startsWith("windows")) { return "windows-x86_64"; }
        throw new NativeProbeException("unsupported-platform", "Native probing is supported only on Linux or Windows x86_64.");
    }

    public static NativeBundle verify(Path input) throws NativeProbeException {
        String platform = currentPlatform();
        try {
            Path root = input.toRealPath();
            Path manifestPath = root.resolve("manifest.json");
            if (!Files.isDirectory(root) || !Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(manifestPath) > 65_536) { throw new IOException(); }
            JsonNode manifest;
            try (var stream = Files.newInputStream(manifestPath)) {
                byte[] bytes = stream.readNBytes(65_537);
                if (bytes.length > 65_536) { throw new IOException(); }
                manifest = JSON.readTree(bytes);
            }
            var fields = new HashSet<String>();
            manifest.fieldNames().forEachRemaining(fields::add);
            if (!manifest.isObject() || !fields.equals(FIELDS) || !manifest.path("schemaVersion").isInt()
                    || manifest.path("schemaVersion").intValue() != 1
                    || !platform.equals(manifest.path("platform").textValue())) { throw new IOException(); }
            JsonNode lock;
            try (var stream = NativeBundle.class.getResourceAsStream("/native/tdlib.lock.json")) {
                lock = JSON.readTree(stream);
            }
            String revision = lock.path("revision").textValue();
            if (!revision.equals(manifest.path("tdlibRevision").textValue())
                    || !lock.path("sourceSha256").textValue().equals(manifest.path("tdlibSourceSha256").textValue())) {
                throw new IOException();
            }
            JsonNode files = manifest.path("files");
            if (!files.isObject() || files.size() < 3 || files.size() > 32) { throw new IOException(); }
            String library = platform.startsWith("linux") ? "libtdjni.so" : "tdjni.dll";
            if (!files.has("tdlib-java.jar") || !files.has(library) || !files.has("licenses/TDLib-LICENSE")) {
                throw new IOException();
            }
            Set<String> listed = new HashSet<>();
            var entries = files.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                String name = entry.getKey();
                if (!name.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*")) {
                    throw new IOException();
                }
                Path relative = Path.of(name);
                if (!relative.normalize().equals(relative) || relative.isAbsolute()) { throw new IOException(); }
                Path file = root;
                for (var component : relative) {
                    file = file.resolve(component);
                    if (Files.isSymbolicLink(file)) { throw new IOException(); }
                }
                var descriptor = entry.getValue();
                String expected = descriptor.path("sha256").textValue();
                if (!descriptor.isObject() || descriptor.size() != 2 || expected == null || !expected.matches("[a-f0-9]{64}")
                        || !descriptor.path("size").isIntegralNumber() || !descriptor.path("size").canConvertToLong()
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { throw new IOException(); }
                long size = Files.size(file);
                if (size < 1 || size > 268_435_456 || size != descriptor.path("size").longValue()) { throw new IOException(); }
                var digest = MessageDigest.getInstance("SHA-256");
                try (var stream = Files.newInputStream(file)) {
                    byte[] buffer = new byte[65_536];
                    long consumed = 0;
                    for (int read; (read = stream.read(buffer)) >= 0;) {
                        consumed += read;
                        if (consumed > size) { throw new IOException(); }
                        digest.update(buffer, 0, read);
                    }
                    if (consumed != size) { throw new IOException(); }
                }
                if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) { throw new IOException(); }
                listed.add(name);
            }
            try (var paths = Files.walk(root)) {
                var actual = new HashSet<String>();
                var iterator = paths.iterator();
                int visited = 0;
                while (iterator.hasNext()) {
                    var path = iterator.next();
                    if (++visited > 128) { throw new IOException(); }
                    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        actual.add(root.relativize(path).toString().replace('\\', '/'));
                    }
                }
                actual.remove("manifest.json");
                if (!actual.equals(listed)) { throw new IOException(); }
            }
            try (var jar = new ZipFile(root.resolve("tdlib-java.jar").toFile())) {
                if (jar.getEntry("org/drinkless/tdlib/Client.class") == null
                        || jar.getEntry("org/drinkless/tdlib/TdApi.class") == null) { throw new IOException(); }
            }
            return new NativeBundle(root, platform, revision);
        } catch (Exception exception) {
            throw new NativeProbeException("bundle-invalid", "Native bundle is missing, incompatible, or failed its integrity checks.");
        }
    }
}
