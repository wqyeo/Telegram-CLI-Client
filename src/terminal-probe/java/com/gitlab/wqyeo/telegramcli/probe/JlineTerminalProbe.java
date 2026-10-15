package com.gitlab.wqyeo.telegramcli.probe;

import com.gitlab.wqyeo.telegramcli.application.DemoScenario;
import com.gitlab.wqyeo.telegramcli.tui.TuiApplication;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.terminal.ansi.ANSITerminal;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingInputStream;

/** Isolated, opt-in Linux experiment. Never included in the application's Maven sources. */
public final class JlineTerminalProbe {
    private JlineTerminalProbe() {}

    public static void main(String[] args) {
        boolean injectFailure = Arrays.equals(args, new String[] {"--demo", "--inject-failure"});
        if (!injectFailure && !Arrays.equals(args, new String[] {"--demo"})) {
            System.err.println("Usage: JlineTerminalProbe --demo [--inject-failure]");
            System.exit(2);
        }
        if (!"Linux".equals(System.getProperty("os.name")) || System.console() == null) {
            System.err.println("The experimental probe requires an interactive Linux terminal. Windows is unverified.");
            System.exit(2);
        }
        int result = 1;
        String report = null;
        try (var nativeTerminal = TerminalBuilder.builder()
                .name("telegram-cli experimental FFM probe")
                .system(true).systemOutput(TerminalBuilder.SystemOutput.SysOut)
                .provider("ffm").ffm(true).jni(false).exec(false).dumb(false).devTty(false)
                .encoding(StandardCharsets.UTF_8).nativeSignals(false).graphemeCluster(false)
                .build()) {
            if (!nativeTerminal.getClass().getName().equals("org.jline.terminal.impl.ffm.FfmUnixSysTerminal")) {
                throw new IllegalStateException("Unexpected experimental terminal provider");
            }
            // JLine alone owns OS modes/stream lifecycle. Lanterna alone decodes input and emits ANSI.
            // Do not use JLine's reader, Display, probing or native signal handlers alongside Lanterna.
            nativeTerminal.enterRawMode();
            var adapter = new Adapter(nativeTerminal);
            result = new TuiApplication().run(adapter, DemoScenario.standard(), 100, () -> {
                adapter.refreshSize(); // Only the UI thread touches Lanterna resize listeners.
                if (injectFailure) { throw new IllegalStateException("Synthetic probe failure"); }
            });
            report = "{\"schemaVersion\":1,\"experimental\":true,\"provider\":\"ffm\","
                    + "\"windowsValidated\":false,\"observedSizes\":" + adapter.observedSizes + "}";
        } catch (Exception | LinkageError failure) {
            result = 1;
            // Never print input, environment, paths or an uncontrolled native/provider exception.
            System.err.println("Experimental FFM probe failed; no fallback was started.");
        }
        if (result == 0) { System.out.println("\n" + report); }
        System.exit(result);
    }

    private static final class Adapter extends ANSITerminal {
        private final Terminal nativeTerminal;
        private final List<List<Integer>> observedSizes = new ArrayList<>();

        private Adapter(Terminal nativeTerminal) {
            super(readyInput(nativeTerminal), nativeTerminal.output(), StandardCharsets.UTF_8);
            this.nativeTerminal = nativeTerminal;
        }

        private static InputStream readyInput(Terminal terminal) {
            if (!(terminal.input() instanceof NonBlockingInputStream input)) {
                throw new IllegalStateException("Unsupported experimental input stream");
            }
            return new FilterInputStream(input) {
                @Override
                public int available() throws IOException {
                    // JLine's stream inherits available() == 0. Lanterna polls Reader.ready(),
                    // so bridge readiness with a bounded, non-consuming native-backed peek.
                    // A zero timeout would block forever; keep this explicitly nonzero.
                    return input.peek(1) >= 0 ? 1 : 0;
                }
            };
        }

        @Override
        protected TerminalSize findTerminalSize() {
            var size = nativeTerminal.getSize();
            int columns = size.getColumns();
            int rows = size.getRows();
            if (columns < 1 || rows < 1 || columns > 512 || rows > 256) {
                throw new IllegalStateException("Unsupported experimental terminal dimensions");
            }
            var dimensions = List.of(columns, rows);
            if (observedSizes.isEmpty() || !observedSizes.getLast().equals(dimensions)) {
                // Bounded synthetic evidence only, not a history of terminal activity.
                if (observedSizes.size() == 32) { observedSizes.removeFirst(); }
                observedSizes.add(dimensions);
            }
            return new TerminalSize(columns, rows);
        }

        private void refreshSize() {
            // getTerminalSize() calls Lanterna's onResized() only when dimensions change.
            try { getTerminalSize(); }
            catch (java.io.IOException failure) { throw new IllegalStateException("Terminal size query failed", failure); }
        }

        // StreamBasedTerminal.close() deliberately does not close the shared streams.
        // The outer try-with-resources closes JLine exactly once, restoring its original OS attributes.
    }
}
