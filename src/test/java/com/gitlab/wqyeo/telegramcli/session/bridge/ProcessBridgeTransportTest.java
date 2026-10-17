package com.gitlab.wqyeo.telegramcli.session.bridge;

import static org.junit.jupiter.api.Assertions.*;
import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import com.gitlab.wqyeo.telegramcli.application.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ProcessBridgeTransportTest {
    private static final ProcessBridgeTransport.Budgets SHORT = new ProcessBridgeTransport.Budgets(
            Duration.ofSeconds(3), Duration.ofMillis(300), Duration.ofMillis(400), Duration.ofSeconds(1));
    private Process launch(String mode) throws Exception { return PublicProcessHarness.launch(SyntheticReadOnlySession.Scenario.FULL, mode); }
    private ProcessBridgeTransport attach(Process p, UUID gen, BridgeCoordinator c) throws Exception {
        return ProcessBridgeTransport.attachPublicFixture(p, gen, c::offer, SHORT);
    }
    @Test void helloNormalCloseNeedsCorrelatedAckEofZeroExitAndCompletedReaders() throws Exception {
        var gen = UUID.randomUUID(); var c = new BridgeCoordinator(gen); Process child = launch("NORMAL");
        try (var transport = attach(child, gen, c)) {
            assertTrue(child.isAlive()); assertFalse(transport.cleanlyClosed());
            transport.closeUsing(() -> c.control(Action.CLOSE, 0));
            assertTrue(transport.cleanlyClosed()); assertFalse(child.isAlive()); assertEquals(0, child.exitValue());
            transport.closeUsing(() -> { fail("Close must be idempotent"); return null; });
        }
    }
    @Test void malformedEofDuplicateAndUnsolicitedCloseNeverEstablishCleanClosure() throws Exception {
        for (String mode : List.of("EOF", "MALFORMED", "DUPLICATE", "UNSOLICITED_CLOSE", "FLOOD")) {
            var gen = UUID.randomUUID(); var c = new BridgeCoordinator(gen); Process child = launch(mode);
            ProcessBridgeTransport t = null;
            try {
                t = attach(child, gen, c); var transport = t;
                assertThrows(BridgeException.class, () -> {
                    long end = System.nanoTime() + Duration.ofSeconds(4).toNanos();
                    while (System.nanoTime() < end) { transport.check(); TimeUnit.MILLISECONDS.sleep(5); }
                    fail("Fixture must fail without consumer drain");
                });
                assertFalse(t.cleanlyClosed());
            } catch (BridgeException expectedStartupFailure) { assertFalse(child.isAlive()); }
            finally { if (t != null) { t.close(); } child.destroyForcibly(); assertTrue(child.waitFor(2, TimeUnit.SECONDS)); }
        }
    }
    @Test void missingWrongNonzeroTrailingOrHangingCloseIsNotReportedAsClosed() throws Exception {
        for (String mode : List.of("CLOSE_NOACK", "CLOSE_WRONGID", "CLOSE_NONZERO", "CLOSE_TRAILING", "CLOSE_HANG")) {
            var gen = UUID.randomUUID(); var c = new BridgeCoordinator(gen); Process child = launch(mode);
            try (var transport = attach(child, gen, c)) {
                assertThrows(BridgeException.class, () -> transport.closeUsing(() -> c.control(Action.CLOSE, 0)));
                assertFalse(transport.cleanlyClosed()); assertFalse(child.isAlive());
            }
        }
    }
    @Test void startupTimeoutReapsOwnedChildAndPreservesFixedDiagnostics() throws Exception {
        Process child = launch("START_HANG");
        var tiny = new ProcessBridgeTransport.Budgets(Duration.ofMillis(200), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
        var failure = assertThrows(BridgeException.class, () -> ProcessBridgeTransport.attachPublicFixture(child, UUID.randomUUID(), frame -> {}, tiny));
        assertEquals(BridgeException.Code.TIMEOUT, failure.code()); assertFalse(child.isAlive());
        assertEquals("Bridge operation failed: TIMEOUT.", failure.getMessage()); assertNull(failure.getCause());
    }
    @Test void staleGenerationDoesNotAdvanceCurrentSequenceAndHumanIdleHasNoReplyTimeout() throws Exception {
        var gen = UUID.randomUUID(); var c = new BridgeCoordinator(gen); Process child = launch("STALE");
        try (var transport = attach(child, gen, c)) {
            long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (c.latest().auth() == null && System.nanoTime() < end) { transport.check(); c.drain(); TimeUnit.MILLISECONDS.sleep(5); }
            assertEquals(1, c.latest().sequence()); assertEquals(Step.PHONE, c.latest().auth().step());
            TimeUnit.MILLISECONDS.sleep(450); transport.check(); assertTrue(child.isAlive());
            transport.closeUsing(() -> c.control(Action.CLOSE, 0)); assertTrue(transport.cleanlyClosed());
        }
    }
    @Test void blockedChildInputHasBoundedPressureAndNoUiThreadWrite() throws Exception {
        var gen = UUID.randomUUID(); var c = new BridgeCoordinator(gen); Process child = launch("NO_READ");
        try (var transport = attach(child, gen, c)) {
            byte[] fixture = new byte[AuthSubmission.MAX_BYTES]; Arrays.fill(fixture, (byte) 'P');
            long start = System.nanoTime();
            assertThrows(BridgeException.class, () -> {
                for (int n = 1; n <= 256; n++) {
                    try (var submission = AuthSubmission.copyOf(gen, n, UUID.randomUUID(), Step.PASSWORD, fixture)) { transport.authenticate(submission); }
                }
            });
            assertTrue(System.nanoTime() - start < Duration.ofSeconds(2).toNanos());
            assertTrue(transport.pendingWrites() <= ProcessBridgeTransport.WRITE_CAPACITY);
            transport.close(); assertFalse(child.isAlive()); assertFalse(transport.cleanlyClosed());
            Arrays.fill(fixture, (byte) 0);
        }
    }
    @Test void pacedBlockedWritesExpireWithoutNeedingQueueOverflow() throws Exception {
        var gen = UUID.randomUUID(); var c = new BridgeCoordinator(gen); Process child = launch("NO_READ");
        try (var transport = attach(child, gen, c)) {
            byte[] fixture = new byte[AuthSubmission.MAX_BYTES]; Arrays.fill(fixture, (byte) 'P');
            try {
                var failure = assertThrows(BridgeException.class, () -> {
                    long end = System.nanoTime() + Duration.ofSeconds(4).toNanos(); long request = 0;
                    while (System.nanoTime() < end) {
                        transport.check();
                        if (transport.pendingWrites() < 8) {
                            try (var submission = AuthSubmission.copyOf(gen, ++request, UUID.randomUUID(), Step.PASSWORD, fixture)) { transport.authenticate(submission); }
                        }
                        TimeUnit.MILLISECONDS.sleep(5);
                    }
                    fail("Blocked writer must expire its own write budget");
                });
                assertEquals(BridgeException.Code.TIMEOUT, failure.code()); transport.close(); assertFalse(child.isAlive());
            } finally { Arrays.fill(fixture, (byte) 0); }
        }
    }
    @Test void ignoredTermWithBlockedPipeStillForciblyStopsWithinCleanupBudget() throws Exception {
        var gen = UUID.randomUUID(); var c = new BridgeCoordinator(gen); Process child = launch("NO_READ_TERM");
        var budgets = new ProcessBridgeTransport.Budgets(Duration.ofSeconds(3), Duration.ofMillis(300), Duration.ofMillis(400), Duration.ofMillis(200));
        try (var transport = ProcessBridgeTransport.attachPublicFixture(child, gen, c::offer, budgets)) {
            byte[] fixture = new byte[AuthSubmission.MAX_BYTES]; Arrays.fill(fixture, (byte) 'P');
            try {
                assertThrows(BridgeException.class, () -> {
                    long end = System.nanoTime() + Duration.ofSeconds(4).toNanos(); long request = 0;
                    while (System.nanoTime() < end) {
                        transport.check();
                        if (transport.pendingWrites() < 8) {
                            try (var submission = AuthSubmission.copyOf(gen, ++request, UUID.randomUUID(), Step.PASSWORD, fixture)) { transport.authenticate(submission); }
                        }
                        TimeUnit.MILLISECONDS.sleep(5);
                    }
                    fail("Expected bounded write failure");
                });
                transport.close(); assertFalse(child.isAlive(), "Pipe closure must not block escalation to SIGKILL");
            } finally { Arrays.fill(fixture, (byte) 0); }
        } finally { child.toHandle().destroyForcibly(); assertTrue(child.waitFor(2, TimeUnit.SECONDS)); }
    }
    private void await(ProcessBridgeTransport transport, BridgeCoordinator c, java.util.function.Predicate<BridgeCoordinator.Snapshot> condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        do { transport.check(); c.drain(); if (condition.test(c.latest())) { return; } TimeUnit.MILLISECONDS.sleep(5); } while (System.nanoTime() < end);
        fail("PUBLIC subprocess state deadline");
    }
    @Test void canceledAndTimedOutPagesCannotBeRestoredByRealQueuedPipeReplies() throws Exception {
        var gen = UUID.randomUUID(); var nanos = new java.util.concurrent.atomic.AtomicLong();
        var c = new BridgeCoordinator(gen, java.time.Clock.systemUTC(), nanos::get); Process child = launch("SLOW_DATA");
        try (var transport = attach(child, gen, c)) {
            for (int n = 0; n < 5; n++) {
                await(transport, c, x -> x.auth() != null && x.auth().step().input() && x.pendingRequests() == 0);
                var token = c.latest().auth().token();
                byte[] fixture = ("PUBLIC_SYNTHETIC_" + c.latest().auth().step().name()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                try (var submission = c.authenticate(fixture)) { transport.authenticate(submission); }
                finally { Arrays.fill(fixture, (byte) 0); }
                await(transport, c, x -> !x.auth().token().equals(token));
            }
            await(transport, c, x -> x.identity() != null && x.connection() == Phase.READY);
            Frame request = c.request(Target.SAVED_MESSAGES, 0, MESSAGE_PAGE); long sequence = c.latest().sequence();
            transport.send(request); transport.send(c.control(Action.CANCEL, request.requestId()));
            await(transport, c, x -> x.sequence() > sequence); assertTrue(c.latest().messages().isEmpty()); assertEquals(0, c.latest().pendingRequests());
            transport.send(c.control(Action.RESYNC, 0)); Frame second = c.request(Target.SAVED_MESSAGES, 0, MESSAGE_PAGE);
            transport.send(second); long previous = c.latest().sequence(); nanos.set(Duration.ofSeconds(11).toNanos()); c.drain();
            await(transport, c, x -> x.sequence() >= previous + 3); // RESYNC connection pair, then late page.
            assertTrue(c.latest().messages().isEmpty()); assertEquals(0, c.latest().pendingRequests()); assertEquals(BridgeData.Error.NOT_AVAILABLE, c.latest().lastError());
            transport.closeUsing(() -> c.control(Action.CLOSE, 0)); assertTrue(transport.cleanlyClosed());
        }
    }
    @Test void parentPipeLossProducesNonzeroExitNotFakeCloseAck() throws Exception {
        Process child = launch("NORMAL"); UUID gen = UUID.randomUUID();
        BridgePipeProtocol.write(child.getOutputStream(), new BridgePipeProtocol.Control(BridgePipeProtocol.Kind.HELLO, gen, 0));
        assertEquals(BridgePipeProtocol.Kind.HELLO, BridgePipeProtocol.read(child.getInputStream()).kind());
        assertNotNull(BridgeProtocol.read(child.getInputStream())); child.getOutputStream().close();
        assertTrue(child.waitFor(3, TimeUnit.SECONDS)); assertNotEquals(0, child.exitValue());
        assertEquals(-1, child.getInputStream().read());
    }
}
