package com.gitlab.wqyeo.telegramcli.telegram;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

/** Separate process only. No Client.create(), authorization, database, or network operation. */
public final class NativeProbeWorker {
    private NativeProbeWorker() {}

    public static void main(String[] args) {
        try {
            // Preload before upstream Client's initializer, which otherwise prints linkage errors.
            System.loadLibrary("tdjni");
            TdApi.TextEntities result = Client.execute(new TdApi.GetTextEntities(NativeProbe.TEXT));
            var json = new StringBuilder("{\"entities\":[");
            for (int i = 0; i < result.entities.length; i++) {
                var entity = result.entities[i];
                if (i > 0) { json.append(','); }
                json.append("{\"offset\":").append(entity.offset).append(",\"length\":").append(entity.length)
                        .append(",\"type\":\"").append(entity.type.getClass().getSimpleName()).append("\"}");
            }
            System.out.println(json.append("]}"));
        } catch (LinkageError | RuntimeException | Client.ExecutionException exception) {
            // No native stderr, throwable messages, paths, or JVM stack traces cross this boundary.
            System.exit(1);
        }
    }
}
