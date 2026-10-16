package com.gitlab.wqyeo.telegramcli.session;

import static org.junit.jupiter.api.Assertions.*;
import static com.gitlab.wqyeo.telegramcli.session.SessionState.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(20)
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
class SessionSupervisorTest {
    @TempDir Path workspace;
    private final AtomicReference<Process> worker = new AtomicReference<>();
    private final SessionSupervisor.Deadlines deadlines = new SessionSupervisor.Deadlines(
            Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(300));

    @BeforeEach void initialize() throws Exception { ProfileStore.initialize(workspace, "default"); }
    @AfterEach void stopOwnedFixture() throws Exception {
        Process process = worker.get();
        if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
    }
    private SessionSupervisor start(String mode) throws SessionException {
        return start(mode, deadlines);
    }
    private SessionSupervisor start(String mode, SessionSupervisor.Deadlines limits) throws SessionException {
        return SessionSupervisor.start(generation -> {
            Process process = WorkerHarness.launch(workspace, "default", generation, mode);
            worker.set(process); return process;
        }, limits);
    }
    private SessionState ready(SessionSupervisor supervisor) throws Exception {
        return supervisor.await(state -> state.connection() == Connection.CONNECTED,
                Duration.ofSeconds(5), SessionException.Code.START_FAILED);
    }
    private SessionProtocol.Frame readFrame(Process process) throws Exception {
        var result = new FutureTask<>(() -> SessionProtocol.read(process.getInputStream()));
        Thread.ofVirtual().start(result);
        return result.get(5, TimeUnit.SECONDS);
    }
    @Test void startMeansLeaseHeldAndNormalCloseMeansAcknowledgedSuccessfulExit() throws Exception {
        var supervisor = start("NORMAL");
        var running = ready(supervisor);
        assertEquals(Authorization.CODE_REQUIRED, running.authorization());
        assertEquals(SessionException.Code.PROFILE_BUSY,
                assertThrows(SessionException.class, () -> ProfileStore.acquire(workspace, "default")).code());
        for (int i = 0; i < 10_000; i++) { assertSame(running, supervisor.latest()); }
        supervisor.close(); supervisor.close();
        assertEquals(Lifecycle.CLOSED, supervisor.latest().lifecycle());
        assertTrue(supervisor.latest().engineClosed()); assertTrue(supervisor.failureCode().isEmpty());
        assertFalse(worker.get().isAlive()); assertEquals(0, worker.get().exitValue());
        try (var lease = ProfileStore.acquire(workspace, "default")) { assertNotNull(lease); }
        try (var paths = Files.walk(workspace)) {
            assertEquals(4, paths.count()); // workspace, profile, metadata, lock; no DB or secret file
        }
    }
    @Test void immediateCloseDrainsAlreadyBufferedStartupEvents() throws Exception {
        var supervisor = start("NORMAL"); supervisor.close();
        assertEquals(Lifecycle.CLOSED, supervisor.latest().lifecycle());
    }
    @ParameterizedTest @ValueSource(strings = {"STALE", "ENVIRONMENT"})
    void staleFramesDoNotMutateSessionAndLauncherDoesNotInheritEnvironment(String mode) throws Exception {
        try (var supervisor = start(mode)) {
            var state = ready(supervisor);
            assertEquals(3, state.sequence()); assertEquals(Authorization.CODE_REQUIRED, state.authorization());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"EXIT", "CRASH", "MALFORMED", "TRUNCATED", "OVERSIZED"})
    void workerFailureEofAndBadProtocolNeverBecomeCleanClosure(String mode) throws Exception {
        SessionSupervisor supervisor;
        try { supervisor = start(mode); }
        catch (SessionException exception) {
            assertEquals(SessionException.Code.START_FAILED, exception.code());
            assertFalse(worker.get().isAlive()); return;
        }
        var exception = assertThrows(SessionException.class, () -> supervisor.await(state -> state.lifecycle() == Lifecycle.CLOSED,
                Duration.ofSeconds(5), SessionException.Code.WORKER_FAILED));
        assertFalse(exception.toString().contains("SYNTHETIC_DO_NOT_PRINT")); assertNull(exception.getCause());
        assertEquals(Lifecycle.FAILED, supervisor.latest().lifecycle());
        assertThrows(SessionException.class, supervisor::close);
        assertFalse(worker.get().isAlive());
    }
    @ParameterizedTest @ValueSource(strings = {"HANG", "ACK_HANG", "FLOOD"})
    void closureDeadlineAndForcedTerminationRemainExplicitFailure(String mode) throws Exception {
        var limits = new SessionSupervisor.Deadlines(Duration.ofSeconds(5), Duration.ofMillis(250), Duration.ofMillis(200));
        var supervisor = start(mode, limits);
        if (!mode.equals("HANG")) { ready(supervisor); }
        var exception = assertThrows(SessionException.class, supervisor::close);
        assertEquals(SessionException.Code.CLOSE_FAILED, exception.code());
        assertEquals(Lifecycle.FAILED, supervisor.latest().lifecycle());
        assertFalse(worker.get().isAlive());
        if (mode.equals("ACK_HANG")) { assertTrue(supervisor.latest().engineClosed()); }
        try (var recovered = ProfileStore.acquire(workspace, "default")) { assertNotNull(recovered); }
    }
    @ParameterizedTest @ValueSource(strings = {"NULL_AFTER_CLOSE", "BAD_EXIT_AFTER_CLOSE", "PARTIAL_BODY"})
    void invalidTrailingFrameBadExitAndBlockedFrameCannotProveCleanClosure(String mode) throws Exception {
        var supervisor = start(mode);
        assertThrows(SessionException.class, supervisor::close);
        assertEquals(Lifecycle.FAILED, supervisor.latest().lifecycle());
        assertFalse(worker.get().isAlive());
    }
    @Test void startupDeadlineTerminatesUnacknowledgedWorker() {
        var limits = new SessionSupervisor.Deadlines(Duration.ofMillis(150), Duration.ofSeconds(1), Duration.ofMillis(200));
        assertEquals(SessionException.Code.START_FAILED,
                assertThrows(SessionException.class, () -> start("NO_START", limits)).code());
        assertFalse(worker.get().isAlive());
    }
    @Test void startRefusesAlreadyOwnedProfileAndMissingWorkspaceWithoutCreatingIt() throws Exception {
        try (var lease = ProfileStore.acquire(workspace, "default")) {
            assertThrows(SessionException.class, () -> start("NORMAL"));
        }
        Path missing = workspace.resolve("DO_NOT_PRINT");
        var exception = assertThrows(SessionException.class, () -> SessionSupervisor.start(generation -> {
            Process process = WorkerHarness.launch(missing, "default", generation, "NORMAL"); worker.set(process); return process;
        }, deadlines));
        assertFalse(exception.toString().contains("DO_NOT_PRINT")); assertFalse(Files.exists(missing));
    }
    @Test void interruptionDuringClosureIsPreservedAndCannotReportClosed() throws Exception {
        var supervisor = start("HANG");
        try {
            Thread.currentThread().interrupt();
            assertEquals(SessionException.Code.INTERRUPTED, assertThrows(SessionException.class, supervisor::close).code());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(Lifecycle.FAILED, supervisor.latest().lifecycle()); assertFalse(worker.get().isAlive());
        } finally { Thread.interrupted(); }
    }
    @Test void interruptionDuringStartupCleansUpAndPreservesInterruptFlag() {
        try {
            Thread.currentThread().interrupt();
            assertEquals(SessionException.Code.INTERRUPTED, assertThrows(SessionException.class, () -> start("NO_START")).code());
            assertTrue(Thread.currentThread().isInterrupted()); assertFalse(worker.get().isAlive());
        } finally { Thread.interrupted(); }
    }
    @Test void parentPipeEofRequestsCooperativeCloseAndReleasesLease() throws Exception {
        UUID generation = UUID.randomUUID();
        Process process = WorkerHarness.launch(workspace, "default", generation, "NORMAL"); worker.set(process);
        assertEquals(Signal.STARTED, readFrame(process).signal());
        assertEquals(Signal.CODE_REQUIRED, readFrame(process).signal());
        assertEquals(Signal.CONNECTED, readFrame(process).signal());
        process.getOutputStream().close();
        assertEquals(Signal.CLOSE_REQUESTED, readFrame(process).signal());
        assertEquals(Signal.ENGINE_CLOSED, readFrame(process).signal());
        assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        try (var lease = ProfileStore.acquire(workspace, "default")) { assertNotNull(lease); }
    }
    @Test void killingParentDoesNotReleaseAStillRunningWorkersLease() throws Exception {
        Process parent = WorkerHarness.launch(workspace, "default", UUID.randomUUID(), "PARENT"); worker.set(parent);
        ProcessHandle child = null;
        try {
            var pidResult = new FutureTask<>(() -> new BufferedReader(new InputStreamReader(parent.getInputStream())).readLine());
            Thread.ofVirtual().start(pidResult);
            long pid = Long.parseLong(pidResult.get(5, TimeUnit.SECONDS));
            child = parent.descendants().filter(value -> value.pid() == pid).findFirst().orElseThrow();
            parent.destroyForcibly(); assertTrue(parent.waitFor(5, TimeUnit.SECONDS));
            assertTrue(child.isAlive());
            assertEquals(SessionException.Code.PROFILE_BUSY,
                    assertThrows(SessionException.class, () -> ProfileStore.acquire(workspace, "default")).code());
            child.destroyForcibly(); child.onExit().get(5, TimeUnit.SECONDS);
            try (var lease = ProfileStore.acquire(workspace, "default")) { assertNotNull(lease); }
        } finally {
            if (child != null && child.isAlive()) { child.destroyForcibly(); }
            parent.destroyForcibly();
        }
    }
    @Test void deadlinesAndLauncherFailuresHaveFixedDiagnostics() {
        assertThrows(IllegalArgumentException.class, () -> new SessionSupervisor.Deadlines(Duration.ZERO, Duration.ZERO, Duration.ZERO));
        var exception = assertThrows(SessionException.class, () -> SessionSupervisor.start(generation -> {
            throw new IOException("DO_NOT_PRINT");
        }, deadlines));
        assertEquals(SessionException.Code.START_FAILED, exception.code()); assertNull(exception.getCause());
        assertFalse(exception.toString().contains("DO_NOT_PRINT"));
        assertThrows(SessionException.class, () -> SessionSupervisor.start(generation -> {
            fail("Invalid deadlines must be refused before launching a process."); return null;
        }, null));
        assertThrows(SessionException.class, () -> SessionSupervisor.start(null, deadlines));
    }
}
