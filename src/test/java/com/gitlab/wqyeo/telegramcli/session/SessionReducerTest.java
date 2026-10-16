package com.gitlab.wqyeo.telegramcli.session;

import static org.junit.jupiter.api.Assertions.*;
import static com.gitlab.wqyeo.telegramcli.session.SessionState.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionReducerTest {
    private SessionState next(SessionState state, Signal signal) throws SessionException {
        return SessionReducer.apply(state, new SessionProtocol.Frame(1, state.generation(), state.sequence() + 1, signal));
    }
    @Test void modelsAuthorizationConnectionAndLifecycleIndependently() throws Exception {
        var initial = SessionState.initial(UUID.randomUUID());
        var state = next(initial, Signal.STARTED);
        for (Signal signal : new Signal[] {Signal.PARAMETERS_REQUIRED, Signal.PHONE_REQUIRED, Signal.EMAIL_ADDRESS_REQUIRED,
                Signal.EMAIL_CODE_REQUIRED, Signal.CODE_REQUIRED, Signal.OTHER_DEVICE_REQUIRED, Signal.REGISTRATION_REQUIRED,
                Signal.PASSWORD_REQUIRED, Signal.UNSUPPORTED_AUTHORIZATION}) { state = next(state, signal); }
        assertEquals(Authorization.UNSUPPORTED, state.authorization());
        state = next(state, Signal.CONNECTED);
        assertNotEquals(Authorization.READY, state.authorization());
        state = next(state, Signal.AUTHORIZED);
        state = next(state, Signal.DISCONNECTED);
        assertEquals(Authorization.READY, state.authorization());
        assertEquals(Connection.OFFLINE, state.connection());
        state = next(state, Signal.CONNECTING); state = next(state, Signal.CONNECTED);
        state = SessionReducer.requestClose(state);
        state = next(state, Signal.ENGINE_CLOSED);
        assertEquals(Lifecycle.CLOSING, state.lifecycle());
        assertTrue(state.engineClosed());
        state = SessionReducer.terminated(state, 0);
        assertEquals(Lifecycle.CLOSED, state.lifecycle());
        assertEquals(Authorization.NONE, state.authorization());
        assertEquals(Lifecycle.STARTING, initial.lifecycle());
        assertEquals(0, initial.sequence());
    }
    @Test void staleGenerationCannotAdvanceSequenceOrMutateState() throws Exception {
        var state = next(SessionState.initial(UUID.randomUUID()), Signal.STARTED);
        assertSame(state, SessionReducer.apply(state, new SessionProtocol.Frame(1, UUID.randomUUID(), Long.MAX_VALUE, Signal.ENGINE_FAILED)));
    }
    @Test void duplicateGapAndInvalidTransitionsFailRatherThanDisappear() throws Exception {
        var initial = SessionState.initial(UUID.randomUUID());
        assertThrows(SessionException.class, () -> next(initial, Signal.AUTHORIZED));
        var running = next(initial, Signal.STARTED);
        assertThrows(SessionException.class, () -> next(running, Signal.STARTED));
        assertThrows(SessionException.class, () -> next(running, Signal.ENGINE_CLOSED));
        assertThrows(SessionException.class, () -> SessionReducer.apply(running,
                new SessionProtocol.Frame(1, running.generation(), 1, Signal.STARTED)));
        assertThrows(SessionException.class, () -> SessionReducer.apply(running,
                new SessionProtocol.Frame(1, running.generation(), 3, Signal.CONNECTED)));
        var closing = SessionReducer.requestClose(running);
        assertEquals(Lifecycle.CLOSING, next(closing, Signal.AUTHORIZED).lifecycle());
        assertThrows(SessionException.class, () -> SessionReducer.terminated(closing, 0));
        var acknowledged = next(closing, Signal.ENGINE_CLOSED);
        assertThrows(SessionException.class, () -> SessionReducer.terminated(acknowledged, 1));
        assertThrows(SessionException.class, () -> next(acknowledged, Signal.CONNECTED));
        var closed = SessionReducer.terminated(acknowledged, 0);
        assertSame(closed, SessionReducer.requestClose(closed));
        assertThrows(SessionException.class, () -> next(closed, Signal.STARTED));
    }
    @Test void failuresAreTerminalAndNeverMasqueradeAsCleanClosure() throws Exception {
        var failed = next(SessionState.initial(UUID.randomUUID()), Signal.ENGINE_FAILED);
        assertEquals(Lifecycle.FAILED, failed.lifecycle()); assertFalse(failed.engineClosed());
        assertSame(failed, SessionReducer.requestClose(failed));
        assertThrows(SessionException.class, () -> next(failed, Signal.STARTED));
        assertEquals(Lifecycle.FAILED, SessionReducer.failed(failed).lifecycle());
    }
}
