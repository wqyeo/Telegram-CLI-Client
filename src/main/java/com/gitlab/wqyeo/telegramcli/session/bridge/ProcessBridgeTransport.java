package com.gitlab.wqyeo.telegramcli.session.bridge;

import static com.gitlab.wqyeo.telegramcli.session.bridge.BridgeData.*;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded pipe supervisor. Owns an already-launched PUBLIC fixture process, never launches a native client.
 * No command/provider/vault/credential selection. Child fixture entry points stay in test sources.
 */
public final class ProcessBridgeTransport implements AutoCloseable {
    public static final int WRITE_CAPACITY = 16;
    @FunctionalInterface public interface Sink { void offer(Frame frame) throws BridgeException; }
    @FunctionalInterface public interface Closing { Frame create() throws BridgeException; }
    public record Budgets(Duration startup, Duration write, Duration closing, Duration cleanup) {
        public Budgets {
            for (var value : new Duration[] {startup, write, closing, cleanup}) {
                if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofMinutes(1)) > 0) {
                    throw new IllegalArgumentException("Invalid pipe budget.");
                }
            }
        }
        public static Budgets defaults() { return new Budgets(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(2)); }
    }
    private record Write(byte[] bytes, long started, boolean last) {}
    private final Object monitor = new Object();
    private final Process process;
    private final UUID generation;
    private final Sink sink;
    private final Budgets budgets;
    private final ArrayDeque<Write> queue = new ArrayDeque<>();
    private final Thread reader, writer, watchdog;
    private Write active;
    private BridgeException.Code failure;
    private boolean ready, closing, ack, eof, readerDone, writerDone, stopped, clean;
    private long incoming, closeRequest, closeStarted;

    private ProcessBridgeTransport(Process process, UUID generation, Sink sink, Budgets budgets) {
        this.process = Objects.requireNonNull(process); this.generation = Objects.requireNonNull(generation);
        this.sink = Objects.requireNonNull(sink); this.budgets = Objects.requireNonNull(budgets);
        reader = Thread.ofVirtual().name("typed-pipe-reader").unstarted(this::readLoop);
        writer = Thread.ofVirtual().name("typed-pipe-writer").unstarted(this::writeLoop);
        watchdog = Thread.ofVirtual().name("typed-pipe-watchdog").unstarted(this::watchLoop);
    }
    /** Ownership transfers here; handshake/startup failure also terminates and reaps the child. */
    public static ProcessBridgeTransport attachPublicFixture(Process process, UUID generation, Sink sink, Budgets budgets) throws BridgeException {
        var result = new ProcessBridgeTransport(process, generation, sink, budgets);
        result.reader.start(); result.writer.start(); result.watchdog.start();
        try {
            try (var buffer = new WipingBuffer()) {
                BridgePipeProtocol.write(buffer, new BridgePipeProtocol.Control(BridgePipeProtocol.Kind.HELLO, generation, 0));
                result.enqueue(buffer.toByteArray(), false);
            }
            long start = System.nanoTime();
            synchronized (result.monitor) {
                while (!result.ready && result.failure == null) {
                    long remaining = budgets.startup().toNanos() - (System.nanoTime() - start);
                    if (remaining <= 0) { result.failLocked(BridgeException.Code.TIMEOUT); break; }
                    TimeUnit.NANOSECONDS.timedWait(result.monitor, remaining);
                }
                result.checkLocked();
            }
            return result;
        } catch (InterruptedException failure) {
            result.fail(BridgeException.Code.CANCELLED); result.close(); Thread.currentThread().interrupt();
            throw new BridgeException(BridgeException.Code.CANCELLED);
        } catch (IOException | RuntimeException failure) {
            result.fail(failure instanceof BridgeException value ? value.code() : BridgeException.Code.PROTOCOL_INVALID);
            result.close(); throw new BridgeException(result.failureCode());
        }
    }
    public void check() throws BridgeException { synchronized (monitor) { checkLocked(); } }
    public boolean cleanlyClosed() { synchronized (monitor) { return clean && failure == null; } }
    public int pendingWrites() { synchronized (monitor) { return queue.size() + (active == null ? 0 : 1); } }
    public long pid() { return process.pid(); }
    private BridgeException.Code failureCode() { synchronized (monitor) { return failure == null ? BridgeException.Code.STATE_INVALID : failure; } }

    /** Only bounded application requests/control, never arbitrary SDK/response dispatch. */
    public void send(Frame frame) throws BridgeException {
        if (frame == null || !generation.equals(frame.generation()) || !(frame.payload() instanceof Request || frame.payload() instanceof Control)
                || frame.payload() instanceof Control value && value.action() == Action.CLOSE) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
        try (var buffer = new WipingBuffer()) { BridgeProtocol.write(buffer, frame); enqueue(buffer.toByteArray(), false); }
    }
    /** Serialize before returning; caller may immediately dispose its submission. Owned queued bytes are wiped. */
    public void authenticate(AuthSubmission submission) throws BridgeException {
        if (submission == null || !generation.equals(submission.generation())) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
        try (var buffer = new WipingBuffer()) { AuthTransfer.write(buffer, submission); enqueue(buffer.toByteArray(), false); }
    }
    private void enqueue(byte[] bytes, boolean last) throws BridgeException {
        synchronized (monitor) {
            try {
                checkLocked();
                if (closing && !last || pendingWrites() >= WRITE_CAPACITY) {
                    failLocked(closing ? BridgeException.Code.STATE_INVALID : BridgeException.Code.OVERFLOW); checkLocked();
                }
                queue.addLast(new Write(bytes, System.nanoTime(), last)); monitor.notifyAll();
            } catch (BridgeException failure) { Arrays.fill(bytes, (byte) 0); throw failure; }
        }
    }
    /** Ingress offer and logical disposal are serialized; queued auth is never resent. */
    public void closeUsing(Closing command) throws BridgeException {
        try {
            synchronized (monitor) {
                if (clean) { return; }
                checkLocked();
                if (!closing) {
                    closing = true; closeStarted = System.nanoTime();
                    Frame frame = command.create();
                    if (!generation.equals(frame.generation()) || !(frame.payload() instanceof Control control) || control.action() != Action.CLOSE) {
                        throw new BridgeException(BridgeException.Code.STATE_INVALID);
                    }
                    closeRequest = frame.requestId();
                    try (var buffer = new WipingBuffer()) { BridgeProtocol.write(buffer, frame); enqueue(buffer.toByteArray(), true); }
                }
                while (!clean && failure == null) { monitor.wait(); }
                checkLockedAfterClose();
            }
        } catch (InterruptedException failure) {
            fail(BridgeException.Code.CANCELLED); Thread.currentThread().interrupt();
            throw new BridgeException(BridgeException.Code.CANCELLED);
        } catch (IOException | RuntimeException failure) {
            fail(failure instanceof BridgeException value ? value.code() : BridgeException.Code.PROTOCOL_INVALID);
            throw new BridgeException(failureCode());
        } finally { close(); }
        synchronized (monitor) { checkLockedAfterClose(); }
    }
    private void checkLockedAfterClose() throws BridgeException {
        if (failure != null || !clean) { throw new BridgeException(failure == null ? BridgeException.Code.STATE_INVALID : failure); }
    }
    private void readLoop() {
        try (var input = new PushbackInputStream(process.getInputStream(), 8)) {
            var hello = BridgePipeProtocol.read(input);
            if (hello.kind() != BridgePipeProtocol.Kind.HELLO || !generation.equals(hello.generation())) { throw invalid(); }
            synchronized (monitor) { ready = true; monitor.notifyAll(); }
            int stale = 0;
            BridgePipeProtocol.Namespace namespace;
            while ((namespace = BridgePipeProtocol.peek(input)) != null) {
                if (namespace == BridgePipeProtocol.Namespace.PIPE) {
                    var control = BridgePipeProtocol.read(input);
                    synchronized (monitor) {
                        if (!closing || ack || control.kind() != BridgePipeProtocol.Kind.CLOSED || !generation.equals(control.generation()) || control.requestId() != closeRequest) { throw invalid(); }
                        ack = true; monitor.notifyAll();
                    }
                } else if (namespace == BridgePipeProtocol.Namespace.TYPED) {
                    Frame frame = BridgeProtocol.read(input);
                    synchronized (monitor) {
                        if (ack || frame == null || frame.payload() instanceof Request || frame.payload() instanceof Control) { throw invalid(); }
                        if (!generation.equals(frame.generation())) {
                            if (++stale > BridgeCoordinator.QUEUE_CAPACITY) { throw new BridgeException(BridgeException.Code.OVERFLOW); }
                            continue;
                        }
                        if (frame.sequence() != Math.addExact(incoming, 1)) { throw invalid(); }
                        incoming = frame.sequence();
                        if (!closing && failure == null) { sink.offer(frame); }
                    }
                } else { throw invalid(); } // Private auth is parent-to-child only; never a return link channel.
            }
            synchronized (monitor) { eof = true; if (!closing || !ack) { failLocked(BridgeException.Code.PROTOCOL_INVALID); } monitor.notifyAll(); }
        } catch (IOException | RuntimeException failure) {
            fail(failure instanceof BridgeException value ? value.code() : BridgeException.Code.PROTOCOL_INVALID);
        } finally { synchronized (monitor) { readerDone = true; monitor.notifyAll(); } }
    }
    private void writeLoop() {
        try {
            while (true) {
                Write job;
                synchronized (monitor) {
                    while (queue.isEmpty() && !stopped && failure == null) { monitor.wait(); }
                    if (stopped || failure != null) { return; }
                    active = job = queue.removeFirst(); monitor.notifyAll();
                }
                try {
                    process.getOutputStream().write(job.bytes()); process.getOutputStream().flush();
                } finally { Arrays.fill(job.bytes(), (byte) 0); synchronized (monitor) { active = null; monitor.notifyAll(); } }
                if (job.last()) { process.getOutputStream().close(); return; }
            }
        } catch (InterruptedException failure) { fail(BridgeException.Code.CANCELLED); Thread.currentThread().interrupt(); }
        catch (IOException | RuntimeException failure) { fail(BridgeException.Code.PROTOCOL_INVALID); }
        finally { synchronized (monitor) { writerDone = true; monitor.notifyAll(); } }
    }
    private void watchLoop() {
        try {
            process.onExit().thenRun(() -> { synchronized (monitor) { monitor.notifyAll(); } });
            long exited = 0;
            synchronized (monitor) {
                while (failure == null) {
                    if (closing && eof && readerDone && writerDone && !process.isAlive()) {
                        if (!ack || process.exitValue() != 0) { failLocked(BridgeException.Code.PROTOCOL_INVALID); break; }
                        clean = true; stopped = true; monitor.notifyAll(); return;
                    }
                    long now = System.nanoTime(), remaining = Long.MAX_VALUE;
                    Write first = active == null ? queue.peekFirst() : active;
                    if (first != null) { remaining = budgets.write().toNanos() - (now - first.started()); }
                    if (closing) { remaining = Math.min(remaining, budgets.closing().toNanos() - (now - closeStarted)); }
                    if (!process.isAlive()) {
                        if (exited == 0) { exited = now; }
                        remaining = Math.min(remaining, budgets.cleanup().toNanos() - (now - exited));
                    }
                    if (remaining <= 0) { failLocked(BridgeException.Code.TIMEOUT); break; }
                    if (remaining == Long.MAX_VALUE) { monitor.wait(); } else { TimeUnit.NANOSECONDS.timedWait(monitor, remaining); }
                }
            }
        } catch (InterruptedException failure) { fail(BridgeException.Code.CANCELLED); Thread.currentThread().interrupt(); }
        finally {
            if (!cleanlyClosed()) { terminate(); }
            synchronized (monitor) { stopped = true; wipeQueue(); monitor.notifyAll(); }
        }
    }
    private void terminate() {
        boolean interrupted = Thread.interrupted();
        try {
            // Process.destroy() also closes Java streams and can block behind our pipe writer.
            // Signal the OS handle without touching streams; escalation must remain reachable.
            process.toHandle().destroy();
            if (!process.waitFor(budgets.cleanup().toNanos(), TimeUnit.NANOSECONDS)) {
                process.toHandle().destroyForcibly(); process.waitFor(budgets.cleanup().toNanos(), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException failure) { interrupted = true; process.toHandle().destroyForcibly(); }
        finally { if (interrupted) { Thread.currentThread().interrupt(); } }
    }
    private void checkLocked() throws BridgeException {
        if (failure != null) { throw new BridgeException(failure); }
        if (stopped) { throw new BridgeException(BridgeException.Code.STATE_INVALID); }
    }
    private void fail(BridgeException.Code code) { synchronized (monitor) { failLocked(code); } }
    private void failLocked(BridgeException.Code code) { if (failure == null) { failure = code; } wipeQueue(); monitor.notifyAll(); }
    private void wipeQueue() { while (!queue.isEmpty()) { Arrays.fill(queue.removeFirst().bytes(), (byte) 0); } }
    /** Abort on failure/disposal. Never manufactures peer-close success; joins have one shared cleanup budget. */
    @Override public void close() {
        synchronized (monitor) { if (!clean) { failLocked(BridgeException.Code.CANCELLED); } }
        boolean interrupted = Thread.interrupted();
        long start = System.nanoTime(), budget = budgets.cleanup().multipliedBy(3).toNanos();
        try {
            for (var thread : new Thread[] {watchdog, writer, reader}) {
                if (thread == Thread.currentThread()) { continue; }
                long remaining = budget - (System.nanoTime() - start);
                if (remaining <= 0 || !thread.join(Duration.ofNanos(remaining))) { fail(BridgeException.Code.TIMEOUT); break; }
            }
            if (!process.isAlive() && !reader.isAlive() && !writer.isAlive()) {
                process.getOutputStream().close(); process.getInputStream().close(); process.getErrorStream().close();
            } else { fail(BridgeException.Code.TIMEOUT); }
        } catch (InterruptedException failure) { interrupted = true; fail(BridgeException.Code.CANCELLED); }
        catch (IOException failure) { fail(BridgeException.Code.PROTOCOL_INVALID); }
        finally { if (interrupted) { Thread.currentThread().interrupt(); } }
    }
    private static BridgeException invalid() { return new BridgeException(BridgeException.Code.PROTOCOL_INVALID); }
    @JsonValue public String redacted() { return "[REDACTED process bridge]"; }
    @Override public String toString() { return redacted(); }
    private static final class WipingBuffer extends ByteArrayOutputStream {
        @Override public void close() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
