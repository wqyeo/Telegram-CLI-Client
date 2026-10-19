package com.gitlab.wqyeo.telegramcli.application;

import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import com.gitlab.wqyeo.telegramcli.session.bridge.*;
import java.io.*;
import java.time.Clock;
import java.util.*;

/** Test-only executable PUBLIC peer. No provider/storage/SDK/client/network API; stdout is binary only. */
public final class PublicBridgeProcess {
    public static void main(String[] args) {
        int status = 0;
        try {
            if (args.length != 2) { throw new IllegalArgumentException(); }
            var scenario = SyntheticReadOnlySession.Scenario.valueOf(args[0]); String mode = args[1];
            if (mode.equals("NO_READ_TERM")) { sun.misc.Signal.handle(new sun.misc.Signal("TERM"), signal -> {}); }
            if (mode.equals("START_HANG")) { Thread.sleep(30_000); return; }
            var input = new PushbackInputStream(System.in, 8);
            var hello = BridgePipeProtocol.read(input);
            if (hello.kind() != BridgePipeProtocol.Kind.HELLO) { throw new IllegalArgumentException(); }
            UUID generation = hello.generation();
            boolean[] announced = {false};
            try (var initial = new InitialFrames();
                 var peer = new SyntheticBridgePeer(generation, Clock.systemUTC(), System::nanoTime,
                    frame -> {
                        OutputStream output = announced[0] ? System.out : initial;
                        BridgeProtocol.write(output, frame);
                        if (mode.equals("DUPLICATE")) { BridgeProtocol.write(output, frame); }
                    }, scenario, mode.startsWith("READER") ? 2 : 1)) {
                // Prepare the actual peer and initial typed bytes under STARTUP, before HELLO
                // lets a parent immediately start its separate Close budget. Never put typed
                // bytes on the pipe before HELLO, or warm up by issuing dummy requests.
                peer.start();
                BridgePipeProtocol.write(System.out, hello); announced[0] = true;
                switch (mode) {
                    case "EOF" -> { return; }
                    case "MALFORMED" -> { System.out.write("TGCBRG01".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); System.out.flush(); return; }
                    case "UNSOLICITED_CLOSE" -> { BridgePipeProtocol.write(System.out, new BridgePipeProtocol.Control(BridgePipeProtocol.Kind.CLOSED, generation, 1)); return; }
                    case "FLOOD" -> {
                        for (long i = 1; i < 100_000; i++) { BridgeProtocol.write(System.out, new Frame(generation, i, 0, new Auth(Step.PHONE, UUID.randomUUID(), 0, Unsupported.NONE))); }
                        return;
                    }
                    case "STALE" -> BridgeProtocol.write(System.out, new Frame(UUID.randomUUID(), 999, 0, new Connection(Phase.OFFLINE)));
                    default -> { }
                }
                initial.writeTo(System.out); initial.close(); System.out.flush();
                if (mode.equals("NO_READ") || mode.equals("NO_READ_TERM")) { Thread.sleep(30_000); return; }
                BridgePipeProtocol.Namespace namespace;
                while ((namespace = BridgePipeProtocol.peek(input)) != null) {
                    if (namespace == BridgePipeProtocol.Namespace.AUTH) { peer.authenticateFrom(input); }
                    else if (namespace == BridgePipeProtocol.Namespace.TYPED) {
                        Frame frame = BridgeProtocol.read(input);
                        if (frame == null) { throw new IllegalArgumentException(); }
                        if (frame.payload() instanceof Request && mode.equals("SLOW_DATA")) { Thread.sleep(150); }
                        if (mode.equals("INVALIDATE_PAGE") && frame.payload() instanceof Request request
                                && request.target() == Target.SAVED_MESSAGES && request.cursor() != 0) { peer.edit(100, "PUBLIC edited fixture", 0); }
                        peer.receive(frame);
                        if (mode.equals("READER_UPDATES") && frame.payload() instanceof HistoryRequest query && query.cursor() == 0) {
                            peer.readerUpdate(query.chatId(), 26, "PUBLIC process incoming", 0, Update.NEW);
                        }
                        if (frame.payload() instanceof Control value && value.action() == Action.CLOSE) {
                            if (mode.equals("CLOSE_HANG")) { Thread.sleep(30_000); return; }
                            if (!mode.equals("CLOSE_NOACK")) {
                                BridgePipeProtocol.write(System.out, new BridgePipeProtocol.Control(BridgePipeProtocol.Kind.CLOSED, generation,
                                        mode.equals("CLOSE_WRONGID") ? frame.requestId() + 1 : frame.requestId()));
                            }
                            if (mode.equals("CLOSE_TRAILING")) { System.out.write(1); System.out.flush(); }
                            if (mode.equals("CLOSE_NONZERO")) { status = 1; }
                            System.exit(status); return;
                        }
                    } else { throw new IllegalArgumentException(); }
                }
                status = 1; // Parent EOF is loss, never a successful peer-close acknowledgment.
            }
        } catch (Exception | LinkageError failure) { status = 1; }
        System.exit(status);
    }
    /** Only bounded PUBLIC initial metadata, never account input or an unbounded event queue. */
    private static final class InitialFrames extends ByteArrayOutputStream {
        @Override public void close() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
