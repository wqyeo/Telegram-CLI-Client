package com.gitlab.wqyeo.telegramcli.session;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;

class SecretTransferTest {
    @Test void boundedTypedBootstrapRoundTripCannotConsumeFollowingLifecycleFrame() throws Exception {
        UUID generation = UUID.randomUUID();
        var output = new ByteArrayOutputStream();
        try (var original = CredentialEnvelopeTest.material()) {
            SecretTransfer.write(output, generation, original);
            assertEquals(108, output.size());
            SessionProtocol.write(output, new SessionProtocol.Frame(1, generation, 1, SessionState.Signal.CLOSE_REQUESTED));
            var input = new ByteArrayInputStream(output.toByteArray());
            try (var received = SecretTransfer.read(input, generation)) { assertArrayEquals(original.encode(), received.encode()); }
            assertEquals(SessionState.Signal.CLOSE_REQUESTED, SessionProtocol.read(input).signal());
            assertNull(SessionProtocol.read(input));
        }
    }
    @Test void rejectsWrongGenerationEveryTruncationUnknownKindsVersionsAndRequestIds() throws Exception {
        UUID generation = UUID.randomUUID(); var output = new ByteArrayOutputStream();
        try (var original = CredentialEnvelopeTest.material()) { SecretTransfer.write(output, generation, original); }
        byte[] good = output.toByteArray();
        invalid(good, UUID.randomUUID());
        for (int size = 0; size < good.length; size++) { invalid(Arrays.copyOf(good, size), generation); }
        for (int offset : new int[] {4, 12, 16, 32}) {
            byte[] bad = good.clone(); bad[offset] ^= 1; invalid(bad, generation);
        }
        for (int length : new int[] {-1, 0, 103, 105, Integer.MAX_VALUE}) {
            byte[] bad = good.clone(); ByteBuffer.wrap(bad).putInt(length); invalid(bad, generation);
        }
    }
    @Test void malformedCredentialPayloadAndIoErrorsHaveNoRawCause() throws Exception {
        UUID generation = UUID.randomUUID(); var output = new ByteArrayOutputStream();
        try (var original = CredentialEnvelopeTest.material()) { SecretTransfer.write(output, generation, original); }
        byte[] bad = output.toByteArray(); bad[44] = 'X'; invalid(bad, generation);
        try (var original = CredentialEnvelopeTest.material()) {
            var error = assertThrows(SessionException.class, () -> SecretTransfer.write(new OutputStream() {
                @Override public void write(int value) throws IOException { throw new IOException("SYNTHETIC_DO_NOT_PRINT"); }
            }, generation, original));
            assertNull(error.getCause()); assertFalse(error.toString().contains("DO_NOT_PRINT"));
        }
    }
    @Test void defaultBuildFailsBeforeProfileOrSecretSideEffects() throws Exception {
        if (!SessionWorker.integrated()) {
            var error = assertThrows(SessionException.class, () -> SessionWorker.startAccountFree(null, null, "DO_NOT_PRINT"));
            assertEquals(SessionException.Code.NATIVE_NOT_BUILT, error.code()); assertNull(error.getCause());
        } else {
            assertEquals(SessionException.Code.BUNDLE_INVALID,
                    assertThrows(SessionException.class, () -> SessionWorker.startAccountFree(null, null, "DO_NOT_PRINT")).code());
        }
    }
    private static void invalid(byte[] bytes, UUID generation) {
        var error = assertThrows(SessionException.class, () -> SecretTransfer.read(new ByteArrayInputStream(bytes), generation));
        assertEquals(SessionException.Code.PROTOCOL_INVALID, error.code()); assertNull(error.getCause());
        assertFalse(error.toString().contains("DO_NOT_PRINT"));
    }
}
