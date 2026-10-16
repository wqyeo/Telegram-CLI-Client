package com.gitlab.wqyeo.telegramcli.tui;

import static org.junit.jupiter.api.Assertions.*;
import com.gitlab.wqyeo.telegramcli.application.SyntheticReadOnlySession;
import com.googlecode.lanterna.input.*;
import org.junit.jupiter.api.*;

class ReadOnlyControllerTest {
    private SyntheticReadOnlySession session;
    private ReadOnlyController ui;
    @BeforeEach void setup() throws Exception {
        session = new SyntheticReadOnlySession(); for (int i = 0; i < 5; i++) { session.advanceAuthorization(); }
        ui = new ReadOnlyController(session);
    }
    @AfterEach void close() { session.close(); }
    private void key(KeyType type) throws Exception { ui.handle(new KeyStroke(type)); }
    private void character(char c) throws Exception { ui.handle(new KeyStroke(c, false, false)); }

    @Test void f2DeleteAndTypedTextNeverProduceAComposerSendOrMutation() throws Exception {
        var activity = session.activity(); var messages = session.latest().messages();
        key(KeyType.F2); assertTrue(ui.status().contains("no composer or send"));
        key(KeyType.Enter); key(KeyType.Delete); key(KeyType.Backspace);
        for (char c : "xyz🙂".toCharArray()) { character(c); }
        assertEquals(activity, session.activity()); assertEquals(messages, session.latest().messages());
        assertArrayEquals(new ReadOnlyController.Focus[] {ReadOnlyController.Focus.CHATS, ReadOnlyController.Focus.HISTORY}, ReadOnlyController.Focus.values());
    }
    @Test void selectionUsesStableIdsAcrossAppendAndRefreshAndClampsWhenRemoved() throws Exception {
        key(KeyType.ArrowDown); long selected = ui.selectedId(); assertEquals(-1, selected);
        character('n'); assertEquals(40, ui.state().chats().size()); assertEquals(selected, ui.selectedId());
        character('r'); assertEquals(selected, ui.selectedId());
        character('n'); key(KeyType.End); assertEquals(-39, ui.selectedId());
        character('r'); assertEquals(42, ui.selectedId()); assertTrue(ui.savedSelected());
    }
    @Test void paginationIsExplicitBoundedAndReversibleWithoutRetainingWholePages() throws Exception {
        key(KeyType.Tab); character('n'); assertEquals(1, ui.paginationDepth()); assertEquals(90, ui.state().messages().getLast().id());
        character('b'); assertEquals(0, ui.paginationDepth()); assertEquals(100, ui.state().messages().getLast().id());
        for (int i = 0; i < 20; i++) { character('n'); assertTrue(ui.paginationDepth() <= 10); }
        assertEquals(1, ui.state().messages().getFirst().id()); int requests = session.activity().readRequests();
        character('n'); assertEquals(requests, session.activity().readRequests());
        character('r'); assertEquals(0, ui.paginationDepth()); assertEquals(100, ui.state().messages().getLast().id());
    }
    @Test void otherChatsAreListOnlyAndHaveNoHistoryDispatcher() throws Exception {
        key(KeyType.ArrowDown); key(KeyType.Enter); var activity = session.activity();
        assertFalse(ui.savedSelected()); character('n'); character('b'); key(KeyType.F2); key(KeyType.Delete);
        assertEquals(activity, session.activity()); key(KeyType.Escape); assertEquals(ReadOnlyController.Focus.CHATS, ui.focus());
    }
    @Test void helpAndEveryQuitPathAreSafeAndImmediateWithoutDiscardPrompts() throws Exception {
        key(KeyType.F1); assertTrue(ui.help()); var activity = session.activity(); character('n'); character('r'); key(KeyType.F2);
        assertEquals(activity, session.activity()); key(KeyType.Escape); assertFalse(ui.help());
        key(KeyType.F1); character('q'); assertFalse(ui.running());
        var eof = new ReadOnlyController(session); eof.handle(new KeyStroke(KeyType.EOF)); assertFalse(eof.running());
        var ctrl = new ReadOnlyController(session); ctrl.handle(new KeyStroke('c', true, false)); assertFalse(ctrl.running());
    }
    @Test void expiryPurgesSelectionPaginationAndDoesNotStartReauthorization() throws Exception {
        key(KeyType.Tab); character('n'); assertEquals(1, ui.paginationDepth());
        session.fixtureAuthorizationExpired(); ui.sync(); var activity = session.activity();
        assertEquals(0, ui.selectedId()); assertEquals(0, ui.paginationDepth()); assertTrue(ui.status().contains("expired"));
        character('r'); character('n'); key(KeyType.F2); assertEquals(activity, session.activity());
        assertTrue(ui.state().messages().isEmpty()); assertTrue(ui.status().contains("expired"));
    }
}
