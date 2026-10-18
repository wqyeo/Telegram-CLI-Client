#!/usr/bin/env python3
"""Inside the cached builder only. PUBLIC SQLite component tests, no Client/network."""
import hashlib
import json
import os
import resource
import subprocess
import sys
from pathlib import Path

BASE = Path('/work/tdlib-a94b83d67ab4c8397c6245e541b31a4131ea2f79')
STAGE = Path('/stage')
SRC = Path('/g1')

def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def run(args, timeout=120):
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=timeout,
                          env={'PATH': '/usr/bin:/bin', 'LC_ALL': 'C', 'HOME': '/nonexistent'})

def snapshot(root):
    result = {}
    for path in sorted(root.iterdir()):
        if not path.is_file() or path.is_symlink():
            raise RuntimeError('Unexpected PUBLIC fixture file type')
        st = path.stat()
        result[path.name] = {'sha256': digest(path), 'size': st.st_size,
                             'mode': st.st_mode, 'uid': st.st_uid, 'inode': st.st_ino}
    return result

def main():
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    if len(sys.argv) != 2 or sys.argv[1] not in ('stock', 'g1'):
        raise RuntimeError('Explicit stock/g1 component test variant required')
    variant = sys.argv[1]
    try:
        manifest = json.loads((SRC / 'base.json').read_text())
    except (OSError, ValueError):
        raise RuntimeError('Native hardening base manifest is unavailable or invalid') from None
    for rel, expected in manifest['sourceHashes'].items():
        if digest(BASE / rel) != expected:
            raise RuntimeError('Base source hash mismatch')
    build = STAGE / 'build'
    build.mkdir(mode=0o700)  # No reuse/overwriting of partial stages.
    common = ['g++', '-std=c++17', '-O1', '-fno-exceptions', '-fno-rtti',
              '-D_FILE_OFFSET_BITS=64', '-D_GNU_SOURCE', '-Wall', '-Wextra', '-Wno-unused-parameter']
    for rel in ('tddb', 'tdactor', 'tdutils', 'jnibuild/tdutils', 'sqlite'):
        common.append('-I' + str(BASE / rel))
    objects = []
    if variant == 'g1':
        patched = build / 'patched'
        for rel in manifest['sourceHashes']:
            target = patched / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((BASE / rel).read_bytes())
        # Exact hash-checked sources; no fuzz/offset acceptance of unexpected patches.
        patched_result = run(['patch', '--batch', '--fuzz=0', '-p1', '-d', str(patched),
                              '-i', str(SRC / 'sqlite-g1.patch')])
        (build / 'patch.log').write_text(patched_result.stdout + patched_result.stderr)
        for index, rel in enumerate(manifest['sourceHashes']):
            obj = build / (str(index) + '.o')
            compiled = run(common + ['-c', str(patched / rel), '-o', str(obj)])
            (build / ('compile-' + str(index) + '.log')).write_text(compiled.stdout + compiled.stderr)
            objects.append(str(obj))
    binary = build / 'sqlite-public-fixture'
    libs = [BASE / 'jnibuild' / rel for rel in ('tddb/libtddb.a', 'tdactor/libtdactor.a',
                                               'tdutils/libtdutils.a', 'sqlite/libtdsqlite.a')]
    command = common + [str(SRC / 'sqlite-public-fixture.cpp'), *objects, '-Wl,--start-group']
    command += [str(p) for p in libs]
    command += ['-Wl,--end-group', '-lssl', '-lcrypto', '-lz', '-ldl', '-lpthread', '-latomic', '-o', str(binary)]
    try:
        built = run(command)
    except subprocess.CalledProcessError as error:
        (build / 'link.log').write_text(error.stdout + error.stderr)
        raise
    (build / 'link.log').write_text(built.stdout + built.stderr)
    (build / 'compiler.txt').write_text(run(['g++', '--version']).stdout)
    cases = [('corrupt', 'corrupt', True), ('missing', None, True), ('missing-existing', None, True),
             ('empty-existing', None, True),
             ('compat3', 'cipher3', True), ('explicit-compat3', 'cipher3', True),
             ('wrong-key', 'cipher4', True), ('rekey', 'cipher4', True),
             ('decrypt', 'cipher4', True), ('encrypt', 'plain', True),
             ('healthy', 'cipher4', False), ('current-key', 'cipher4', False)]
    (STAGE / 'cases').mkdir(mode=0o700)
    receipt = {'variant': variant, 'scope': 'B1a SQLite components only; not engine-wide G1',
               'clientCreated': False, 'publicFixturesOnly': True, 'cases': [],
               'baseLibraryHashes': {str(p): digest(p) for p in libs}, 'binarySha256': digest(binary),
               'patchSha256': digest(SRC / 'sqlite-g1.patch'), 'allPassed': False}
    for name, fixture, unchanged in cases:
        folder = STAGE / 'cases' / name
        folder.mkdir(mode=0o700)
        db = folder / 'store.db'
        if fixture:
            made = run([str(binary), 'setup', fixture, str(db)], timeout=30)
            if made.stdout.strip() != 'PUBLIC_FIXTURE_CREATED':
                raise RuntimeError('Fixture setup not confirmed')
        if name == 'empty-existing':
            db.write_bytes(b'')
        if name == 'missing':
            for suffix in ('-wal', '-shm', '-journal', '.encrypted'):
                (folder / ('store.db' + suffix)).write_bytes(b'PUBLIC_ORPHAN_SENTINEL')
        if name in ('rekey', 'decrypt', 'encrypt'):
            (folder / 'store.db.encrypted').write_bytes(b'PUBLIC_MIGRATION_REMNANT_KEEP')
        before = snapshot(folder)
        try:
            result = run([str(binary), 'check', name, str(db)], timeout=30)
            code, output, errors = result.returncode, result.stdout, result.stderr
        except subprocess.CalledProcessError as error:
            code, output, errors = error.returncode, error.stdout, error.stderr
        after = snapshot(folder)
        preserved = before == after
        passed = code == 0 and (preserved or not unchanged)
        receipt['cases'].append({'case': name, 'exitCode': code, 'passed': passed,
                                 'preservationRequired': unchanged, 'preserved': preserved,
                                 'stdout': output[-4096:], 'stderr': errors[-4096:],
                                 'before': before, 'after': after})
        (STAGE / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print(('PASS' if passed else 'FAIL'), name, 'exit=' + str(code), 'preserved=' + str(preserved), flush=True)
    receipt['allPassed'] = all(c['passed'] for c in receipt['cases'])
    receipt['failedCases'] = [c['case'] for c in receipt['cases'] if not c['passed']]
    (STAGE / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return 0 if receipt['allPassed'] else 1

if __name__ == '__main__':
    sys.exit(main())
