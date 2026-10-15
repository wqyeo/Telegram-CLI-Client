#!/usr/bin/env python3
"""Explicit Linux PTY checks for the experimental FFM/Lanterna adapter, not Windows proof.

Requires the native-free application and `probe.py prepare`. No downloads occur here.
The reused ASCII status grid is not a Unicode/font visual emulator.
"""
import fcntl
import json
import os
import pathlib
import pty
import runpy
import selectors
import signal
import struct
import subprocess
import sys
import termios
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
TerminalReplies = runpy.run_path(str(pathlib.Path(__file__).with_name('pty-smoke.py')))['TerminalReplies']


def exercise(command, mode, expected_exit):
    master, slave = pty.openpty()
    original = termios.tcgetattr(slave)
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 30, 100, 0, 0))

    def controlling_terminal():
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

    child = subprocess.Popen(command, stdin=slave, stdout=slave, stderr=slave,
                             preexec_fn=controlling_terminal,
                             env={**os.environ, 'TERM': 'xterm-256color', 'LANG': 'C.UTF-8'})
    output = bytearray()
    terminal = TerminalReplies(lambda: termios.tcgetwinsize(slave))
    selector = selectors.DefaultSelector()
    selector.register(master, selectors.EVENT_READ)
    phase = 0
    settled_until = None
    measure_until = None
    idle_start = 0
    idle_bytes = None
    edit_bytes = None
    deadline = time.monotonic() + 30
    try:
        while time.monotonic() < deadline:
            for _, _ in selector.select(0.03):
                chunk = os.read(master, 65536)
                output.extend(chunk)
                if len(output) > 4_194_304:
                    raise AssertionError('Experimental PTY output exceeded the 4 MiB bound')
                for reply in terminal.feed(chunk):
                    os.write(master, reply)
            visible = terminal.text()
            if mode == 'interactive':
                if phase == 0 and 'OFFLINE DEMO' in visible:
                    now = time.monotonic()
                    if settled_until is None:
                        settled_until = now + 0.5
                    elif now >= settled_until and measure_until is None:
                        idle_start, measure_until = len(output), now + 0.5
                    elif measure_until is not None and now >= measure_until:
                        idle_bytes = len(output) - idle_start
                        assert idle_bytes == 0, f'idle output was {idle_bytes} bytes'
                        os.write(master, '\t\tsynthetic first\r你好🙂e\u0301'.encode())
                        os.write(master, b'\x1bOQ')
                        phase = 1
                elif phase == 1 and 'Simulated locally' in visible:
                    delta = output[idle_start:]
                    assert b'\x1b[2J' not in delta, 'ordinary input forced a physical screen clear'
                    assert all(not row[25:].strip() for row in visible.splitlines()[25:28]), 'stale composer text'
                    edit_bytes = len(delta)
                    # No following key or explicit signal: the adapter must detect this while idle.
                    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 14, 48, 0, 0))
                    phase = 2
                elif phase == 2 and visible.splitlines()[8].startswith('-- Composer'):
                    os.write(master, b'\x1bOP')  # Help only after the resized layout is visible.
                    phase = 3
                elif phase == 3 and 'OFFLINE DEMO HELP' in visible:
                    os.write(master, b'\x1b\x03')
                    phase = 4
            elif mode in ('draft-quit', 'launcher-draft-quit'):
                if phase == 0 and 'OFFLINE DEMO' in visible:
                    os.write(master, b'\t\tsynthetic draft\x03')
                    phase = 1
                elif phase == 1 and 'DISCARD UNSENT DEMO DRAFTS?' in visible:
                    os.write(master, b'y')
                    phase = 2
            if child.poll() is not None:
                # Drain the bounded output after exit, including the final JSON or failure label.
                while selector.select(0):
                    chunk = os.read(master, 65536)
                    if not chunk:
                        break
                    output.extend(chunk)
                    assert len(output) <= 4_194_304
                break
        assert child.poll() is not None, f'Experimental PTY test exceeded 30 seconds: {mode}, phase={phase}'
        assert child.returncode == expected_exit, f'{mode}: exit={child.returncode}'
        assert termios.tcgetattr(slave) == original, f'{mode}: terminal attributes were not restored'
        if mode in ('interactive', 'draft-quit', 'launcher-draft-quit', 'injected-failure'):
            assert b'\x1b[?1049h' in output and b'\x1b[?1049l' in output, 'alternate screen not restored'
            assert b'\x1b[?25h' in output, 'cursor visibility not restored'
        if mode == 'interactive':
            assert phase == 4, f'incomplete interaction phase={phase}'
            reports = [json.loads(line) for line in output.decode('utf-8', errors='replace').splitlines()
                       if line.startswith('{')]
            assert len(reports) == 1, 'missing experimental report'
            report = reports[0]
            assert report['provider'] == 'ffm' and report['windowsValidated'] is False
            assert [100, 30] in report['observedSizes'] and [48, 14] in report['observedSizes']
        elif mode in ('draft-quit', 'launcher-draft-quit'):
            assert phase == 2, 'draft discard confirmation was not exercised'
        else:
            assert b'Experimental FFM probe failed; no fallback was started.' in output
        return {'scenario': mode, 'exit': child.returncode, 'terminalAttributesRestored': True,
                **({'idleOutputBytes': idle_bytes, 'editOutputBytes': edit_bytes,
                    'ordinaryEditsAvoidPhysicalClear': True, 'composerDeletionClean': True,
                    'resizeWithoutInput': True} if mode == 'interactive' else {})}
    except Exception:
        print('Bounded synthetic probe transcript: ' + repr(bytes(output[-4000:])), file=sys.stderr)
        raise
    finally:
        if child.poll() is None:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait(timeout=5)
        selector.close()
        os.close(master)
        os.close(slave)


def main():
    try:
        command = json.loads(subprocess.check_output(
            [sys.executable, str(ROOT / 'scripts/terminal-probe/probe.py'), 'command'], text=True, timeout=15))
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        raise SystemExit('Experimental probe command unavailable; build the application and run probe.py prepare.') from error
    noninteractive = subprocess.run(command, stdin=subprocess.DEVNULL, capture_output=True, timeout=20, check=False)
    assert noninteractive.returncode == 2 and not noninteractive.stdout
    assert b'requires an interactive Linux terminal' in noninteractive.stderr
    reports = [exercise(command, 'interactive', 0),
               exercise([*command, '--inject-failure'], 'injected-failure', 1),
               exercise(command, 'draft-quit', 0)]
    denied = [argument for argument in command if not argument.startswith('--enable-native-access=')]
    reports.append(exercise(denied, 'native-access-denied', 1))
    reports.append(exercise([sys.executable, str(ROOT / 'scripts/terminal-probe/probe.py'), 'run'],
                            'launcher-draft-quit', 0))
    print(json.dumps({'schemaVersion': 1, 'status': 'ok', 'experimental': True,
                      'windowsValidated': False, 'noninteractiveRejected': True, 'scenarios': reports}))


if __name__ == '__main__':
    main()
