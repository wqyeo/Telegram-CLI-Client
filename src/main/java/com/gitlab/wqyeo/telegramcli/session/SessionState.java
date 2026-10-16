package com.gitlab.wqyeo.telegramcli.session;

import java.util.Objects;
import java.util.UUID;

/** Application-owned, immutable and secret-free. Separate from demo message state. */
public record SessionState(UUID generation, long sequence, Lifecycle lifecycle,
                           Authorization authorization, Connection connection, boolean engineClosed) {
    public enum Lifecycle { STARTING, RUNNING, CLOSING, CLOSED, FAILED }
    public enum Authorization { NONE, PARAMETERS_REQUIRED, PHONE_REQUIRED, EMAIL_ADDRESS_REQUIRED,
        EMAIL_CODE_REQUIRED, CODE_REQUIRED, OTHER_DEVICE_REQUIRED, REGISTRATION_REQUIRED,
        PASSWORD_REQUIRED, READY, LOGGING_OUT, UNSUPPORTED }
    public enum Connection { OFFLINE, CONNECTING, CONNECTED }
    public enum Signal { STARTED, PARAMETERS_REQUIRED, PHONE_REQUIRED, EMAIL_ADDRESS_REQUIRED,
        EMAIL_CODE_REQUIRED, CODE_REQUIRED, OTHER_DEVICE_REQUIRED, REGISTRATION_REQUIRED,
        PASSWORD_REQUIRED, AUTHORIZED, LOGGING_OUT, UNSUPPORTED_AUTHORIZATION,
        CONNECTING, CONNECTED, DISCONNECTED, CLOSE_REQUESTED, ENGINE_CLOSED, ENGINE_FAILED }

    public SessionState {
        Objects.requireNonNull(generation); Objects.requireNonNull(lifecycle);
        Objects.requireNonNull(authorization); Objects.requireNonNull(connection);
        if (sequence < 0) { throw new IllegalArgumentException("Invalid session sequence."); }
    }

    public static SessionState initial(UUID generation) {
        return new SessionState(generation, 0, Lifecycle.STARTING, Authorization.NONE, Connection.OFFLINE, false);
    }
}
