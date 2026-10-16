package com.gitlab.wqyeo.telegramcli.session;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import static com.gitlab.wqyeo.telegramcli.session.SessionState.Signal.*;

/** Test fixture, never compiled into the application JAR. No TDLib or network use. */
public final class SyntheticSessionWorker {
    private static void forever() throws InterruptedException { new CountDownLatch(1).await(); }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) { System.exit(2); }
        Path workspace = Path.of(arguments[0]);
        String profile = arguments[1];
        UUID generation = UUID.fromString(arguments[2]);
        String mode = arguments[3];
        if (mode.equals("PARENT")) {
            Process child = WorkerHarness.launch(workspace, profile, generation, "HANG");
            SessionProtocol.read(child.getInputStream()); // lock-held handshake
            System.out.println(child.pid()); System.out.flush();
            // Outer test deliberately kills this parent; its worker must retain the lease.
            forever(); return;
        }
        if (mode.equals("ENVIRONMENT") && (!System.getenv().keySet().equals(java.util.Set.of("LC_ALL"))
                || System.getProperty("injected.marker") != null)) { System.exit(4); }
        try (var lease = ProfileStore.acquire(workspace, profile)) {
            if (mode.equals("NO_START")) { forever(); }
            if (mode.equals("HANG") || mode.equals("ACK_HANG")) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { forever(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }));
            }
            long sequence = 1;
            SessionProtocol.write(System.out, new SessionProtocol.Frame(1, generation, sequence++, STARTED));
            switch (mode) {
                case "EXIT" -> { return; }
                case "CRASH" -> { Runtime.getRuntime().halt(7); }
                case "HANG" -> forever();
                case "OVERSIZED" -> {
                    System.out.write(ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE).array()); System.out.flush(); forever();
                }
                case "PARTIAL_BODY" -> {
                    System.out.write(ByteBuffer.allocate(4).putInt(100).array()); System.out.flush(); forever();
                }
                case "TRUNCATED" -> { System.out.write(new byte[] {0, 0}); System.out.flush(); return; }
                case "MALFORMED" -> {
                    byte[] marker = "SYNTHETIC_DO_NOT_PRINT".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    System.out.write(ByteBuffer.allocate(4).putInt(marker.length).array()); System.out.write(marker); System.out.flush(); return;
                }
                case "FLOOD" -> {
                    while (true) {
                        SessionProtocol.write(System.out, new SessionProtocol.Frame(1, generation, sequence++, CONNECTED));
                    }
                }
                case "STALE" -> SessionProtocol.write(System.out, new SessionProtocol.Frame(1, UUID.randomUUID(), 999, ENGINE_FAILED));
                default -> { }
            }
            SessionProtocol.write(System.out, new SessionProtocol.Frame(1, generation, sequence++, CODE_REQUIRED));
            SessionProtocol.write(System.out, new SessionProtocol.Frame(1, generation, sequence++, CONNECTED));
            SessionProtocol.Frame request = SessionProtocol.read(System.in);
            if (request != null && (!request.generation().equals(generation) || request.sequence() != 1 || request.signal() != CLOSE_REQUESTED)) {
                System.exit(5);
            }
            // Boundary EOF from a disappeared parent also requests cooperative closure.
            SessionProtocol.write(System.out, new SessionProtocol.Frame(1, generation, sequence++, CLOSE_REQUESTED));
            SessionProtocol.write(System.out, new SessionProtocol.Frame(1, generation, sequence, ENGINE_CLOSED));
            if (mode.equals("ACK_HANG")) { forever(); }
            if (mode.equals("BAD_EXIT_AFTER_CLOSE")) { Runtime.getRuntime().halt(8); }
            if (mode.equals("NULL_AFTER_CLOSE")) {
                System.out.write(ByteBuffer.allocate(4).putInt(4).array());
                System.out.write("null".getBytes(java.nio.charset.StandardCharsets.UTF_8)); System.out.flush();
            }
        }
    }
}
