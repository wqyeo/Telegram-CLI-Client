package com.gitlab.wqyeo.telegramcli.session;

import static com.gitlab.wqyeo.telegramcli.session.SessionException.Code.*;
import com.gitlab.wqyeo.telegramcli.telegram.NativeBundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Production launch mechanics, exposed ONLY as a fixed account-free check. */
public final class SessionWorker {
    private static final String WORKER = "com.gitlab.wqyeo.telegramcli.session.NativeSessionWorker";
    private SessionWorker() {}

    public static boolean integrated() { return SessionWorker.class.getResource("NativeSessionWorker.class") != null; }

    /** Existing commands never call this. Requires an explicitly initialized private profile. */
    public static SessionSupervisor startAccountFree(Path bundlePath, Path workspace, String profile) throws SessionException {
        if (!integrated()) { throw new SessionException(NATIVE_NOT_BUILT); }
        if (!System.getProperty("os.name").equalsIgnoreCase("linux")) { throw new SessionException(STORAGE_UNSUPPORTED); }
        final NativeBundle bundle;
        try { bundle = NativeBundle.verify(bundlePath); }
        catch (Exception exception) { throw new SessionException(BUNDLE_INVALID); }
        return SessionSupervisor.start(generation -> launch(bundle, workspace, profile, generation), SessionSupervisor.Deadlines.defaults());
    }

    // Internal component seam also used to verify actual worker EOF/lease behavior.
    static Process launch(NativeBundle bundle, Path workspace, String profile, UUID generation) throws IOException {
        Process process = null;
        try {
            if (workspace == null || !workspace.isAbsolute() || !workspace.equals(workspace.normalize())
                    || profile == null || !profile.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) { throw new SessionException(PROFILE_INVALID); }
            var classpath = new LinkedHashSet<Path>();
            classpath.add(bundle.directory().resolve("tdlib-java.jar"));
            // Trusted code-source locations, not ambient CLASSPATH/java.class.path or shell text.
            for (Class<?> type : new Class<?>[] {SessionWorker.class,
                    com.fasterxml.jackson.databind.json.JsonMapper.class, com.fasterxml.jackson.core.JsonParser.class,
                    com.fasterxml.jackson.annotation.JsonProperty.class}) {
                classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()));
            }
            for (Path entry : classpath) {
                if (!entry.isAbsolute() || entry.toString().contains(File.pathSeparator)) { throw new SessionException(START_FAILED); }
            }
            String joined = String.join(File.pathSeparator, classpath.stream().map(Path::toString).toList());
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx128m", "-Dfile.encoding=UTF-8", "-XX:-UsePerfData", "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=/dev/null",
                    "--enable-native-access=ALL-UNNAMED", "-Djava.library.path=" + bundle.directory(), "-cp", joined, WORKER,
                    workspace.toString(), profile, generation.toString())
                    .directory(bundle.directory().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear(); builder.environment().put("LC_ALL", "C.UTF-8");
            process = builder.start();
            // Deliberately cannot accept real provisioning values through this public gateway.
            // No real provider, passphrase, vault or account client is invoked.
            try (var synthetic = CredentialMaterial.generate(1, "00000000000000000000000000000000".getBytes(StandardCharsets.US_ASCII))) {
                SecretTransfer.write(process.getOutputStream(), generation, synthetic);
            }
            return process;
        } catch (Exception exception) {
            if (process != null) {
                process.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                try { process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
                finally { if (interrupted) { Thread.currentThread().interrupt(); } }
            }
            throw new SessionException(START_FAILED);
        }
    }
}
