package com.gitlab.wqyeo.telegramcli.session;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
class ProfileStoreTest {
    @TempDir Path workspace;

    private void invalid(String name) {
        var exception = assertThrows(SessionException.class, () -> ProfileStore.acquire(workspace, name));
        assertFalse(exception.toString().contains("DO_NOT_PRINT")); assertNull(exception.getCause());
    }
    @Test void explicitInitializationIsPrivateFixedAndDoesNotRepairOrReinitialize() throws Exception {
        try (var paths = Files.list(workspace)) { assertEquals(0, paths.count()); }
        invalid("default"); assertFalse(Files.exists(workspace.resolve("default")));
        ProfileStore.initialize(workspace, "default");
        Path profile = workspace.resolve("default");
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(profile));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(profile.resolve("profile.json")));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(profile.resolve("profile.lock")));
        assertEquals("{\"schemaVersion\":1}\n", Files.readString(profile.resolve("profile.json")));
        assertEquals(0, Files.size(profile.resolve("profile.lock")));
        assertThrows(SessionException.class, () -> ProfileStore.initialize(workspace, "default"));
        try (var lease = ProfileStore.acquire(workspace, "default")) { assertNotNull(lease); }
    }
    @Test void rejectsTraversalLinksUnsafeModesAndInvalidMetadataWithoutRepair() throws Exception {
        for (String name : new String[] {"../DO_NOT_PRINT", "/absolute", ".", "", "a/b", "x".repeat(65)}) {
            assertThrows(SessionException.class, () -> ProfileStore.initialize(workspace, name));
        }
        ProfileStore.initialize(workspace, "default");
        Path profile = workspace.resolve("default");
        Files.setPosixFilePermissions(profile, PosixFilePermissions.fromString("rwxr-x---")); invalid("default");
        assertEquals(PosixFilePermissions.fromString("rwxr-x---"), Files.getPosixFilePermissions(profile));
        Files.setPosixFilePermissions(profile, PosixFilePermissions.fromString("rwx------"));
        Path metadata = profile.resolve("profile.json");
        Files.writeString(metadata, "SYNTHETIC_DO_NOT_PRINT"); invalid("default");
        Files.writeString(metadata, "{\"schemaVersion\":1}\n");
        Files.setPosixFilePermissions(metadata, PosixFilePermissions.fromString("rw-r--r--")); invalid("default");
        Files.setPosixFilePermissions(metadata, PosixFilePermissions.fromString("rw-------"));
        Files.delete(metadata); Files.createSymbolicLink(metadata, Path.of("../missing_DO_NOT_PRINT")); invalid("default");
        Files.delete(metadata); Files.createDirectory(metadata); invalid("default");
        Files.createSymbolicLink(workspace.resolve("alias"), Path.of("default")); invalid("alias");
    }
    @Test void refusesUnsafeWorkspaceRelativePathsAndSymlinkAncestors() throws Exception {
        assertThrows(SessionException.class, () -> ProfileStore.initialize(Path.of("relative"), "default"));
        assertThrows(SessionException.class, () -> ProfileStore.initialize(workspace.resolve("a/../b"), "default"));
        Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThrows(SessionException.class, () -> ProfileStore.initialize(workspace, "default"));
        Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("rwx------"));
        Path real = Files.createDirectory(workspace.resolve("real"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createSymbolicLink(workspace.resolve("alias"), real);
        assertThrows(SessionException.class, () -> ProfileStore.initialize(workspace.resolve("alias"), "default"));
    }
    @Test void badLockObjectsAreRefusedAndNeverUnlinked() throws Exception {
        ProfileStore.initialize(workspace, "default");
        Path lock = workspace.resolve("default/profile.lock");
        Files.writeString(lock, "SYNTHETIC_DO_NOT_PRINT"); invalid("default");
        assertEquals("SYNTHETIC_DO_NOT_PRINT", Files.readString(lock));
        Files.delete(lock); Files.createSymbolicLink(lock, Path.of("profile.json")); invalid("default");
        assertTrue(Files.isSymbolicLink(lock));
    }
    @Test void duplicateSameJvmAcquisitionCannotDropOriginalKernelLock() throws Exception {
        ProfileStore.initialize(workspace, "default");
        try (var lease = ProfileStore.acquire(workspace, "default")) {
            var busy = assertThrows(SessionException.class, () -> ProfileStore.acquire(workspace, "default"));
            assertEquals(SessionException.Code.PROFILE_BUSY, busy.code());
            Process contender = WorkerHarness.launch(workspace, "default", UUID.randomUUID(), "NORMAL");
            try {
                assertTrue(contender.waitFor(5, TimeUnit.SECONDS));
                assertNotEquals(0, contender.exitValue());
                assertEquals(-1, contender.getInputStream().read());
            } finally { contender.destroyForcibly(); }
        }
        try (var lease = ProfileStore.acquire(workspace, "default")) { lease.close(); lease.close(); }
    }
    @Test void competingJvmOwnsLeaseUntilItActuallyExitsAndCrashReleasesIt() throws Exception {
        ProfileStore.initialize(workspace, "default");
        UUID generation = UUID.randomUUID();
        Process owner = WorkerHarness.launch(workspace, "default", generation, "NORMAL");
        try {
            var handshake = new FutureTask<>(() -> SessionProtocol.read(owner.getInputStream()));
            Thread.ofVirtual().start(handshake);
            assertEquals(SessionState.Signal.STARTED, handshake.get(5, TimeUnit.SECONDS).signal());
            assertEquals(SessionException.Code.PROFILE_BUSY,
                    assertThrows(SessionException.class, () -> ProfileStore.acquire(workspace, "default")).code());
            owner.destroyForcibly(); assertTrue(owner.waitFor(5, TimeUnit.SECONDS));
            try (var recovered = ProfileStore.acquire(workspace, "default")) { assertNotNull(recovered); }
        } finally { owner.destroyForcibly(); }
    }
}
