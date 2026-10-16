package com.gitlab.wqyeo.telegramcli.session;

import static org.junit.jupiter.api.Assertions.*;
import com.gitlab.wqyeo.telegramcli.telegram.NativeBundle;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Explicit native integration, synthetic values only. Never an account session. */
@Timeout(30)
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
class SessionWorkerTest {
    @TempDir Path workspace;
    private Path bundle() throws Exception {
        return Path.of(System.getProperty("tgc.native.bundle", "native-build/bundles/" + NativeBundle.currentPlatform()));
    }
    @Test void fixedNativeWorkerOwnsLeaseClosesAndRestartsWithoutAccountFiles() throws Exception {
        assertTrue(SessionWorker.integrated()); ProfileStore.initialize(workspace, "work");
        for (int iteration = 0; iteration < 2; iteration++) {
            try (var supervisor = SessionWorker.startAccountFree(bundle(), workspace, "work")) {
                assertEquals(SessionState.Lifecycle.RUNNING, supervisor.latest().lifecycle());
                assertEquals(SessionState.Authorization.NONE, supervisor.latest().authorization());
                assertEquals(SessionState.Connection.OFFLINE, supervisor.latest().connection());
                assertEquals(SessionException.Code.PROFILE_BUSY,
                        assertThrows(SessionException.class, () -> ProfileStore.acquire(workspace, "work")).code());
                if (iteration == 0) { assertThrows(SessionException.class, () -> SessionWorker.startAccountFree(bundle(), workspace, "work")); }
                // The failed contender must not free the original worker's lock.
                assertThrows(SessionException.class, () -> ProfileStore.acquire(workspace, "work"));
                supervisor.close(); assertEquals(SessionState.Lifecycle.CLOSED, supervisor.latest().lifecycle());
                assertTrue(supervisor.failureCode().isEmpty());
            }
            try (var lease = ProfileStore.acquire(workspace, "work")) { assertNotNull(lease); }
        }
        try (var files = Files.walk(workspace)) {
            assertEquals(java.util.Set.of("", "work", "work/profile.json", "work/profile.lock"),
                    files.map(workspace::relativize).map(Path::toString).collect(java.util.stream.Collectors.toSet()));
        }
    }
    @Test void refusesMissingProfileWithoutCreatingItAndInvalidBundlesHaveFixedFailure() throws Exception {
        var missing = assertThrows(SessionException.class, () -> SessionWorker.startAccountFree(bundle(), workspace, "missing"));
        assertNull(missing.getCause()); assertFalse(Files.exists(workspace.resolve("missing")));
        assertEquals(SessionException.Code.BUNDLE_INVALID,
                assertThrows(SessionException.class, () -> SessionWorker.startAccountFree(workspace, workspace, "missing")).code());
    }
    @Test void actualWorkerRefusesParentTransportEofAndReleasesItsOwnLease() throws Exception {
        ProfileStore.initialize(workspace, "work");
        var generation = java.util.UUID.randomUUID();
        Process process = SessionWorker.launch(NativeBundle.verify(bundle()), workspace, "work", generation);
        try {
            var handshake = new java.util.concurrent.FutureTask<>(() -> SessionProtocol.read(process.getInputStream()));
            Thread.ofVirtual().start(handshake);
            assertEquals(SessionState.Signal.STARTED, handshake.get(5, java.util.concurrent.TimeUnit.SECONDS).signal());
            assertThrows(SessionException.class, () -> ProfileStore.acquire(workspace, "work"));
            process.getOutputStream().close(); // Same stdin EOF delivered after an owning parent's disappearance.
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            assertEquals(-1, process.getInputStream().read()); // No fabricated clean-close acknowledgment.
            try (var lease = ProfileStore.acquire(workspace, "work")) { assertNotNull(lease); }
        } finally {
            process.destroyForcibly(); process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    @Test void corruptNativeLoadCannotEscapeChildOrLeaveProfileLocked() throws Exception {
        Path broken = Files.createDirectory(workspace.resolve("broken-bundle"));
        Path source = bundle().toRealPath();
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path destination = broken.resolve(source.relativize(file));
                if (Files.isDirectory(file)) { Files.createDirectories(destination); } else { Files.copy(file, destination); }
            }
        }
        byte[] invalid = "SYNTHETIC_DO_NOT_PRINT invalid library".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(broken.resolve("libtdjni.so"), invalid);
        var mapper = JsonMapper.builder().build();
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(broken.resolve("manifest.json").toFile());
        var entry = (com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("files").path("libtdjni.so");
        entry.put("size", invalid.length); entry.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(invalid)));
        mapper.writeValue(broken.resolve("manifest.json").toFile(), manifest);
        ProfileStore.initialize(workspace, "work");
        var error = assertThrows(SessionException.class, () -> SessionWorker.startAccountFree(broken, workspace, "work"));
        assertNull(error.getCause()); assertFalse(error.toString().contains("DO_NOT_PRINT"));
        try (var lease = ProfileStore.acquire(workspace, "work")) { assertNotNull(lease); }
        try (var files = Files.list(broken)) { assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith("hs_err"))); }
    }
}
