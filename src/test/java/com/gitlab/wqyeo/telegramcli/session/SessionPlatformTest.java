package com.gitlab.wqyeo.telegramcli.session;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/** Tests only the explicit refusal branch, not actual Windows compatibility. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SessionPlatformTest {
    @TempDir Path workspace;
    @Test void unsupportedPlatformsFailBeforeCreatingOrOpeningStorage() throws Exception {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertEquals(SessionException.Code.STORAGE_UNSUPPORTED,
                    assertThrows(SessionException.class, () -> ProfileStore.initialize(workspace, "default")).code());
            assertEquals(SessionException.Code.STORAGE_UNSUPPORTED,
                    assertThrows(SessionException.class, () -> ProfileStore.acquire(workspace, "default")).code());
            assertFalse(Files.exists(workspace.resolve("default")));
        } finally { System.setProperty("os.name", original); }
    }
}
