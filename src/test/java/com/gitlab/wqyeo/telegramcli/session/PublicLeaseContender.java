package com.gitlab.wqyeo.telegramcli.session;

import java.nio.file.Path;

/** Test-only exact kernel-lock probe. PUBLIC disposable paths only; no SDK or providers. */
public final class PublicLeaseContender {
    private PublicLeaseContender() {}

    public static void main(String[] arguments) {
        if (arguments.length != 2) { System.exit(70); return; }
        try (var lease = ProfileStore.acquire(Path.of(arguments[0]), arguments[1])) {
            // Print availability only after acquisition AND successful release.
        } catch (SessionException failure) {
            if (failure.code() == SessionException.Code.PROFILE_BUSY) {
                System.out.print("PUBLIC_LOCK_BUSY\n");
                System.exit(20);
            } else {
                System.out.print("PUBLIC_LOCK_FIXTURE_ERROR\n");
                System.exit(70);
            }
            return;
        }
        System.out.print("PUBLIC_LOCK_AVAILABLE\n");
    }
}
