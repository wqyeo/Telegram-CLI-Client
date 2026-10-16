package com.gitlab.wqyeo.telegramcli.session;

import java.io.Console;
import java.io.IOError;

/** Operator-local only. Agent verification must inject CredentialInput, never call open().
 * JDK internal buffers/copies are not hard-bounded or under our complete erasure control.
 * Uses the JDK's hidden input/echo restoration contract, not a second fullscreen reader.
 */
public final class ConsoleCredentialInput implements CredentialInput {
    private final Console console;
    private boolean closed;
    private ConsoleCredentialInput(Console console) { this.console = console; }
    public static CredentialInput open() throws SetupException {
        try {
            Console console = System.console();
            if (console == null || !console.isTerminal()) { throw new SetupException(SetupException.Code.CONSOLE_REQUIRED); }
            return new ConsoleCredentialInput(console);
        } catch (RuntimeException | IOError failure) { throw new SetupException(SetupException.Code.INPUT_UNAVAILABLE); }
    }
    @Override public char[] read(Prompt prompt) throws SetupException {
        if (closed || prompt == null) { throw new SetupException(SetupException.Code.INPUT_UNAVAILABLE); }
        try { return console.readPassword("%s", prompt.label()); }
        catch (RuntimeException | IOError failure) { throw new SetupException(SetupException.Code.INPUT_UNAVAILABLE); }
    }
    @Override public void close() { closed = true; }
    @Override public String toString() { return "[REDACTED credential input]"; }
}
