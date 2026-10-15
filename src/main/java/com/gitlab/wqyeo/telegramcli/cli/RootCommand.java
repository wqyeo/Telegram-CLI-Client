package com.gitlab.wqyeo.telegramcli.cli;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gitlab.wqyeo.telegramcli.application.DemoService;
import com.gitlab.wqyeo.telegramcli.config.AppConfig;
import com.gitlab.wqyeo.telegramcli.config.ConfigLoader;
import com.gitlab.wqyeo.telegramcli.model.ClientState;
import com.gitlab.wqyeo.telegramcli.platform.AppDirectories;
import com.gitlab.wqyeo.telegramcli.tui.TerminalText;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(name = "tgc", mixinStandardHelpOptions = true, versionProvider = RootCommand.VersionProvider.class,
        description = "Unofficial Telegram CLI — offline prototype; no Telegram connection is available.",
        subcommands = {RootCommand.Diagnostics.class, RootCommand.Demo.class, TuiCommand.class, NativeCheckCommand.class})
public final class RootCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Option(names = "--config", scope = CommandLine.ScopeType.INHERIT, paramLabel = "FILE",
            description = "Explicit non-secret JSON configuration file; no file is read by default.")
    private Path configFile;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    AppConfig config() {
        try {
            return new ConfigLoader().load(configFile);
        } catch (IOException | IllegalArgumentException exception) {
            // Parsing exceptions can contain input snippets: never send those to the terminal.
            throw new CommandLine.ParameterException(spec.commandLine(), "Configuration is invalid or unreadable.");
        }
    }

    public static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() throws IOException {
            var properties = new Properties();
            try (var input = RootCommand.class.getResourceAsStream("/version.properties")) {
                if (input != null) {
                    properties.load(input);
                }
            }
            return new String[] {"Unofficial Telegram CLI " + properties.getProperty("application.version", "development")};
        }
    }

    public record DiagnosticsReport(int schemaVersion, String mode, int javaFeature, String operatingSystem,
                                    String engine, AppConfig config, String configDirectory, String dataDirectory) {}

    public record DemoReport(int schemaVersion, String mode, ClientState state) {}

    @Command(name = "diagnostics", mixinStandardHelpOptions = true,
            description = "Show local, non-secret diagnostics. Does not connect to Telegram.")
    public static final class Diagnostics implements Callable<Integer> {
        @ParentCommand
        private RootCommand parent;
        @Spec
        private CommandSpec spec;
        @Option(names = "--json", description = "Print a schema-versioned JSON object.")
        private boolean json;

        @Override
        public Integer call() throws IOException {
            var config = parent.config();
            var directories = AppDirectories.current();
            var report = new DiagnosticsReport(1, "offline", Runtime.version().feature(),
                    System.getProperty("os.name"), "not-integrated", config,
                    directories.config().toString(), directories.data().toString());
            var out = spec.commandLine().getOut();
            if (json) {
                out.println(JsonMapper.builder().build().writeValueAsString(report));
            } else {
                out.println("Offline prototype — real Telegram engine is not integrated; no Telegram connection was made.");
                out.printf("Java: %d%nPlatform: %s%nProfile: %s%n", report.javaFeature(),
                        TerminalText.escapeControls(report.operatingSystem()), config.profile());
                out.println("Config directory (not created): " + TerminalText.escapeControls(report.configDirectory()));
                out.println("Data directory (not created): " + TerminalText.escapeControls(report.dataDirectory()));
            }
            return 0;
        }
    }

    @Command(name = "demo", mixinStandardHelpOptions = true,
            description = "Run a synthetic offline event sequence. Never logs in or sends a real message.")
    public static final class Demo implements Callable<Integer> {
        @ParentCommand
        private RootCommand parent;
        @Spec
        private CommandSpec spec;
        @Option(names = "--json", description = "Print a schema-versioned offline demo result.")
        private boolean json;

        @Override
        public Integer call() throws Exception {
            var state = new DemoService().run(parent.config().historyLimit());
            var out = spec.commandLine().getOut();
            if (json) {
                out.println(JsonMapper.builder().build().writeValueAsString(new DemoReport(1, "offline-demo", state)));
            } else {
                out.println("OFFLINE DEMO — synthetic data only; no Telegram connection or message send occurred.");
                for (var message : state.messages()) {
                    out.println(TerminalText.escapeControls(message.sender()) + ": "
                            + TerminalText.escapeControls(message.text()));
                }
                out.println("Demo engine state: " + state.connection());
            }
            return 0;
        }
    }
}
