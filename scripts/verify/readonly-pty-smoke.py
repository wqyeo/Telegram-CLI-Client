#!/usr/bin/env python3
"""Synthetic-only Linux PTYs. Reuses bounded status negotiation, not a visual/font emulator.
Never invoke vault commands, credential input or a native account client.
Usage: readonly-pty-smoke.py -- COMMAND [ARGS...] (without tui arguments).
"""
import errno
import fcntl
import importlib.util
import json
import os
import pty
import selectors
import signal
import struct
import subprocess
import sys
import termios
import time
from pathlib import Path

spec = importlib.util.spec_from_file_location('readonly_pty_replies', Path(__file__).with_name('pty-smoke.py'))
if spec is None or spec.loader is None:
    raise RuntimeError('PTY helper could not be resolved.')
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
LABELS = [b'Phone number step', b'Login code step', b'Email address step',
          b'Email verification code step', b'Two-step verification password step']
HEADER = 'READ-ONLY PREVIEW | SYNTHETIC'


def run(base, scenario):
    master, slave = pty.openpty()
    original = termios.tcgetattr(slave)
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 30, 100, 0, 0))

    def controlling_terminal():
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

    mode = 'RETRY' if scenario == 'retry' else 'UNSUPPORTED' if scenario == 'unsupported' else 'FULL'
    command = [*base, 'tui', '--read-only-demo', '--auth-scenario', mode]
    child = subprocess.Popen(command, stdin=slave, stdout=slave, stderr=slave,
                             preexec_fn=controlling_terminal,
                             env=dict(os.environ, TERM='xterm-256color', LANG='C.UTF-8'))
    selector = selectors.DefaultSelector()
    selector.register(master, selectors.EVENT_READ)
    terminal = helper.TerminalReplies(lambda: termios.tcgetwinsize(slave))
    output = bytearray()
    step = 0
    phase = 'guide'
    retry_seen = None
    retry_early = retry_again = False
    settle = measure = None
    idle_start = delta_start = 0
    idle_bytes = None
    ordinary_delta = 0
    private_entered = False
    deadline = time.monotonic() + 35
    try:
        while time.monotonic() < deadline:
            for _, _ in selector.select(0.03):
                try:
                    chunk = os.read(master, 65536)
                except OSError as error:
                    if error.errno != errno.EIO:
                        raise
                    chunk = b''
                output.extend(chunk)
                if len(output) > 4_194_304:
                    raise AssertionError('PTY output exceeded 4 MiB')
                for answer in terminal.feed(chunk):
                    os.write(master, answer)
            now = time.monotonic()
            visible = terminal.text()
            private_entered = private_entered or b'\x1b[?1049h' in output
            if phase == 'guide':
                if scenario == 'cancel' and LABELS[0] in output:
                    os.write(master, b'\x03')
                    phase = 'closing'
                elif scenario == 'unsupported' and b'Nothing accepted' in output:
                    assert not private_entered, 'unsupported flow entered fullscreen'
                    os.write(master, b'q')
                    phase = 'closing'
                else:
                    if scenario == 'retry' and b'Retry delay active' in output and not retry_again:
                        if retry_seen is None:
                            retry_seen = now
                        if not retry_early:
                            os.write(master, b'\r')
                            retry_early = True
                        if now - retry_seen < 1.2:
                            assert LABELS[1] not in output, 'retry was automatically/prematurely submitted'
                        elif b'no attempt was sent' in output:
                            os.write(master, b'\r')
                            retry_again = True
                    elif step < len(LABELS) and LABELS[step] in output:
                        os.write(master, b'\r')
                        step += 1
                    if HEADER in visible:
                        assert step == 5, 'fullscreen began before all fixture input stages'
                        assert scenario not in ('cancel', 'unsupported')
                        phase = 'idle'
                        settle = now + 0.5
            elif phase == 'idle':
                if settle is None:
                    raise AssertionError('Idle measurement was not initialized.')
                if now >= settle and measure is None:
                    idle_start = len(output)
                    measure = now + 0.5
                elif measure is not None and now >= measure:
                    idle_bytes = len(output) - idle_start
                    assert idle_bytes == 0, f'idle output={idle_bytes}'
                    delta_start = len(output)
                    os.write(master, b'\x1bOQ')  # F2: explicitly unavailable, never a success simulation
                    phase = 'f2'
            elif phase == 'f2' and 'no composer or send action' in visible:
                assert b'\x1b[2J' not in output[delta_start:], 'routine F2 repaint cleared the screen'
                os.write(master, b'\tn')  # History, explicit older Saved page
                phase = 'older'
            elif phase == 'older' and 'Older synthetic Saved page' in visible:
                os.write(master, b'b')
                phase = 'newer'
            elif phase == 'newer' and 'Newer synthetic Saved page' in visible:
                ordinary_delta = len(output) - delta_start
                assert ordinary_delta < 65536, 'ordinary interactions exceeded bounded delta'
                assert b'\x1b[2J' not in output[delta_start:], 'ordinary pagination cleared the screen'
                fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 14, 48, 0, 0))
                os.kill(child.pid, signal.SIGWINCH)  # Resize deliberately without a key.
                phase = 'resize'
            elif phase == 'resize' and (14, 48) in terminal.reported_sizes and HEADER in visible:
                os.write(master, b'\x1bOP')  # F1
                phase = 'help'
            elif phase == 'help' and 'SYNTHETIC READ-ONLY HELP' in visible:
                os.write(master, b'\x03' if scenario == 'full' else b'q')
                phase = 'closing'
            if child.poll() is not None:
                break
        if child.poll() is None:
            raise AssertionError(f'PTY deadline exceeded in phase={phase}')
        assert child.returncode == 0, f'exit={child.returncode}'
        assert phase == 'closing', f'incomplete phase={phase}'
        assert b'PUBLIC_SYNTHETIC_' not in output, 'fixture submission values were printed'
        assert b'Never type credentials' in output
        assert termios.tcgetattr(slave) == original, 'terminal attributes not exactly restored'
        if scenario in ('cancel', 'unsupported'):
            assert not private_entered, 'cancel/unsupported entered private mode'
        else:
            assert idle_bytes == 0
            assert (30, 100) in terminal.reported_sizes and (14, 48) in terminal.reported_sizes
        if scenario == 'retry':
            assert retry_early and retry_again and retry_seen is not None
        return {'scenario': scenario, 'status': 'ok', 'idleOutputBytes': idle_bytes,
                'ordinaryDeltaBytes': ordinary_delta, 'fullscreenEntered': private_entered,
                'terminalAttributesRestored': True, 'syntheticInputOnly': True}
    except Exception:
        print('Bounded public-fixture transcript: ' + repr(bytes(output[-6000:])), file=sys.stderr)
        raise
    finally:
        if child.poll() is None:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait(timeout=5)
        selector.close()
        os.close(master)
        os.close(slave)


def main():
    if sys.flags.optimize:
        raise SystemExit('Assertions must be enabled for verification.')
    base = sys.argv[1:]
    if base and base[0] == '--':
        base = base[1:]
    if not base:
        raise SystemExit(__doc__)
    results = [run(base, scenario) for scenario in ('full', 'cancel', 'unsupported', 'retry')]
    print(json.dumps({'schemaVersion': 1, 'mode': 'synthetic-readonly-pty', 'results': results}))


if __name__ == '__main__':
    main()
