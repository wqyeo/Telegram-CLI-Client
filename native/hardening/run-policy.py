#!/usr/bin/env python3
"""Opt-in PUBLIC policy checks, called only by the isolated cumulative native runner."""
import hashlib
import importlib.util
import json
import os
import selectors
import stat
import struct
import subprocess
import time
from pathlib import Path

SRC, STAGE = Path('/g1'), Path('/stage')
# Independent byte recipe, not read from the compiled policy or an admission record.
DESCRIPTOR = (
    b'telegram-cli:g1:binlog-only-layout:1\n'
    b'record=TGCG1ADM:1:112:120;layout-policy=1;schema-policy=1\n'
    b'namespace=native-g1;slot=native;record-name=g1-admission\n'
    b'components=td.binlog|td_test.binlog;sqlite-caches=0,0,0;dc=trusted-bit8\n'
    b'identity=sha256-first16:profile-path-dev-ino:namespace-path-dev-ino:v1\n'
    b'key=raw:32;empty-key-fallback=forbidden;alternate-key=forbidden\n'
    b'tdlib=a94b83d67ab4c8397c6245e541b31a4131ea2f79\n'
    b'b1b=2fb5c5caa744e4e398b2a718ef2014e5b82fa6c66cee55d8ba71cb6c10fa4fcc\n'
    b'b1c=9870eae3f971035f81399df4a79dcb8863cb609b291b68030e0489f726e7dbdf\n'
    b'b1e=acddad1cc9c33b7e0475e943749282de7eb3ab3ea843d90d16f4c143b08daba7\n'
)
DOMAIN = b'telegram-cli:g1:filesystem-store-id:1\n'


def identity(path, profile_info, namespace_info):
    name, location = b'PUBLIC-profile', str(path).encode()
    data = (DOMAIN + struct.pack('<I', len(name)) + name + struct.pack('<I', len(location)) + location +
            struct.pack('<QQQQ', profile_info.st_dev, profile_info.st_ino, namespace_info.st_dev, namespace_info.st_ino))
    return hashlib.sha256(data).digest()[:16]


def ready(process):
    data = b''
    deadline = time.monotonic() + 10
    with selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ)
        while b'\n' not in data:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not selector.select(remaining):
                raise RuntimeError('PUBLIC policy setup timed out')
            block = os.read(process.stdout.fileno(), 64)
            if not block:
                raise RuntimeError('PUBLIC policy setup exited early')
            data += block
            if len(data) > 64:
                raise RuntimeError('PUBLIC policy setup exceeded bound')
    if data != b'PUBLIC_POLICY_READY\n':
        raise RuntimeError('Unexpected PUBLIC policy setup marker')


def checks(flags, objects, link, compile_logged, receipt):
    spec = importlib.util.spec_from_file_location('policy_record_snapshot', SRC / 'run-admission.py')
    if not spec or not spec.loader:
        raise RuntimeError('Record snapshot helper unavailable')
    record = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(record)
    binary = STAGE / 'build/policy-public-checker'
    command = [*flags, '-Werror', str(SRC / 'binlog-only-policy.cpp'), str(SRC / 'policy-public-fixture.cpp'),
               *objects, *link, '-Wl,--wrap=open64', '-o', str(binary)]
    (STAGE / 'build/policy-command.json').write_text(json.dumps(command) + '\n')
    compile_logged(command, STAGE / 'build/policy-link.log')
    positive = ('healthy', 'test-dc', 'reopen', 'moved-capability', 'caller-fd-closed', 'key-independent',
                'identity-only-unknown', 'record-healthy', 'native-existing', 'header-roundtrip')
    context = ('fd-invalid', 'fd-other', 'fd-file', 'name-invalid', 'name-mismatch', 'path-relative',
               'path-dotdot', 'path-double', 'path-trailing', 'path-nul', 'profile-mode', 'namespace-mode',
               'profile-symlink', 'namespace-symlink')
    keys = ('key-empty', 'key-empty-raw', 'key-short', 'key-long', 'key-password',
            'key-stock-empty-fallback', 'native-password-refused')
    records = ('record-cloned', 'record-moved-profile', 'record-moved-namespace', 'record-foreign-id',
               'record-foreign-layout', 'record-cache-flags', 'record-dc-mismatch', 'record-missing',
               'record-partial', 'record-sqlite', 'record-remnant')
    late = ('late-namespace-moved', 'late-namespace-replaced', 'late-profile-replaced', 'late-namespace-mode')
    reasons = {**dict.fromkeys(context, 'CONTEXT'), **dict.fromkeys(keys, 'KEY'),
               **dict.fromkeys(records, 'RECORD'), **dict.fromkeys(late, 'CONTEXT'), 'native-wrong-key': 'BINLOG'}
    schema_id = hashlib.sha256(DESCRIPTOR).digest()
    receipt['policy'] = {'binarySha256': record.digest(binary), 'schemaId': schema_id.hex(),
                         'identityDomain': DOMAIN.decode(), 'caseCount': len(positive) + len(reasons),
                         'scope': 'Trusted expected configuration/identity and raw32 gate; not native/account schema certification'}
    for name in (*positive, *reasons):
        folder = STAGE / 'cases' / ('policy-' + name)
        folder.mkdir(mode=0o700)
        profile, space = folder / 'PUBLIC-profile', folder / 'PUBLIC-profile/native-g1'
        process = subprocess.Popen([str(binary), name, str(folder)], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        binding = None
        original_id = None
        try:
            ready(process)
            before = record.snapshot(folder)
            if stat.S_ISDIR(profile.lstat().st_mode) and stat.S_ISDIR(space.lstat().st_mode):
                pinfo, ninfo = profile.stat(), space.stat()
                expected_id = identity(space, pinfo, ninfo)
                binding = {'namespacePath': str(space), 'profileDevice': pinfo.st_dev, 'profileInode': pinfo.st_ino,
                           'namespaceDevice': ninfo.st_dev, 'namespaceInode': ninfo.st_ino, 'storeId': expected_id.hex()}
                if name == 'record-cloned':
                    original = folder / 'seed/PUBLIC-profile/native-g1'
                    original_id = identity(original, original.parent.stat(), original.stat())
                elif name == 'record-moved-profile':
                    original_id = identity(folder / 'seed/PUBLIC-profile/native-g1', pinfo, ninfo)
                elif name == 'record-moved-namespace':
                    original_id = identity(folder / 'seed/PUBLIC-profile/native-g1',
                                           (folder / 'seed/PUBLIC-profile').stat(), ninfo)
            stdout, stderr = process.communicate(b'CONTINUE\n', timeout=20)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate(timeout=5)
        after = record.snapshot(folder)
        expected_exit = 0 if name in positive else 20
        opens = 3 if name == 'header-roundtrip' else (1 if name in ('native-existing', 'native-wrong-key') else 0)
        config_flags = 8 if name == 'test-dc' else 0
        if name in positive:
            if binding is None:
                raise RuntimeError('Positive PUBLIC policy context unavailable')
            expected_stdout = ('PUBLIC_POLICY_ACCEPTED id=' + binding['storeId'] + ' schema=' + schema_id.hex() +
                               ' flags=' + str(config_flags) + ' opens=' + str(opens) + ' callbacks=0\n')
        else:
            expected_stdout = 'PUBLIC_POLICY_REFUSED reason=' + reasons[name] + ' opens=' + str(opens) + ' callbacks=0\n'
        readonly = name != 'header-roundtrip' and name not in late
        preserved = before == after
        passed = (process.returncode == expected_exit and stdout == expected_stdout.encode() and stderr == b'' and
                  (preserved or not readonly))
        boundary = True
        if name in ('record-healthy', 'native-existing', 'header-roundtrip'):
            if binding is None:
                raise RuntimeError('PUBLIC policy record context unavailable')
            expected_record = (b'TGCG1ADM' + struct.pack('<IIII', 1, 1, 1, 0) + bytes.fromhex(binding['storeId']) +
                               b'a94b83d67ab4c8397c6245e541b31a4131ea2f79' + schema_id + b'G1SEALED')
            boundary = (space / 'native/g1-admission').read_bytes() == expected_record
            main = (space / 'native/td.binlog').read_bytes()
            boundary = boundary and (main == b'PUBLIC_NON_DATABASE_FIXTURE' if name == 'record-healthy' else
                                     len(main) > 8 and int.from_bytes(main[:4], 'little') == len(main))
            boundary = boundary and sorted(p.name for p in (space / 'native').iterdir()) == ['g1-admission', 'td.binlog']
        if original_id is not None:
            if binding is None:
                raise RuntimeError('Copied PUBLIC policy context unavailable')
            data = (space / 'native/g1-admission').read_bytes()
            boundary = data[24:40] == original_id and data[24:40].hex() != binding['storeId']
        if name == 'identity-only-unknown':
            boundary = (space / 'PUBLIC_UNKNOWN').read_bytes() == b'PUBLIC_NOT_ADMITTED'
        if name == 'late-namespace-mode':
            boundary = stat.S_IMODE(space.stat().st_mode) == 0o750 and list(space.iterdir()) == []
        if name in ('late-namespace-moved', 'late-namespace-replaced'):
            if binding is None:
                raise RuntimeError('Moved PUBLIC namespace context unavailable')
            retained = profile / 'PUBLIC_RETAINED_NAMESPACE'
            boundary = retained.stat().st_ino == binding['namespaceInode'] and list(retained.iterdir()) == []
            if name.endswith('moved'):
                boundary = boundary and not space.exists() and sorted(p.name for p in profile.iterdir()) == ['PUBLIC_RETAINED_NAMESPACE']
            else:
                boundary = boundary and space.stat().st_ino != binding['namespaceInode'] and list(space.iterdir()) == []
                boundary = boundary and sorted(p.name for p in profile.iterdir()) == ['PUBLIC_RETAINED_NAMESPACE', 'native-g1']
        if name == 'late-profile-replaced':
            if binding is None:
                raise RuntimeError('Moved PUBLIC profile context unavailable')
            retained = folder / 'PUBLIC_RETAINED_PROFILE'
            boundary = (retained.stat().st_ino == binding['profileInode'] and
                        (retained / 'native-g1').stat().st_ino == binding['namespaceInode'] and
                        profile.stat().st_ino != binding['profileInode'] and
                        space.stat().st_ino != binding['namespaceInode'] and list(space.iterdir()) == [] and
                        sorted(p.name for p in folder.iterdir()) == ['PUBLIC-profile', 'PUBLIC_RETAINED_PROFILE'])
        passed = passed and boundary
        case = {'case': 'policy/' + name, 'exitCode': process.returncode, 'expectedExitCode': expected_exit,
                'expectedOpens': opens, 'passed': bool(passed), 'preservationRequired': readonly, 'preserved': preserved,
                'boundaryVerified': bool(boundary), 'binding': binding, 'stdout': stdout.decode(errors='replace'),
                'stderr': stderr.decode(errors='replace'), 'before': before, 'after': after}
        receipt['cases'].append(case)
        (STAGE / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print('PASS' if passed else 'FAIL', case['case'], 'exit=' + str(process.returncode), flush=True)
