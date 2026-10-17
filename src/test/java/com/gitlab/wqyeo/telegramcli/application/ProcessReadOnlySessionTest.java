package com.gitlab.wqyeo.telegramcli.application;

import static org.junit.jupiter.api.Assertions.*;
import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import com.gitlab.wqyeo.telegramcli.session.bridge.*;
import com.gitlab.wqyeo.telegramcli.tui.*;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.*;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class ProcessReadOnlySessionTest {
    private BridgeCoordinator.Snapshot await(ProcessReadOnlySession s, Predicate<BridgeCoordinator.Snapshot> condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do { var state = s.tick(); if (condition.test(state)) { return state; } Thread.sleep(5); } while (System.nanoTime() < end);
        fail("PUBLIC process fixture did not reach expected state"); return null;
    }
    private void authorize(ProcessReadOnlySession session) throws Exception {
        for (int n = 0; n < 5; n++) {
            var before = await(session, s -> s.auth() != null && s.auth().step().input() && s.pendingRequests() == 0);
            session.advanceAuthorization();
            await(session, s -> s.auth() != null && !s.auth().token().equals(before.auth().token()));
        }
        await(session, s -> s.connection() == Phase.READY && s.pendingRequests() == 0 && s.chats().size() == 20 && s.messages().size() == 10);
    }
    @Test void privateAuthTypedPagesRefreshAndIdleUseActualChildAndMatchInMemoryPublicRows() throws Exception {
        var child = PublicProcessHarness.launch(SyntheticReadOnlySession.Scenario.FULL, "NORMAL");
        var s = new ProcessReadOnlySession(child, UUID.randomUUID(), ProcessBridgeTransport.Budgets.defaults());
        try (s; var memory = new SyntheticReadOnlySession()) {
            authorize(s); for (int n = 0; n < 5; n++) { memory.advanceAuthorization(); }
            assertEquals(memory.latest().chats(), s.latest().chats()); assertEquals(memory.latest().messages(), s.latest().messages());
            var stable = s.latest(); assertSame(stable, s.tick()); assertSame(stable, s.tick());
            assertTrue(s.moreChats()); await(s, x -> x.pendingRequests() == 0); assertEquals(40, s.latest().chats().size());
            s.savedPage(s.latest().nextMessages()); await(s, x -> x.pendingRequests() == 0); assertEquals(90, s.latest().messages().getLast().id());
            s.refresh(); await(s, x -> x.pendingRequests() == 0); assertEquals(20, s.latest().chats().size()); assertEquals(100, s.latest().messages().getLast().id());
            assertTrue(child.isAlive()); assertFalse(s.peerCleanlyClosed());
        }
        assertTrue(s.peerCleanlyClosed()); assertFalse(child.isAlive()); assertTrue(s.latest().disposed()); assertTrue(s.latest().messages().isEmpty());
    }
    @Test void slowPagesDoNotCommitNavigationEarlyOrBlockQuit() throws Exception {
        var child = PublicProcessHarness.launch(SyntheticReadOnlySession.Scenario.FULL, "SLOW_DATA");
        try (var s = new ProcessReadOnlySession(child, UUID.randomUUID(), ProcessBridgeTransport.Budgets.defaults())) {
            authorize(s); var ui = new ReadOnlyController(s); ui.handle(new KeyStroke(KeyType.Tab));
            ui.handle(new KeyStroke('n', false, false)); assertEquals(0, ui.paginationDepth());
            ui.handle(new KeyStroke('n', false, false)); assertEquals(1, s.latest().pendingRequests());
            await(s, x -> x.pendingRequests() == 0); ui.sync(); assertEquals(1, ui.paginationDepth()); assertEquals(90, s.latest().messages().getLast().id());
            ui.handle(new KeyStroke('b', false, false)); await(s, x -> x.pendingRequests() == 0); ui.sync(); assertEquals(0, ui.paginationDepth());
            ui.handle(new KeyStroke('n', false, false)); long start = System.nanoTime(); ui.handle(new KeyStroke('q', false, false));
            assertFalse(ui.running()); assertTrue(System.nanoTime() - start < Duration.ofMillis(100).toNanos());
        }
    }
    @Test void invalidatedAsynchronousPageCannotAdvanceNavigationEvenWithoutAnError() throws Exception {
        var child = PublicProcessHarness.launch(SyntheticReadOnlySession.Scenario.FULL, "INVALIDATE_PAGE");
        try (var s = new ProcessReadOnlySession(child, UUID.randomUUID(), ProcessBridgeTransport.Budgets.defaults())) {
            authorize(s); var ui = new ReadOnlyController(s); ui.handle(new KeyStroke(KeyType.Tab));
            ui.handle(new KeyStroke('n', false, false)); await(s, x -> x.pendingRequests() == 0); ui.sync();
            assertNull(s.latest().lastError()); assertEquals(100, s.latest().messages().getLast().id());
            assertEquals(0, ui.paginationDepth(), "Invalidated request is not a successful older-page response");
        }
    }
    @Test void sameExistingGuideAndFullscreenRunAgainstProcessWithoutCollectingInput() throws Exception {
        var child = PublicProcessHarness.launch(SyntheticReadOnlySession.Scenario.FULL, "NORMAL");
        var s = new ProcessReadOnlySession(child, UUID.randomUUID(), ProcessBridgeTransport.Budgets.defaults());
        var terminal = new DefaultVirtualTerminal(new TerminalSize(100, 30));
        int[] frames = {0};
        assertEquals(0, new ReadOnlyTuiApplication().run(terminal, s, () -> {
            frames[0]++;
            var state = s.latest();
            if (state.auth() != null && state.auth().step().input() && state.pendingRequests() == 0) { terminal.addInput(new KeyStroke(KeyType.Enter)); }
            if (state.chats().size() == 20 && state.messages().size() == 10) { terminal.addInput(new KeyStroke(KeyType.EOF)); }
            if (frames[0] > 200) { fail("Shared frontend did not finish PUBLIC process guide"); }
        }));
        assertTrue(frames[0] > 5); assertTrue(s.peerCleanlyClosed()); assertFalse(child.isAlive());
    }
    @Test void retryRequiresExplicitNewAttemptAfterDelayAndUnsupportedStaysUnsupported() throws Exception {
        for (var scenario : new SyntheticReadOnlySession.Scenario[] {SyntheticReadOnlySession.Scenario.RETRY, SyntheticReadOnlySession.Scenario.UNSUPPORTED}) {
            var child = PublicProcessHarness.launch(scenario, "NORMAL");
            try (var s = new ProcessReadOnlySession(child, UUID.randomUUID(), ProcessBridgeTransport.Budgets.defaults())) {
                await(s, x -> x.auth() != null); s.advanceAuthorization();
                if (scenario == SyntheticReadOnlySession.Scenario.RETRY) {
                    await(s, x -> x.auth().step() == Step.PHONE && x.retryAfterSeconds() > 0 && x.pendingRequests() == 0);
                    assertEquals(BridgeException.Code.TIMEOUT, assertThrows(BridgeException.class, s::advanceAuthorization).code());
                    await(s, x -> x.retryAfterSeconds() == 0); s.advanceAuthorization();
                    await(s, x -> x.auth().step() == Step.CODE); assertTrue(s.latest().chats().isEmpty());
                } else {
                    await(s, x -> x.auth().step() == Step.CODE); s.advanceAuthorization();
                    await(s, x -> x.auth().step() == Step.UNSUPPORTED); assertTrue(s.latest().chats().isEmpty());
                }
            }
        }
    }
    @Test void childCrashPurgesDataAndNeverReportsPeerOrNativeEngineClose() throws Exception {
        var child = PublicProcessHarness.launch(SyntheticReadOnlySession.Scenario.FULL, "NORMAL");
        try (var s = new ProcessReadOnlySession(child, UUID.randomUUID(), ProcessBridgeTransport.Budgets.defaults())) {
            authorize(s); child.destroyForcibly(); child.waitFor();
            assertThrows(BridgeException.class, () -> await(s, x -> false));
            assertTrue(s.latest().disposed()); assertTrue(s.latest().messages().isEmpty()); assertNull(s.latest().identity()); assertFalse(s.peerCleanlyClosed());
        }
    }
}
