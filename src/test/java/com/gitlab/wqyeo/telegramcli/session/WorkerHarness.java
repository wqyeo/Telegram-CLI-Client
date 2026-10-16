package com.gitlab.wqyeo.telegramcli.session;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Test-only launcher; no arbitrary command is exposed by the production CLI/API. */
final class WorkerHarness {
    private WorkerHarness() {}

    static Process launch(Path workspace, String profile, UUID generation, String mode) throws IOException {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-XX:-UsePerfData", "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=/dev/null",
                "-Dfile.encoding=UTF-8", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                SyntheticSessionWorker.class.getName(), workspace.toString(), profile, generation.toString(), mode);
        builder.environment().clear();
        builder.environment().put("LC_ALL", "C.UTF-8");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }
}
