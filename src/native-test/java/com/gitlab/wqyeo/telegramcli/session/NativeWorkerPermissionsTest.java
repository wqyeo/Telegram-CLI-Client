package com.gitlab.wqyeo.telegramcli.session;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
class NativeWorkerPermissionsTest {
    @TempDir Path workspace;

    private Process probe(boolean grant, boolean deny, Path root, String... fixtureJvmOptions) throws Exception {
        var args = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:-UsePerfData", "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=/dev/null"));
        if (grant) { args.add("--enable-native-access=ALL-UNNAMED"); }
        if (deny) { args.add("--illegal-native-access=deny"); }
        args.addAll(List.of(fixtureJvmOptions));
        String classes = Path.of(NativeWorkerPermissions.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String fixtures = Path.of(NativePermissionProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        args.addAll(List.of("-cp", classes + File.pathSeparator + fixtures, NativePermissionProbe.class.getName(), root.toString()));
        var builder = new ProcessBuilder(args).redirectErrorStream(true);
        builder.environment().clear(); builder.environment().put("LC_ALL", "C.UTF-8");
        return builder.start();
    }

    @Test void childRestrictsDefaultCreationAndDoesNotChangeParentPolicy() throws Exception {
        Path before = Files.createFile(workspace.resolve("parent-before"));
        var parentModes = Files.getPosixFilePermissions(before);
        Path child = Files.createDirectory(workspace.resolve("child"));
        Process process = probe(true, true, child);
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals(0, process.getInputStream().readAllBytes().length);
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(child.resolve("synthetic-directory")));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(child.resolve("synthetic-file")));
            assertEquals(parentModes, Files.getPosixFilePermissions(Files.createFile(workspace.resolve("parent-after"))));
        } finally { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); }
    }

    @Test void unsupportedArchitectureRefusesBeforeCreatingAnything() throws Exception {
        Path child = Files.createDirectory(workspace.resolve("unsupported"));
        Process process = probe(true, true, child, "-Dos.arch=aarch64");
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(1, process.exitValue());
            assertEquals(0, process.getInputStream().readAllBytes().length);
            try (var files = Files.list(child)) { assertEquals(0, files.count()); }
        } finally { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); }
    }

    @Test void missingExplicitGrantRefusesEvenIfJdkWouldOnlyWarn() throws Exception {
        for (boolean deny : new boolean[] {false, true}) {
            Path child = Files.createDirectory(workspace.resolve("refused-" + deny));
            Process process = probe(false, deny, child);
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                assertEquals(1, process.exitValue());
                assertEquals(0, process.getInputStream().readAllBytes().length);
                try (var files = Files.list(child)) { assertEquals(0, files.count()); }
            } finally { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); }
        }
    }
}
