package com.gitlab.wqyeo.telegramcli.session;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;

/** Linux x64 CHILD ONLY: process-global umask, before any TDLib client threads. */
final class NativeWorkerPermissions {
    private NativeWorkerPermissions() {}

    static void restrict() throws SessionException {
        try {
            String architecture = System.getProperty("os.arch");
            if (!System.getProperty("os.name").equalsIgnoreCase("linux")
                    || !(architecture.equals("amd64") || architecture.equals("x86_64"))
                    || !NativeWorkerPermissions.class.getModule().isNativeAccessEnabled()) {
                throw new IllegalStateException();
            }
            // Linux x64 mode_t is unsigned 32-bit. No arena/pointer or third-party FFM dependency.
            var linker = Linker.nativeLinker();
            var umask = linker.downcallHandle(linker.defaultLookup().find("umask").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            int previous = (int) umask.invokeExact(0077);
            if (previous < 0 || previous > 0777) { throw new IllegalStateException(); }
            // Deliberately do not restore: this process is the private native worker.
        } catch (Throwable failure) {
            // Never log loader/linker causes or continue without the creation policy.
            throw new SessionException(SessionException.Code.STORAGE_UNSUPPORTED);
        }
    }
}
