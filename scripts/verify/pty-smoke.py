#!/usr/bin/env python3
"""Manually invoked Linux PTY smoke test; no terminal emulator, account or network needed."""
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


class TerminalReplies:
    """Minimal cursor/size negotiation, not a visual terminal emulator.

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

    def feed(self, chunk):
        self.pending.extend(chunk)
        replies = []
        while match := self.CSI.search(self.pending):
            parameters, operation = match.groups()
            rows, columns = self.size()
            if operation in (b'H', b'f'):
                coordinates = parameters.split(b';')
                row = int(coordinates[0] or b'1')
                column = int(coordinates[1] or b'1') if len(coordinates) > 1 else 1
                self.cursor = (min(rows, max(1, row)), min(columns, max(1, column)))
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
            if actions == 0 and b'OFFLINE DEMO' in output:
                # Enter is a newline. F2 alone simulates submission.
                os.write(master, '\t\tsynthetic first\r你好🙂'.encode())
                os.write(master, b'\x1bOQ')
                actions = 1
            elif actions == 1 and b'Simulated locally' in output:
                fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 14, 48, 0, 0))
                os.kill(child.pid, signal.SIGWINCH)
                os.write(master, b'\x1bOP')  # F1
                actions = 2
            elif actions == 2 and b'OFFLINE DEMO HELP' in output:
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
