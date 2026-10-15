package com.gitlab.wqyeo.telegramcli.application;

import com.gitlab.wqyeo.telegramcli.model.ClientState;
import com.gitlab.wqyeo.telegramcli.telegram.FakeTelegramEngine;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class DemoService {
    public ClientState run(int historyLimit) throws InterruptedException, ExecutionException, TimeoutException {
        try (var store = new OrderedStateStore(historyLimit); var engine = new FakeTelegramEngine()) {
            engine.start(store::accept);
            engine.close();
            return store.snapshot().get(5, TimeUnit.SECONDS);
        }
    }
}
