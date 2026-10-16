package com.gitlab.wqyeo.telegramcli.session;

import java.nio.file.Files;
import java.nio.file.Path;

/** Fork-only fixture. Never change the test runner's umask. No SDK client or database. */
public final class NativePermissionProbe {
    private NativePermissionProbe() {}
    public static void main(String[] args) {
        try {
            NativeWorkerPermissions.restrict();
            if (args.length != 1) { throw new IllegalArgumentException(); }
            Path root = Path.of(args[0]);
            // No explicit POSIX attributes: test kernel policy on default creation modes.
            Files.createDirectory(root.resolve("synthetic-directory"));
            Files.createFile(root.resolve("synthetic-file"));
            System.exit(0);
        } catch (Exception | LinkageError failure) {
            System.exit(1); // No paths, exception strings or accidental fallback.
        }
    }
}
