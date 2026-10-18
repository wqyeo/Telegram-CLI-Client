#!/usr/bin/env python3
"""Cumulative PUBLIC NEW-binlog checks, called only by the isolated creation runner."""
import importlib.util
import json
import os
import selectors
import subprocess
import sys
import time
from pathlib import Path

SRC, STAGE = Path('/g1'), Path('/stage')


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if not spec or not spec.loader:
        raise RuntimeError('PUBLIC fixture module unavailable; no fallback')
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def ready(process):
    """Bounded PUBLIC setup barrier; never readline on a partial pipe record."""
    data = b''
    deadline = time.monotonic() + 10
    with selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ)
        while b'\n' not in data:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not selector.select(remaining):
                raise RuntimeError('PUBLIC fixture setup barrier timed out')
            block = os.read(process.stdout.fileno(), 64)
            if not block:
                raise RuntimeError('PUBLIC fixture exited before setup barrier')
            data += block
            if len(data) > 64:
                raise RuntimeError('PUBLIC fixture setup barrier exceeded bound')
    if data != b'PUBLIC_NEW_READY\n':
        raise RuntimeError('Unexpected PUBLIC setup barrier')


def checks(flags, objects, link, compile_logged, receipt):
    record = module('public_record_regression', SRC / 'run-admission.py')
    binary = STAGE / 'build/new-binlog-fixture'
    command = [*flags, str(SRC / 'new-binlog-public-fixture.cpp'), *objects, *link,
               '-Wl,--wrap=open64', '-Wl,--wrap=write', '-Wl,--wrap=read', '-Wl,--wrap=fsync', '-o', str(binary)]
    (STAGE / 'build/new-command.json').write_text(json.dumps(command) + '\n')
    compile_logged(command, STAGE / 'build/new-link.log')
    receipt['newFixtureBinarySha256'] = record.digest(binary)
    positive = ('healthy-raw', 'healthy-password', 'healthy-test-dc', 'repeat-success')
    gates = ('main-file', 'main-symlink', 'main-directory', 'main-fifo', 'replacement', 'sqlite', 'sqlite-wal',
             'unknown-root', 'unknown-parent', 'parent-mode', 'root-mode', 'record-mode', 'renamed-root',
             'record-link', 'record-mutation', 'already-sealed')
    reasons: dict[str, str] = dict.fromkeys(gates, 'TGCLI_G1_BINLOG_NEW_CAPABILITY_REFUSED')
    reasons.update(dict.fromkeys(('existing-slot', 'partial-slot'), 'PUBLIC_RESERVATION_REFUSED'))
    reasons.update(dict.fromkeys(('empty-key', 'short-key', 'cache-flags', 'failed-then-filled'),
                                 'TGCLI_G1_BINLOG_NEW_CONFIGURATION_REFUSED'))
    reasons.update({'fail-after-format': 'PUBLIC_FORMAT_FAILURE_LATCHED',
                    'open-failure': 'TGCLI_G1_BINLOG_NEW_CREATE_REFUSED',
                    'write-failure': 'TGCLI_G1_BINLOG_NEW_WRITE_REFUSED',
                    'zero-write': 'TGCLI_G1_BINLOG_NEW_WRITE_REFUSED',
                    'partial-write': 'TGCLI_G1_BINLOG_NEW_WRITE_REFUSED',
                    'sync-failure': 'TGCLI_G1_BINLOG_NEW_SYNC_REFUSED',
                    'readback-failure': 'TGCLI_G1_BINLOG_NEW_VALIDATION_REFUSED'})
    faults = ('open-failure', 'write-failure', 'zero-write', 'partial-write', 'sync-failure', 'readback-failure')
    mutating = set(positive) | {'write-failure', 'zero-write', 'partial-write', 'sync-failure',
                                'readback-failure', 'failed-then-filled', 'fail-after-format'}
    for name in (*positive, *reasons):
        folder = STAGE / 'cases' / ('new-' + name)
        folder.mkdir(mode=0o700)
        (folder / 'namespace').mkdir(mode=0o700)
        process = subprocess.Popen([str(binary), name, str(folder)], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            ready(process)
            before = record.snapshot(folder)
            stdout, stderr = process.communicate(b'CONTINUE\n', timeout=20)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate(timeout=5)
        after = record.snapshot(folder)
        expected_hits = (2 if name == 'partial-write' else 1) if name in faults else 0
        expected_exit = 0 if name in positive else 20
        expected_stdout = ('PUBLIC_NEW_ACCEPTED rows=1 wrong_callbacks=0 hits=0\n' if name in positive else
                           'PUBLIC_NEW_REFUSED reason=' + reasons[name] + ' hits=' + str(expected_hits) + '\n')
        expected_stderr = (('PUBLIC_NEW_FAULT=' + name + '\n') * expected_hits).encode()
        passed = (process.returncode == expected_exit and stdout == expected_stdout.encode() and stderr == expected_stderr)
        readonly = name not in mutating
        preserved = before == after
        passed = passed and (preserved or not readonly)
        native = folder / 'namespace/native'
        main = native / ('td_test.binlog' if name == 'healthy-test-dc' else 'td.binlog')
        admission = native / 'g1-admission'
        if name in positive:
            config_flags = 8 if name == 'healthy-test-dc' else 0
            expected = (b'TGCG1ADM' + (1).to_bytes(4, 'little') * 2 + (77).to_bytes(4, 'little') +
                        config_flags.to_bytes(4, 'little') + b'P' * 16 +
                        b'a94b83d67ab4c8397c6245e541b31a4131ea2f79' + b'S' * 32 + b'G1SEALED')
            passed = passed and admission.read_bytes() == expected and main.stat().st_size > 0
            passed = passed and sorted(p.name for p in native.iterdir()) == sorted(['g1-admission', main.name])
        elif name not in ('existing-slot', 'partial-slot', 'renamed-root', 'already-sealed'):
            passed = passed and admission.stat().st_size == 112
        if name in ('write-failure', 'zero-write'):
            passed = passed and main.stat().st_size == 0
        if name == 'partial-write':
            passed = passed and main.stat().st_size == 8
        if name in ('sync-failure', 'readback-failure', 'fail-after-format'):
            data = main.read_bytes()
            passed = passed and len(data) > 8 and int.from_bytes(data[:4], 'little') == len(data)
        if name == 'failed-then-filled':
            passed = passed and main.read_bytes() == b'PUBLIC_FORMAT_FAILURE_EVIDENCE'
        case = {'case': 'new/' + name, 'exitCode': process.returncode, 'expectedExitCode': expected_exit,
                'passed': bool(passed), 'preservationRequired': readonly, 'preserved': preserved,
                'expectedFaultHits': expected_hits, 'stdout': stdout.decode(errors='replace')[-4096:],
                'stderr': stderr.decode(errors='replace')[-4096:], 'before': before, 'after': after}
        receipt['cases'].append(case)
        (STAGE / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print('PASS' if passed else 'FAIL', case['case'], 'exit=' + str(process.returncode), flush=True)

    # Changed reservation header: recheck the original 206 record primitives with
    # this exact frozen header, not the accepted historical B1d binary/receipt.
    regression = STAGE / 'record-regression'
    regression.mkdir(mode=0o700)
    original = sys.argv[:]
    try:
        sys.argv[:] = ['run-admission.py', 'record-only']
        code = record.main(stage=regression)
    finally:
        sys.argv[:] = original
    try:
        result = json.loads((regression / 'result.json').read_text())
    except (OSError, ValueError):
        raise RuntimeError('Admission-record regression receipt unavailable or invalid') from None
    if not isinstance(result, dict):
        raise RuntimeError('Admission-record regression receipt is not an object')
    if code != 0 or not result['allPassed'] or len(result['cases']) != 206:
        raise RuntimeError('Admission-record regression failed; not accepted')
    receipt['recordRegression'] = {'path': str(regression / 'result.json'),
                                   'sha256': record.digest(regression / 'result.json'), 'caseCount': 206}
    for case in result['cases']:
        receipt['cases'].append({**case, 'case': 'record/' + case['case']})
