package com.gitlab.wqyeo.telegramcli.session;

import static com.gitlab.wqyeo.telegramcli.session.SessionException.Code.*;
import static com.gitlab.wqyeo.telegramcli.session.SessionState.*;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Bounded supervision only. There is deliberately no production launcher yet.
 * The package-private launch seam is exercised by a test-only Java worker.
 */
public final class SessionSupervisor implements AutoCloseable {
    @FunctionalInterface interface Launcher { Process launch(UUID generation) throws IOException; }

    public record Deadlines(Duration startup, Duration closing, Duration cleanup) {
        public Deadlines {
            for (var duration : new Duration[] {startup, closing, cleanup}) {
                if (duration == null || duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(1)) > 0) {
                    throw new IllegalArgumentException("Invalid session deadline.");
                }
            }
        }
        public static Deadlines defaults() { return new Deadlines(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(2)); }
    }

    private final Object monitor = new Object();
    private final Process process;
    private final Deadlines deadlines;
    private final Thread reader;
    private volatile SessionState state;
    private volatile SessionException failure;
    private boolean closeSent;

    private SessionSupervisor(Process process, UUID generation, Deadlines deadlines) {
        this.process = process; this.deadlines = deadlines;
        state = SessionState.initial(generation);
        reader = Thread.ofVirtual().name("session-worker-reader").unstarted(this::readLoop);
        reader.start();
    }

    static SessionSupervisor start(Launcher launcher, Deadlines deadlines) throws SessionException {
        SessionSupervisor supervisor = null;
        try {
            java.util.Objects.requireNonNull(launcher);
            java.util.Objects.requireNonNull(deadlines);
            UUID generation = UUID.randomUUID();
            supervisor = new SessionSupervisor(launcher.launch(generation), generation, deadlines);
            supervisor.await(value -> value.lifecycle() != Lifecycle.STARTING, deadlines.startup(), START_FAILED);
            return supervisor;
        } catch (InterruptedException exception) {
            if (supervisor != null) { supervisor.fail(INTERRUPTED); supervisor.terminate(); }
            Thread.currentThread().interrupt();
            throw new SessionException(INTERRUPTED);
        } catch (IOException | RuntimeException exception) {
            if (supervisor != null) { supervisor.fail(START_FAILED); supervisor.terminate(); }
            throw new SessionException(START_FAILED);
        }
    }

    public SessionState latest() { return state; }
    public Optional<SessionException.Code> failureCode() {
        return failure == null ? Optional.empty() : Optional.of(failure.code());
    }

    /** Test/integration barrier; no snapshot task or notification queue is enqueued. */
    SessionState await(Predicate<SessionState> condition, Duration timeout, SessionException.Code timeoutCode)
            throws SessionException, InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (!condition.test(state) && failure == null) {
                long remaining = end - System.nanoTime();
                if (remaining <= 0) { throw new SessionException(timeoutCode); }
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
            }
            if (failure != null) { throw failure; }
            return state;
        }
    }

    private void readLoop() {
        try {
            SessionProtocol.Frame frame;
            while ((frame = SessionProtocol.read(process.getInputStream())) != null) {
                synchronized (monitor) {
                    state = SessionReducer.apply(state, frame);
                    if (state.lifecycle() == Lifecycle.FAILED) { throw new SessionException(WORKER_FAILED); }
                    monitor.notifyAll();
                }
            }
            // EOF is not proof of either engine closure or process termination.
            if (!process.waitFor(deadlines.cleanup().toNanos(), TimeUnit.NANOSECONDS)) { throw new SessionException(WORKER_FAILED); }
            synchronized (monitor) {
                state = SessionReducer.terminated(state, process.exitValue());
                monitor.notifyAll();
            }
        } catch (InterruptedException exception) {
            fail(INTERRUPTED);
            terminate();
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            fail(exception instanceof SessionException session ? session.code() : WORKER_FAILED);
            terminate();
        }
    }

    @Override public synchronized void close() throws SessionException {
        if (state.lifecycle() == Lifecycle.CLOSED) { return; }
        try {
            if (Thread.currentThread().isInterrupted()) { throw new InterruptedException(); }
            synchronized (monitor) {
                if (failure != null) { throw failure; }
                state = SessionReducer.requestClose(state);
            }
            if (!closeSent) {
                closeSent = true;
                // Exactly one small control frame; no user payload or unbounded writer queue.
                SessionProtocol.write(process.getOutputStream(), new SessionProtocol.Frame(1, state.generation(), 1, Signal.CLOSE_REQUESTED));
                process.getOutputStream().close();
            }
            await(value -> value.lifecycle() == Lifecycle.CLOSED, deadlines.closing(), CLOSE_FAILED);
            if (!reader.join(deadlines.cleanup())) { throw new SessionException(CLOSE_FAILED); }
        } catch (InterruptedException exception) {
            fail(INTERRUPTED);
            terminate();
            Thread.currentThread().interrupt();
            throw new SessionException(INTERRUPTED);
        } catch (IOException | RuntimeException exception) {
            fail(CLOSE_FAILED);
            terminate();
            throw new SessionException(CLOSE_FAILED);
        }
    }

    private void fail(SessionException.Code code) {
        synchronized (monitor) {
            // Preserve the first diagnostic without copying raw worker/parser exceptions.
            if (failure == null) { failure = new SessionException(code); }
            state = SessionReducer.failed(state);
            monitor.notifyAll();
        }
    }

    private void terminate() {
        boolean interrupted = Thread.interrupted();
        try {
            process.destroy();
            if (!process.waitFor(deadlines.cleanup().toNanos(), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                process.waitFor(deadlines.cleanup().toNanos(), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            process.destroyForcibly();
        } finally {
            // Even a process that survives termination retains its own lease; never claim CLOSED.
            if (!process.isAlive()) {
                try { process.getOutputStream().close(); process.getInputStream().close(); process.getErrorStream().close(); }
                catch (IOException ignored) { /* no secret-bearing diagnostics */ }
            }
            if (interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
