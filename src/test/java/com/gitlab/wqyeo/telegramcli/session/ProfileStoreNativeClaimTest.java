package com.gitlab.wqyeo.telegramcli.session;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** PUBLIC-only Java ownership fixtures: no JNI, SDK, credential providers or account engine. */
@Timeout(20)
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
class ProfileStoreNativeClaimTest {
    private static final UUID GENERATION = UUID.fromString("00000000-0000-4000-8000-000000000001");
    @TempDir Path workspace;

    private Path directory(Path path) throws Exception {
        return Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    private void kernelLockHeld(Path root, String name) throws Exception { kernelLock(root, name, true); }
    private void kernelLock(Path root, String name, boolean busy) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-XX:-UsePerfData", "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=/dev/null", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                PublicLeaseContender.class.getName(), root.toString(), name);
        builder.environment().clear();
        builder.environment().put("LC_ALL", "C.UTF-8");
        Process contender = builder.start();
        try {
            assertTrue(contender.waitFor(5, TimeUnit.SECONDS));
            assertEquals(busy ? 20 : 0, contender.exitValue());
            String expected = busy ? "PUBLIC_LOCK_BUSY\n" : "PUBLIC_LOCK_AVAILABLE\n";
            assertArrayEquals(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8), contender.getInputStream().readNBytes(64));
            assertEquals(-1, contender.getInputStream().read());
            assertEquals(0, contender.getErrorStream().readNBytes(1024).length);
        } finally { contender.destroyForcibly(); }
    }
    private void invalidMint(ProfileStore.NativeClaim claim, AtomicInteger calls) {
        var failure = assertThrows(SessionException.class, () -> claim.mint(identity -> {
            calls.incrementAndGet(); return 41;
        }));
        assertEquals(SessionException.Code.PROFILE_INVALID, failure.code());
        assertNull(failure.getCause());
        assertEquals(0, calls.get());
    }

    @Test void identityIsIndependentlyDerivedAndMintIsSingleUseWhileLeaseStaysHeld() throws Exception {
        ProfileStore.initialize(workspace, "PUBLIC");
        try (var lease = ProfileStore.acquire(workspace, "PUBLIC")) {
            var claim = lease.claimNative(GENERATION);
            var calls = new AtomicInteger();
            try {
                assertEquals(41, claim.mint(identity -> {
                    calls.incrementAndGet();
                    assertEquals(workspace.resolve("PUBLIC").toString(), identity.directory());
                    assertEquals("PUBLIC", identity.profileName());
                    assertEquals(GENERATION.toString(), identity.generation());
                    try {
                        var independent = Files.readAttributes(workspace.resolve("PUBLIC"), "unix:dev,ino", NOFOLLOW_LINKS);
                        assertEquals(independent.get("dev"), identity.device());
                        assertEquals(independent.get("ino"), identity.inode());
                    } catch (java.io.IOException failure) { fail(failure); }
                    assertFalse(identity.toString().contains(workspace.toString()));
                    return 41;
                }));
                assertEquals(1, calls.get());
                assertThrows(SessionException.class, () -> lease.claimNative(GENERATION));
                assertThrows(SessionException.class, () -> claim.mint(identity -> { calls.incrementAndGet(); return 42; }));
                assertEquals(1, calls.get());
                assertEquals(SessionException.Code.CLOSE_FAILED, assertThrows(SessionException.class, lease::close).code());
                // Both snapshot/mint have inspected attributes without reopening a lock descriptor.
                kernelLockHeld(workspace, "PUBLIC");
            } finally { claim.confirmNativeStopped(); } // fixture knows NO native client was constructed
            assertThrows(SessionException.class, () -> claim.mint(identity -> 43));
        }
        kernelLock(workspace, "PUBLIC", false); // positive control: the exact child works after real release
        try (var reopened = ProfileStore.acquire(workspace, "PUBLIC")) { assertNotNull(reopened); }
    }

    @Test void mintFailureIsNotShutdownEvidenceAndDoesNotGrantRetry() throws Exception {
        ProfileStore.initialize(workspace, "PUBLIC");
        try (var lease = ProfileStore.acquire(workspace, "PUBLIC")) {
            var claim = lease.claimNative(GENERATION);
            try {
                assertEquals(SessionException.Code.START_FAILED, assertThrows(SessionException.class,
                        () -> claim.mint(identity -> { throw new SessionException(SessionException.Code.START_FAILED); })).code());
                assertThrows(SessionException.class, lease::close);
                invalidMint(claim, new AtomicInteger());
                kernelLockHeld(workspace, "PUBLIC");
            } finally { claim.confirmNativeStopped(); }
        }
    }

    @Test void nullAndNonpositiveMintsAreConsumedAndDoNotAutomaticallyReleaseOwnership() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            String name = "PUBLIC_" + mode;
            ProfileStore.initialize(workspace, name);
            try (var lease = ProfileStore.acquire(workspace, name)) {
                var claim = lease.claimNative(GENERATION);
                try {
                    long result = mode == 1 ? 0 : -1;
                    ProfileStore.NativeMinter minter = mode == 0 ? null : identity -> result;
                    assertThrows(SessionException.class, () -> claim.mint(minter));
                    invalidMint(claim, new AtomicInteger());
                    assertThrows(SessionException.class, lease::close);
                } finally { claim.confirmNativeStopped(); }
            }
        }
    }

    @Test void closedLeaseAndInvalidGenerationCannotMint() throws Exception {
        ProfileStore.initialize(workspace, "PUBLIC");
        var closed = ProfileStore.acquire(workspace, "PUBLIC");
        closed.close(); closed.close();
        assertThrows(SessionException.class, () -> closed.claimNative(GENERATION));
        try (var lease = ProfileStore.acquire(workspace, "PUBLIC")) {
            assertThrows(SessionException.class, () -> lease.claimNative(null));
            assertThrows(SessionException.class, () -> lease.claimNative(GENERATION));
        }
    }

    @Test void changedProfileDirectoryRefusesBeforeMintAndRestorationDoesNotRearmClaim() throws Exception {
        ProfileStore.initialize(workspace, "PUBLIC");
        try (var lease = ProfileStore.acquire(workspace, "PUBLIC")) {
            var claim = lease.claimNative(GENERATION);
            try {
                Files.move(workspace.resolve("PUBLIC"), workspace.resolve("PUBLIC_HELD"));
                directory(workspace.resolve("PUBLIC"));
                invalidMint(claim, new AtomicInteger());
                Files.move(workspace.resolve("PUBLIC"), workspace.resolve("PUBLIC_REPLACEMENT"));
                Files.move(workspace.resolve("PUBLIC_HELD"), workspace.resolve("PUBLIC"));
                invalidMint(claim, new AtomicInteger());
                kernelLockHeld(workspace, "PUBLIC");
            } finally { claim.confirmNativeStopped(); }
        }
    }

    @Test void renamedProfileBeforeClaimIsNotAClaimOnTheReplacementDirectory() throws Exception {
        ProfileStore.initialize(workspace, "PUBLIC");
        try (var lease = ProfileStore.acquire(workspace, "PUBLIC")) {
            Files.move(workspace.resolve("PUBLIC"), workspace.resolve("PUBLIC_HELD"));
            ProfileStore.initialize(workspace, "PUBLIC");
            assertThrows(SessionException.class, () -> lease.claimNative(GENERATION));
        }
    }

    @Test void changedWorkspaceDirectoryRefusesBeforeMint() throws Exception {
        Path outer = directory(workspace.resolve("outer"));
        Path root = directory(outer.resolve("workspace"));
        ProfileStore.initialize(root, "PUBLIC");
        try (var lease = ProfileStore.acquire(root, "PUBLIC")) {
            var claim = lease.claimNative(GENERATION);
            try {
                Files.move(root, outer.resolve("held"));
                directory(root);
                invalidMint(claim, new AtomicInteger());
                Files.move(root, outer.resolve("replacement"));
                Files.move(outer.resolve("held"), root);
                invalidMint(claim, new AtomicInteger());
            } finally { claim.confirmNativeStopped(); }
        }
    }

    @Test void workspaceSymlinkSubstitutionRefusesBeforeMint() throws Exception {
        Path outer = directory(workspace.resolve("outer"));
        Path root = directory(outer.resolve("workspace"));
        ProfileStore.initialize(root, "PUBLIC");
        try (var lease = ProfileStore.acquire(root, "PUBLIC")) {
            var claim = lease.claimNative(GENERATION);
            try {
                Files.move(root, outer.resolve("held"));
                Files.createSymbolicLink(root, Path.of("held"));
                invalidMint(claim, new AtomicInteger());
            } finally { claim.confirmNativeStopped(); }
        }
    }

    @Test void changedModeMetadataAndLockInodeRefuseBeforeMintWithoutRepair() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            String name = "PUBLIC_" + mode;
            ProfileStore.initialize(workspace, name);
            Path profile = workspace.resolve(name);
            try (var lease = ProfileStore.acquire(workspace, name)) {
                var claim = lease.claimNative(GENERATION);
                try {
                    if (mode == 0) {
                        Files.setPosixFilePermissions(profile, PosixFilePermissions.fromString("rwxr-x---"));
                    } else if (mode == 1) {
                        Files.writeString(profile.resolve("profile.json"), "{\"schemaVersion\":2}\n");
                    } else {
                        Files.move(profile.resolve("profile.lock"), profile.resolve("PUBLIC_HELD_LOCK"));
                        Files.createFile(profile.resolve("profile.lock"),
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    }
                    invalidMint(claim, new AtomicInteger());
                    if (mode == 0) {
                        assertEquals(PosixFilePermissions.fromString("rwxr-x---"), Files.getPosixFilePermissions(profile));
                    } else if (mode == 1) {
                        assertEquals("{\"schemaVersion\":2}\n", Files.readString(profile.resolve("profile.json")));
                    } else { assertTrue(Files.exists(profile.resolve("PUBLIC_HELD_LOCK"))); }
                } finally { claim.confirmNativeStopped(); }
            }
        }
    }

    @Test void anotherThreadCannotCloseLeaseDuringMintAndCannotCloseItAfterMintWithoutStopProof() throws Exception {
        ProfileStore.initialize(workspace, "PUBLIC");
        try (var lease = ProfileStore.acquire(workspace, "PUBLIC")) {
            var claim = lease.claimNative(GENERATION);
            var entered = new CountDownLatch(1);
            var finish = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var mint = executor.submit(() -> claim.mint(identity -> {
                    entered.countDown();
                    try { if (!finish.await(5, TimeUnit.SECONDS)) { throw new SessionException(SessionException.Code.START_FAILED); } }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new SessionException(SessionException.Code.INTERRUPTED); }
                    return 41;
                }));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var closeStarted = new CountDownLatch(1);
                var close = executor.submit(() -> { closeStarted.countDown(); return assertThrows(SessionException.class, lease::close); });
                assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
                assertFalse(close.isDone());
                finish.countDown();
                assertEquals(41L, mint.get(5, TimeUnit.SECONDS));
                assertEquals(SessionException.Code.CLOSE_FAILED, close.get(5, TimeUnit.SECONDS).code());
            } finally { finish.countDown(); claim.confirmNativeStopped(); }
        }
    }
}
