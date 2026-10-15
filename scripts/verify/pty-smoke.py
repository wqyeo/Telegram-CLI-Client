#!/usr/bin/env python3
"""Manually invoked Linux PTY smoke test; no terminal emulator, account or network needed."""
import codecs
import errno
import fcntl
import json
import os
import pty
import re
import selectors
import signal
import struct
import subprocess
import sys
import termios
import time
import unicodedata


class TerminalReplies:
    """Minimal cursor/size negotiation and status-text grid, not a visual emulator.

    Lanterna probes size by moving to (5001,5001), then asking for the cursor.
    Reporting (1,1) unconditionally makes it correctly believe the PTY is 1x1.
    """
    CSI = re.compile(rb'\x1b\[([0-9;?]*)([A-Za-z])')

    def __init__(self, size):
        self.size = size
        self.pending = bytearray()
        self.cursor = (1, 1)
        self.saved = self.cursor
        self.reported_sizes = []
        self.cells = {}
        self.decoder = codecs.getincrementaldecoder('utf-8')(errors='replace')

    def text(self):
        rows, columns = self.size()
        return '\n'.join(''.join(self.cells.get((row, column), ' ')
                                for column in range(1, columns + 1))
                         for row in range(1, rows + 1))

    def _write(self, data):
        # This grid checks ASCII status labels, not font/grapheme layout fidelity.
        rows, columns = self.size()
        row, column = self.cursor
        for character in self.decoder.decode(data):
            if character == '\r':
                column = 1
            elif character == '\n':
                row = min(rows, row + 1)
            elif character == '\b':
                column = max(1, column - 1)
            elif character.isprintable() and unicodedata.category(character) not in ('Mn', 'Me', 'Cf'):
                if column > columns:
                    row, column = min(rows, row + 1), 1
                width = 2 if unicodedata.east_asian_width(character) in ('W', 'F') else 1
                self.cells[(row, column)] = character
                if width == 2 and column < columns:
                    self.cells[(row, column + 1)] = ' '
                column += width
        self.cursor = (row, column)

    def feed(self, chunk):
        self.pending.extend(chunk)
        replies = []
        while match := self.CSI.search(self.pending):
            self._write(self.pending[:match.start()])
            parameters, operation = match.groups()
            rows, columns = self.size()
            if operation in (b'H', b'f'):
                coordinates = parameters.split(b';')
                row = int(coordinates[0] or b'1')
                column = int(coordinates[1] or b'1') if len(coordinates) > 1 else 1
                self.cursor = (min(rows, max(1, row)), min(columns, max(1, column)))
            elif ((operation == b'J' and parameters == b'2')
                  or (operation == b'h' and parameters == b'?1049')):
                self.cells.clear()
            elif operation == b's':
                self.saved = self.cursor
            elif operation == b'u':
                self.cursor = self.saved
            elif operation == b'n' and parameters == b'6':
                replies.append(f'\x1b[{self.cursor[0]};{self.cursor[1]}R'.encode())
                if self.cursor == (rows, columns):
                    self.reported_sizes.append(self.cursor)
            elif operation == b'c' and not parameters:
                replies.append(b'\x1b[?1;2c')
            elif operation == b't' and parameters == b'18':
                replies.append(f'\x1b[8;{rows};{columns}t'.encode())
            del self.pending[:match.end()]
        # Retain incomplete escape sequences and let the UTF-8 decoder retain
        # incomplete codepoints; neither may become visible status text.
        end = self.pending.find(b'\x1b')
        if end < 0:
            end = len(self.pending)
        self._write(self.pending[:end])
        del self.pending[:end]
        if len(self.pending) > 1024:
            del self.pending[:-1024]
        return replies


def main():
    command = sys.argv[1:]
    if command and command[0] == '--':
        command = command[1:]
    if not command:
        raise SystemExit('Usage: pty-smoke.py -- COMMAND [ARGS...] (include tui --demo)')
    master, slave = pty.openpty()
    original = termios.tcgetattr(slave)
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 30, 100, 0, 0))

    def controlling_terminal():
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

    environment = dict(os.environ, TERM='xterm-256color', LANG='C.UTF-8')
    child = subprocess.Popen(command, stdin=slave, stdout=slave, stderr=slave,
                             preexec_fn=controlling_terminal, env=environment)
    selector = selectors.DefaultSelector()
    selector.register(master, selectors.EVENT_READ)
    output = bytearray()
    terminal = TerminalReplies(lambda: termios.tcgetwinsize(slave))
    actions = 0
    idle_settle_until = None
    idle_measure_until = None
    idle_start_bytes = 0
    idle_output_bytes = None
    deadline = time.monotonic() + 30
    try:
        while time.monotonic() < deadline:
            for _, _ in selector.select(0.05):
                try:
                    chunk = os.read(master, 65536)
                except OSError as error:
                    if error.errno != errno.EIO:
                        raise
                    chunk = b''
                output.extend(chunk)
                if len(output) > 4_194_304:
                    raise AssertionError('PTY output exceeded the 4 MiB evidence limit')
                for answer in terminal.feed(chunk):
                    os.write(master, answer)
            visible = terminal.text()
            if actions == 0 and 'OFFLINE DEMO' in visible:
                now = time.monotonic()
                if idle_settle_until is None:
                    idle_settle_until = now + 0.5
                elif now >= idle_settle_until and idle_measure_until is None:
                    idle_start_bytes = len(output)
                    idle_measure_until = now + 0.5
                elif idle_measure_until is not None and now >= idle_measure_until:
                    idle_output_bytes = len(output) - idle_start_bytes
                    assert idle_output_bytes == 0, f'idle terminal emitted {idle_output_bytes} bytes'
                    # Enter is a newline. F2 alone simulates submission.
                    os.write(master, '\t\tsynthetic first\r你好🙂'.encode())
                    os.write(master, b'\x1bOQ')
                    actions = 1
            elif actions == 1 and 'Simulated locally' in visible:
                fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 14, 48, 0, 0))
                os.kill(child.pid, signal.SIGWINCH)
                os.write(master, b'\x1bOP')  # F1
                actions = 2
            elif actions == 2 and 'OFFLINE DEMO HELP' in visible:
                os.write(master, b'\x1b')
                os.write(master, b'\x03')  # Ctrl+C, with no remaining draft
                actions = 3
            if child.poll() is not None:
                break
        if child.poll() is None:
            raise AssertionError('PTY smoke test exceeded 30 seconds')
        assert child.returncode == 0, f'exit={child.returncode}'
        assert actions == 3, f'incomplete interaction: phase={actions}'
        assert (30, 100) in terminal.reported_sizes, 'initial dimensions were not negotiated'
        assert (14, 48) in terminal.reported_sizes, 'resized dimensions were not negotiated'
        restored = termios.tcgetattr(slave)
        assert restored == original, 'terminal attributes were not fully restored'
        print(json.dumps({'schemaVersion': 1, 'status': 'ok', 'interactiveDemo': True,
                          'idleOutputBytes': idle_output_bytes,
                          'simulatedSubmission': True, 'resizeNegotiated': True,
                          'helpDisplayed': True, 'terminalAttributesRestored': True}))
    except Exception:
        print('Bounded synthetic terminal transcript: ' + repr(bytes(output[-6000:])), file=sys.stderr)
        raise
    finally:
        if child.poll() is None:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait(timeout=5)
        selector.close()
        os.close(master)
        os.close(slave)


if __name__ == '__main__':
    main()
