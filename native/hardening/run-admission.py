#!/usr/bin/env python3
"""Container-only PUBLIC native admission-record tests; no TDLib database execution."""
import hashlib
import json
import os
import stat
import subprocess
import sys
from pathlib import Path


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def snapshot(root):
    result = {}
    for directory, dirs, files in os.walk(root, followlinks=False):
        for path in [Path(directory)] + [Path(directory) / n for n in dirs + files]:
            info = path.lstat()
            key = str(path.relative_to(root))
            result[key] = {'mode': info.st_mode, 'uid': info.st_uid, 'inode': info.st_ino,
                           'size': info.st_size, 'links': info.st_nlink,
                           'sha256': digest(path) if stat.S_ISREG(info.st_mode) else None,
                           'target': os.readlink(path) if stat.S_ISLNK(info.st_mode) else None}
    return result


def main(*, stage=Path('/stage')):
    os.umask(0o077)
    if sys.argv[1:] != ['record-only'] or stage not in (Path('/stage'), Path('/stage/record-regression')):
        raise SystemExit('PUBLIC record-only invocation and fixed stage required.')
    code = Path('/g1')
    build, cases = stage / 'build', stage / 'cases'
    build.mkdir(mode=0o700)
    cases.mkdir(mode=0o700)
    compiler = subprocess.run(['g++', '--version'], capture_output=True, check=True, timeout=20)
    (build / 'compiler.txt').write_bytes(compiler.stdout)
    executable = build / 'admission-fixture'
    command = ['g++', '-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror', '-pedantic',
               str(code / 'admission-public-fixture.cpp'), '-o', str(executable)]
    (build / 'command.json').write_text(json.dumps(command) + '\n')
    with (build / 'compile-link.log').open('wb') as log:
        compiled = subprocess.run(command, stdout=log, stderr=log, check=False, timeout=120)
    if compiled.returncode != 0:
        raise SystemExit('Native compile/link failed; not behavioral RED.')
    results = []

    def run(parent, mode, flavor='full'):
        outcome = subprocess.run([str(executable), mode, str(parent), flavor], check=False,
                                 capture_output=True, timeout=5)
        return {'exitCode': outcome.returncode, 'stdout': outcome.stdout.decode('utf-8', errors='replace'),
                'stderr': outcome.stderr.decode('utf-8', errors='replace')}

    def fresh(name):
        case_root = cases / name
        case_root.mkdir(mode=0o700)
        parent = case_root / 'namespace'
        parent.mkdir(mode=0o700)
        return parent

    def created(parent, flavor='full'):
        outcome = run(parent, 'create', flavor)
        if outcome['exitCode'] != 0:
            raise RuntimeError('PUBLIC fixture creation failed, not a refusal pass: ' + json.dumps(outcome))

    def record_case(name, parent, mode, expected, flavor='full', readonly=True, check=None):
        before = snapshot(parent.parent)
        outcome = run(parent, mode, flavor)
        after = snapshot(parent.parent)
        expected_marker = 'PUBLIC_RECORD_ACCEPTED\n' if expected == 0 else 'PUBLIC_RECORD_REFUSED\n'
        preserved = before == after
        passed = outcome['exitCode'] == expected and outcome['stdout'] == expected_marker
        passed = passed and (preserved or not readonly) and (check(parent) if check else True)
        results.append({'case': name, 'expectedExitCode': expected, 'passed': passed,
                        'preservationRequired': readonly, 'preserved': preserved, **outcome,
                        'before': before, 'after': after})
        print(('PASS ' if passed else 'FAIL ') + name, flush=True)

    for flavor in ('full', 'binlog-only', 'test-dc'):
        parent = fresh('healthy-' + flavor)
        record_case('new/' + flavor, parent, 'create', 0, flavor, readonly=False,
                    check=lambda p: (p / 'native/g1-admission').stat().st_size == 120)
        record_case('existing/' + flavor, parent, 'open', 0, flavor)
        # An existing committed root can never be reinterpreted as NEW.
        record_case('new-refuses-existing/' + flavor, parent, 'reserve', 20, flavor)

    parent = fresh('no-root')
    record_case('existing/no-root', parent, 'open', 20)
    parent = fresh('empty-root')
    (parent / 'native').mkdir(mode=0o700)
    record_case('existing/empty-root', parent, 'open', 20)
    record_case('new/refuses-empty-existing-root', parent, 'reserve', 20)
    parent = fresh('reservation-only')
    record_case('new/reserve-only', parent, 'reserve', 0, readonly=False,
                check=lambda p: (p / 'native/g1-admission').stat().st_size == 112)
    record_case('existing/incomplete-reservation', parent, 'open', 20)
    record_case('new/no-resume-partial', parent, 'reserve', 20)

    for flavor in ('wrong-flags', 'wrong-id', 'wrong-schema', 'wrong-version', 'invalid-flags', 'zero-id'):
        parent = fresh('mismatch-' + flavor)
        created(parent)
        record_case('existing/' + flavor, parent, 'open', 20, flavor)
    for flavor in ('invalid-flags', 'zero-id'):
        parent = fresh('invalid-new-' + flavor)
        record_case('new/' + flavor, parent, 'reserve', 20, flavor)
    parent = fresh('invalid-name')
    record_case('new/no-traversal', parent, 'reserve-invalid-name', 20)

    # Every byte is significant: versions, format, flags, identity, revision,
    # schema/layout ID and completion tag. One fresh PUBLIC fixture per mutation.
    for offset in range(120):
        parent = fresh('byte-' + str(offset))
        created(parent)
        path = parent / 'native/g1-admission'
        payload = bytearray(path.read_bytes())
        payload[offset] ^= 0x80
        path.write_bytes(payload)
        record_case('existing/changed-byte-' + str(offset), parent, 'open', 20)

    mutations = ('missing-record', 'short-record', 'long-record', 'record-symlink', 'record-hardlink',
                 'record-directory', 'record-fifo', 'record-mode', 'root-mode', 'parent-mode',
                 'missing-binlog', 'empty-binlog', 'missing-sqlite', 'empty-sqlite', 'component-symlink',
                 'component-hardlink', 'component-mode', 'replacement-file', 'replacement-directory',
                 'replacement-dangling-link', 'unknown-file', 'other-dc', 'root-symlink')
    for mutation in mutations:
        parent = fresh(mutation)
        created(parent)
        root, record = parent / 'native', parent / 'native/g1-admission'
        if mutation == 'missing-record':
            record.unlink()
        elif mutation == 'short-record':
            record.write_bytes(record.read_bytes()[:-1])
        elif mutation == 'long-record':
            record.write_bytes(record.read_bytes() + b'X')
        elif mutation in ('record-symlink', 'record-hardlink', 'record-directory', 'record-fifo'):
            record.rename(parent.parent / 'original-record')
            if mutation == 'record-symlink':
                record.symlink_to('../../original-record')
            elif mutation == 'record-hardlink':
                os.link(parent.parent / 'original-record', record)
            elif mutation == 'record-directory':
                record.mkdir(mode=0o700)
            else:
                os.mkfifo(record, 0o600)
        elif mutation.endswith('-mode'):
            target = {'record-mode': record, 'root-mode': root, 'parent-mode': parent,
                      'component-mode': root / 'td.binlog'}[mutation]
            target.chmod(0o755 if target.is_dir() else 0o644)
        elif mutation.startswith(('missing-', 'empty-')):
            target = root / ('td.binlog' if mutation.endswith('binlog') else 'db.sqlite')
            if mutation.startswith('missing-'):
                target.unlink()
            else:
                target.write_bytes(b'')
        elif mutation == 'component-symlink':
            (root / 'td.binlog').rename(parent.parent / 'original-binlog')
            (root / 'td.binlog').symlink_to('../../original-binlog')
        elif mutation == 'component-hardlink':
            os.link(root / 'td.binlog', parent.parent / 'binlog-alias')
        elif mutation == 'replacement-file':
            (root / 'td.binlog.new').write_bytes(b'PUBLIC_SENTINEL')
        elif mutation == 'replacement-directory':
            (root / 'td.binlog.new').mkdir(mode=0o700)
        elif mutation == 'replacement-dangling-link':
            (root / 'td.binlog.new').symlink_to('missing')
        elif mutation == 'unknown-file':
            (root / 'unexpected').write_bytes(b'PUBLIC')
        elif mutation == 'other-dc':
            (root / 'td.binlog').rename(root / 'td_test.binlog')
        elif mutation == 'root-symlink':
            root.rename(parent.parent / 'original-root')
            root.symlink_to('../original-root', target_is_directory=True)
        record_case('existing/' + mutation, parent, 'open', 20)
        record_case('new/refuses-' + mutation, parent, 'reserve', 20)

    for mode in ('commit-missing', 'commit-unknown', 'commit-replaced', 'commit-renamed', 'commit-parent-mode'):
        parent = fresh(mode)
        record_case('new/' + mode, parent, mode, 20, readonly=False,
                    check=lambda p: (p / ('moved' if (p / 'moved').exists() else 'native') /
                                     ('old-record' if (p / 'native/old-record').exists() else 'g1-admission')).stat().st_size == 112)
        record_case('new/no-retry-' + mode, parent, 'reserve', 20)
    parent = fresh('unknown-parent-entry')
    (parent / 'unexpected').write_bytes(b'PUBLIC_EVIDENCE')
    record_case('new/refuses-unknown-namespace', parent, 'reserve', 20)
    parent = fresh('existing-unknown-parent-entry')
    created(parent)
    (parent / 'unexpected').write_bytes(b'PUBLIC_EVIDENCE')
    record_case('existing/refuses-unknown-namespace', parent, 'open', 20)
    parent = fresh('repeat-commit')
    record_case('new/commit-at-most-once', parent, 'repeat-commit', 0, readonly=False)
    parent = fresh('failed-then-filled')
    record_case('new/failed-commit-consumes-capability', parent, 'failed-then-filled', 0, readonly=False,
                check=lambda p: (p / 'native/g1-admission').stat().st_size == 112)
    record_case('existing/failed-commit-still-incomplete', parent, 'open', 20)
    parent = fresh('sidecars')
    created(parent)
    for suffix in ('-wal', '-shm', '-journal'):
        (parent / ('native/db.sqlite' + suffix)).write_bytes(b'')
    record_case('existing/ordinary-sidecars', parent, 'open', 0)

    receipt = {'scope': 'G1 admission-record primitives only; NOT TDLib data validation or engine G1',
               'clientCreated': False, 'accountDatabaseCreated': False, 'publicFixturesOnly': True,
               'databaseSeedBytes': 'PUBLIC_NON_DATABASE_FIXTURE; deliberately not TDLib formats',
               'cases': results, 'allPassed': all(c['passed'] for c in results),
               'failedCases': [c['case'] for c in results if not c['passed']],
               'fixtureBinarySha256': digest(executable)}
    (stage / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return 0 if receipt['allPassed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
