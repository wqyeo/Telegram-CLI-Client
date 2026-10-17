package com.gitlab.wqyeo.telegramcli.session.bridge;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BridgePipeProtocolTest {
    @Test void fixedPublicModeControlRoundTripsAndNoOtherModeIsAccepted() throws Exception {
        var generation = UUID.randomUUID(); var out = new ByteArrayOutputStream();
        var value = new BridgePipeProtocol.Control(BridgePipeProtocol.Kind.CLOSED, generation, 3);
        BridgePipeProtocol.write(out, value); byte[] bytes = out.toByteArray();
        assertEquals(34, bytes.length); assertEquals(value, BridgePipeProtocol.read(new ByteArrayInputStream(bytes)));
        bytes[8] = 2; assertThrows(BridgeException.class, () -> BridgePipeProtocol.read(new ByteArrayInputStream(bytes)));
    }
    @Test void everyTruncatedControlAndUnknownKindMagicOrRequestFailsClosed() throws Exception {
        var out = new ByteArrayOutputStream();
        BridgePipeProtocol.write(out, new BridgePipeProtocol.Control(BridgePipeProtocol.Kind.HELLO, UUID.randomUUID(), 0));
        byte[] bytes = out.toByteArray();
        for (int n = 0; n < bytes.length; n++) {
            byte[] truncated = Arrays.copyOf(bytes, n);
            assertThrows(BridgeException.class, () -> BridgePipeProtocol.read(new ByteArrayInputStream(truncated)));
        }
        for (int index : new int[] {0, 9, 33}) {
            byte[] bad = bytes.clone(); bad[index] = 99;
            assertThrows(BridgeException.class, () -> BridgePipeProtocol.read(new ByteArrayInputStream(bad)));
        }
    }
    @Test void demuxHasFixedLookaheadAndOnlyBoundaryEofIsClean() throws Exception {
        for (var magic : Map.of("TGCPIP01", BridgePipeProtocol.Namespace.PIPE, "TGCBRG01", BridgePipeProtocol.Namespace.TYPED,
                "TGCAUT01", BridgePipeProtocol.Namespace.AUTH).entrySet()) {
            try (var input = new PushbackInputStream(new ByteArrayInputStream(magic.getKey().getBytes(java.nio.charset.StandardCharsets.US_ASCII)), 8)) {
                assertEquals(magic.getValue(), BridgePipeProtocol.peek(input)); assertEquals('T', input.read());
            }
        }
        assertNull(BridgePipeProtocol.peek(new PushbackInputStream(InputStream.nullInputStream(), 8)));
        assertThrows(BridgeException.class, () -> BridgePipeProtocol.peek(new PushbackInputStream(new ByteArrayInputStream(new byte[7]), 8)));
        assertThrows(BridgeException.class, () -> BridgePipeProtocol.peek(new PushbackInputStream(new ByteArrayInputStream(new byte[8]), 8)));
    }
}
