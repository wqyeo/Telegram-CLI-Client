package com.gitlab.wqyeo.telegramcli.session;

import static com.gitlab.wqyeo.telegramcli.session.SessionState.*;

/** Stateless transition rules; supervisor applies frames serially on one reader. */
public final class SessionReducer {
    private SessionReducer() {}

    public static SessionState apply(SessionState state, SessionProtocol.Frame frame) throws SessionException {
        if (!state.generation().equals(frame.generation())) { return state; }
        if (state.sequence() == Long.MAX_VALUE || frame.sequence() != state.sequence() + 1
                || state.lifecycle() == Lifecycle.CLOSED || state.lifecycle() == Lifecycle.FAILED || state.engineClosed()) {
            throw invalid();
        }
        Lifecycle lifecycle = state.lifecycle();
        Authorization authorization = state.authorization();
        Connection connection = state.connection();
        boolean closed = false;
        if (frame.signal() == Signal.STARTED) {
            if (lifecycle != Lifecycle.STARTING) { throw invalid(); }
            lifecycle = Lifecycle.RUNNING;
        } else if (frame.signal() == Signal.ENGINE_FAILED) {
            lifecycle = Lifecycle.FAILED;
            connection = Connection.OFFLINE;
            authorization = Authorization.NONE;
        } else if (frame.signal() == Signal.CLOSE_REQUESTED) {
            if (lifecycle != Lifecycle.RUNNING && lifecycle != Lifecycle.CLOSING) { throw invalid(); }
            lifecycle = Lifecycle.CLOSING;
        } else if (frame.signal() == Signal.ENGINE_CLOSED) {
            if (lifecycle != Lifecycle.CLOSING) { throw invalid(); }
            closed = true;
            connection = Connection.OFFLINE;
            authorization = Authorization.NONE;
        } else {
            // Already-buffered updates can precede the close acknowledgment. Drain
            // them in order without returning to RUNNING or accepting new requests.
            if (lifecycle != Lifecycle.RUNNING && lifecycle != Lifecycle.CLOSING) { throw invalid(); }
            switch (frame.signal()) {
                case CONNECTING -> connection = Connection.CONNECTING;
                case CONNECTED -> connection = Connection.CONNECTED;
                case DISCONNECTED -> connection = Connection.OFFLINE;
                case AUTHORIZED -> authorization = Authorization.READY;
                case UNSUPPORTED_AUTHORIZATION -> authorization = Authorization.UNSUPPORTED;
                default -> authorization = Authorization.valueOf(frame.signal().name());
            }
        }
        return new SessionState(state.generation(), frame.sequence(), lifecycle, authorization, connection, closed);
    }

    public static SessionState requestClose(SessionState state) {
        if (state.lifecycle() == Lifecycle.CLOSED || state.lifecycle() == Lifecycle.FAILED) { return state; }
        return new SessionState(state.generation(), state.sequence(), Lifecycle.CLOSING,
                state.authorization(), state.connection(), state.engineClosed());
    }

    /** A closed acknowledgment alone is insufficient; call only after successful exit. */
    public static SessionState terminated(SessionState state, int exitCode) throws SessionException {
        if (state.lifecycle() != Lifecycle.CLOSING || !state.engineClosed() || exitCode != 0) { throw invalid(); }
        return new SessionState(state.generation(), state.sequence(), Lifecycle.CLOSED,
                Authorization.NONE, Connection.OFFLINE, true);
    }

    public static SessionState failed(SessionState state) {
        return new SessionState(state.generation(), state.sequence(), Lifecycle.FAILED,
                Authorization.NONE, Connection.OFFLINE, state.engineClosed());
    }

    private static SessionException invalid() { return new SessionException(SessionException.Code.PROTOCOL_INVALID); }
}
