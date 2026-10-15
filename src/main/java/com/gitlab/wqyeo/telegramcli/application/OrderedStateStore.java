package com.gitlab.wqyeo.telegramcli.application;

import com.gitlab.wqyeo.telegramcli.model.ClientState;
import com.gitlab.wqyeo.telegramcli.model.EngineEvent;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One bounded FIFO state executor. Saturation rejects visibly rather than dropping events. */
public final class OrderedStateStore implements AutoCloseable {
    private final StateReducer reducer;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
            Thread.ofPlatform().name("telegram-state-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private ClientState state = ClientState.initial();
    private RuntimeException failure;

    public OrderedStateStore(int historyLimit) {
        reducer = new StateReducer(historyLimit);
    }

    public void accept(EngineEvent event) {
        Objects.requireNonNull(event, "event");
        executor.execute(() -> {
            if (failure == null) {
                try {
                    state = reducer.reduce(state, event);
                } catch (RuntimeException exception) {
                    failure = exception;
                }
            }
        });
    }

    /** The queued barrier observes all previously accepted events. */
    public Future<ClientState> snapshot() {
        return executor.submit(() -> {
            if (failure != null) {
                throw new IllegalStateException("State processing failed.", failure);
            }
            return state;
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                throw new IllegalStateException("State executor did not stop within the deadline.");
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("State executor shutdown was interrupted.", exception);
        }
    }
}
