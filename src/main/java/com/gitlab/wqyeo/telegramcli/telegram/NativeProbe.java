package com.gitlab.wqyeo.telegramcli.telegram;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Parent never loads JNI: a bounded child contains linkage errors and native crashes. */
public final class NativeProbe {
    public static final String TEXT = "🙂 Visit https://example.com #demo @sample_user";
    private static final String WORKER = "com.gitlab.wqyeo.telegramcli.telegram.NativeProbeWorker";
    public record Entity(int offset, int length, String type) {}
    public record Report(int schemaVersion, String mode, String status, String platform,
                         String tdlibRevision, String text, List<Entity> entities) {}
    private record WorkerResult(List<Entity> entities) {}

    public boolean integrated() {
        return NativeProbe.class.getResource("NativeProbeWorker.class") != null;
    }

    public Report run(Path input) throws NativeProbeException, InterruptedException {
        if (!integrated()) {
            throw new NativeProbeException("native-not-built", "Build the pinned SDK, then rebuild with Maven -Pnative.");
        }
        var bundle = NativeBundle.verify(input);
        Process process = null;
        var reader = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                    bundle.platform().startsWith("windows") ? "java.exe" : "java");
            var application = Path.of(NativeProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (bundle.directory().toString().contains(java.io.File.pathSeparator)
                    || application.toString().contains(java.io.File.pathSeparator)) {
                throw new NativeProbeException("unsupported-path", "Native probe paths must not contain the platform classpath separator.");
            }
            String classpath = bundle.directory().resolve("tdlib-java.jar") + java.io.File.pathSeparator + application;
            // Literal argv, never a shell command: bundle paths cannot introduce new arguments.
            var builder = new ProcessBuilder(javaExecutable.toString(), "-Xmx128m", "-Dfile.encoding=UTF-8", "-XX:-UsePerfData",
                    "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=" + (bundle.platform().startsWith("windows") ? "NUL" : "/dev/null"),
                    "--enable-native-access=ALL-UNNAMED", "-Djava.library.path=" + bundle.directory(),
                    "-cp", classpath, WORKER).directory(bundle.directory().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            String systemRoot = builder.environment().get("SystemRoot");
            builder.environment().clear(); // Do not inherit loader/JVM injection options or account credentials.
            if (systemRoot != null) { builder.environment().put("SystemRoot", systemRoot); }
            if (bundle.platform().startsWith("linux")) {
                // Native filename/argv decoding must also support Unicode after the environment reset.
                builder.environment().put("LC_ALL", "C.UTF-8");
            }
            process = builder.start();
            process.getOutputStream().close();
            var stream = process.getInputStream();
            var output = reader.submit(() -> stream.readNBytes(65_537));
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                throw new NativeProbeException("native-process-timeout", "Native probe exceeded its 15 second deadline.");
            }
            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || bytes.length > 65_536) {
                throw new NativeProbeException("native-process-failed", "Native child failed; check platform dependencies and bundle compatibility.");
            }
            var result = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build().readValue(new String(bytes, StandardCharsets.UTF_8), WorkerResult.class);
            var expected = List.of(new Entity(TEXT.indexOf("https://example.com"), 19, "TextEntityTypeUrl"),
                    new Entity(TEXT.indexOf("#demo"), 5, "TextEntityTypeHashtag"),
                    new Entity(TEXT.indexOf("@sample_user"), 12, "TextEntityTypeMention"));
            if (!expected.equals(result.entities())) {
                throw new NativeProbeException("native-output-invalid", "Native entity results did not match the synthetic UTF-16 fixture.");
            }
            return new Report(1, "account-free-native-probe", "ok", bundle.platform(), bundle.revision(), TEXT, result.entities());
        } catch (NativeProbeException | InterruptedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new NativeProbeException("native-process-failed", "Unable to execute or validate the isolated native probe.");
        } finally {
            if (process != null) {
                process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            reader.shutdownNow();
        }
    }
}
