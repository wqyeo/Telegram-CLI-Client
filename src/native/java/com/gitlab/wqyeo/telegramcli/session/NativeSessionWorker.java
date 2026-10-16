package com.gitlab.wqyeo.telegramcli.session;

import java.nio.file.Path;
import java.util.*;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import com.gitlab.wqyeo.telegramcli.telegram.NativeProbe;

/** Account-free only: holds a lease, checks synthetic transport/JNI, waits for close. */
public final class NativeSessionWorker {
    private NativeSessionWorker() {}
    public static void main(String[] args) {
        int status = 0;
        try {
            if (args.length != 3) { throw new IllegalArgumentException(); }
            UUID generation = UUID.fromString(args[2]);
            try (var lease = ProfileStore.acquire(Path.of(args[0]), args[1]);
                 var material = SecretTransfer.read(System.in, generation)) {
                byte[] encoded = material.encode();
                try {
                    if (java.nio.ByteBuffer.wrap(encoded).getInt() != 1) { throw new IllegalArgumentException(); }
                    for (int index = 4; index < 36; index++) { if (encoded[index] != '0') { throw new IllegalArgumentException(); } }
                } finally { Arrays.fill(encoded, (byte) 0); }
                // Dispose bootstrap material before calling JNI; NEVER pass it to TDLib.
                material.close();
                System.loadLibrary("tdjni");
                TdApi.TextEntities result = Client.execute(new TdApi.GetTextEntities(NativeProbe.TEXT));
                if (result.entities.length != 3
                        || result.entities[0].offset != 9 || result.entities[0].length != 19
                        || !(result.entities[0].type instanceof TdApi.TextEntityTypeUrl)
                        || result.entities[1].offset != 29 || result.entities[1].length != 5
                        || !(result.entities[1].type instanceof TdApi.TextEntityTypeHashtag)
                        || result.entities[2].offset != 35 || result.entities[2].length != 12
                        || !(result.entities[2].type instanceof TdApi.TextEntityTypeMention)) { throw new IllegalStateException(); }
                SessionProtocol.write(System.out, new SessionProtocol.Frame(1, generation, 1, SessionState.Signal.STARTED));
                var request = SessionProtocol.read(System.in);
                if (request == null || !request.generation().equals(generation) || request.sequence() != 1
                        || request.signal() != SessionState.Signal.CLOSE_REQUESTED) { throw new IllegalStateException(); }
                // No actual TDLib session exists: this acknowledges worker closure, not TDLib Close.
                SessionProtocol.write(System.out, new SessionProtocol.Frame(1, generation, 2, SessionState.Signal.ENGINE_CLOSED));
            }
        } catch (Exception | LinkageError exception) {
            // Fixed exit only: no raw parser/native causes, secret bytes, paths or stack traces.
            status = 1;
        }
        System.exit(status);
    }
}
