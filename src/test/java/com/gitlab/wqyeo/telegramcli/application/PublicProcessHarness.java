package com.gitlab.wqyeo.telegramcli.application;

import java.io.IOException;
import java.nio.file.Path;

/** Fixed test-only literal argv. No arbitrary command/credentials/workspace or SDK/native launch. */
public final class PublicProcessHarness {
    private PublicProcessHarness() {}
    public static Process launch(SyntheticReadOnlySession.Scenario scenario, String mode) throws IOException {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-XX:-UsePerfData", "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=/dev/null",
                "--illegal-native-access=deny", "-Dfile.encoding=UTF-8", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                PublicBridgeProcess.class.getName(), scenario.name(), mode);
        builder.environment().clear(); builder.environment().put("LC_ALL", "C.UTF-8");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }
}
