package com.gitlab.wqyeo.telegramcli;

import com.gitlab.wqyeo.telegramcli.cli.RootCommand;
import java.io.PrintWriter;
import picocli.CommandLine;

/** Process boundary; application services never terminate the JVM. */
public final class Main {
    private Main() {}

    public static int execute(String[] args, PrintWriter out, PrintWriter err) {
        var commandLine = new CommandLine(new RootCommand());
        commandLine.setOut(out);
        commandLine.setErr(err);
        commandLine.setColorScheme(new CommandLine.Help.ColorScheme.Builder()
                .ansi(CommandLine.Help.Ansi.OFF).build());
        commandLine.setExecutionExceptionHandler((exception, command, parseResult) -> {
            if (exception instanceof CommandLine.ParameterException) {
                command.getErr().println("Configuration is invalid or unreadable.");
                return 2;
            }
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            command.getErr().println("Operation failed. No Telegram operation was performed.");
            return 1;
        });
        return commandLine.execute(args);
    }

    public static void main(String[] args) {
        System.exit(execute(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true)));
    }
}
