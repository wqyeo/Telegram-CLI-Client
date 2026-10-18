#!/usr/bin/env python3
"""Cached-builder PUBLIC existing-binlog/replay tests only, no Client/account."""
import importlib.util
import json
import os
import re
import resource
import signal
import stat
import subprocess
import sys
from pathlib import Path

BASE = Path('/work/tdlib-a94b83d67ab4c8397c6245e541b31a4131ea2f79')
SRC, STAGE = Path('/g1'), Path('/stage')
SPEC = importlib.util.spec_from_file_location('public_native_helpers', SRC / 'run-sqlite.py')
assert SPEC and SPEC.loader
helpers = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(helpers)
digest, run, snapshot = helpers.digest, helpers.run, helpers.snapshot


def maintenance_snapshot(root):
    result = {}
    for path in sorted(root.iterdir()):
        st = path.lstat()
        directory = stat.S_ISDIR(st.st_mode)
        if not stat.S_ISREG(st.st_mode) and not (directory and path.name == 'store.bin.new'):
            raise RuntimeError('Unexpected PUBLIC maintenance node')
        if directory and any(path.iterdir()):
            raise RuntimeError('Unexpected nonempty PUBLIC replacement directory')
        result[path.name] = {'kind': 'directory' if directory else 'file',
                             'sha256': None if directory else digest(path), 'mode': st.st_mode,
                             'size': st.st_size, 'uid': st.st_uid, 'inode': st.st_ino}
    return result


def maintenance_checks(generator, binary, receipt):
    for name in ('healthy-compaction', 'collision', 'directory', 'rename-failure', 'rotation'):
        folder = STAGE / 'cases' / ('maintenance-' + name)
        folder.mkdir(mode=0o700)
        db = folder / 'store.bin'
        created = run([str(generator), 'seed', 'encrypted', str(db)], timeout=30)
        if created.stdout.strip() != 'PUBLIC_BINLOG_CREATED':
            raise RuntimeError('PUBLIC encrypted seed not confirmed')
        before = maintenance_snapshot(folder)
        try:
            checked = run([str(binary), name, str(db)], timeout=30)
            code, output, errors = checked.returncode, checked.stdout, checked.stderr
        except subprocess.CalledProcessError as error:
            code, output, errors = error.returncode, error.stdout, error.stderr
        after = maintenance_snapshot(folder)
        main = after.get('store.bin', {})
        replacement = after.get('store.bin.new', {})
        same_main_inode = main.get('inode') == before['store.bin']['inode']
        stopped = code == -signal.SIGABRT
        boundary = False
        mode = 'evidence-inodes-not-byte-atomicity'
        if name == 'healthy-compaction':
            boundary = (code == 0 and 'PUBLIC_COMPACTION_REOPEN_PASSED rows=3' in output
                        and bool(main) and not same_main_inode and not replacement)
            mode = 'healthy-maintenance-writes'
        elif name == 'rotation':
            boundary = (stopped and 'TGCLI_G1_BINLOG_KEY_ROTATION_REFUSED' in errors and before == after)
            mode = 'same-tree'
        elif name in ('collision', 'directory'):
            pattern = r'^PUBLIC_' + ('SENTINEL' if name == 'collision' else 'DIRECTORY') + r'_INODE=([0-9]{1,20})$'
            ready = re.search(pattern, output, re.MULTILINE)
            boundary = (stopped and 'TGCLI_G1_BINLOG_COMPACTION_REFUSED' in errors and same_main_inode
                        and ready is not None and replacement.get('inode') == int(ready.group(1)))
            if name == 'collision':
                boundary = boundary and Path(str(db) + '.new').read_bytes() == b'PUBLIC_KEEP_REPLACEMENT'
            else:
                boundary = boundary and replacement.get('kind') == 'directory'
        elif name == 'rename-failure':
            boundary = (stopped and 'PUBLIC_RENAME_FAULT_ARMED' in output
                        and 'TGCLI_G1_BINLOG_COMPACTION_RENAME_REFUSED' in errors
                        and same_main_inode and replacement.get('kind') == 'file')
        receipt['cases'].append({'case': 'maintenance/' + name, 'exitCode': code, 'passed': bool(boundary),
                                 'preservationMode': mode, 'preserved': before == after,
                                 'boundaryVerified': bool(boundary), 'stdout': output[-4096:],
                                 'stderr': errors[-4096:], 'before': before, 'after': after})
        (STAGE / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print('PASS' if boundary else 'FAIL', 'maintenance/' + name, 'exit=' + str(code), flush=True)


def main():
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    if len(sys.argv) not in (2, 3) or sys.argv[1] not in ('stock', 'g1'):
        raise RuntimeError('Explicit stock/g1 variant required')
    component = sys.argv[2] if len(sys.argv) == 3 else 'replay'
    if component not in ('replay', 'maintenance', 'creation', 'policy'):
        raise RuntimeError('Unknown PUBLIC suite')
    policy = component == 'policy'
    creation = component in ('creation', 'policy')
    maintenance = component in ('maintenance', 'creation', 'policy')
    variant = sys.argv[1]
    if creation and variant != 'g1':
        raise RuntimeError('New API has no stock implementation; no behavioral RED claimed')
    try:
        manifest_name = ('policy-base.json' if policy else
                         ('new-binlog-base.json' if creation else ('maintenance-base.json' if maintenance else 'binlog-base.json')))
        manifest = json.loads((SRC / manifest_name).read_text())
    except (OSError, ValueError):
        raise RuntimeError('Binlog hardening manifest unavailable or invalid') from None
    for rel, expected in {**manifest['sourceHashes'], **manifest.get('referenceHashes', {})}.items():
        if digest(BASE / rel) != expected:
            raise RuntimeError('Original binlog source/header/reference mismatch')
    if maintenance and digest(SRC / 'binlog-g1.patch') != manifest['basePatchSha256']:
        raise RuntimeError('B1b base patch changed; refuse cumulative build')
    if creation and digest(SRC / 'binlog-maintenance-g1.patch') != manifest['maintenancePatchSha256']:
        raise RuntimeError('B1c patch changed; refuse cumulative NEW build')
    if policy and digest(SRC / 'binlog-new-g1.patch') != manifest['newPatchSha256']:
        raise RuntimeError('B1e patch changed; refuse policy/layout binding build')
    build = STAGE / 'build'
    build.mkdir(mode=0o700)
    common = ['g++', '-std=c++17', '-O1', '-fno-exceptions', '-fno-rtti',
              '-D_FILE_OFFSET_BITS=64', '-D_GNU_SOURCE', '-Wall', '-Wextra', '-Wno-unused-parameter']
    for rel in ('tddb', 'tdactor', 'tdutils', 'jnibuild/tdutils', 'sqlite'):
        common.append('-I' + str(BASE / rel))
    libs = [BASE / 'jnibuild' / rel for rel in ('tddb/libtddb.a', 'tdactor/libtdactor.a',
                                               'tdutils/libtdutils.a', 'sqlite/libtdsqlite.a')]
    link = ['-Wl,--start-group', *map(str, libs), '-Wl,--end-group',
            '-lssl', '-lcrypto', '-lz', '-ldl', '-lpthread', '-latomic']

    def compile_logged(command, log):
        try:
            result = run(command)
        except subprocess.CalledProcessError as error:
            log.write_text(error.stdout + error.stderr)
            raise
        log.write_text(result.stdout + result.stderr)

    # Only this labelled stock generator can create PUBLIC seed files. It is not
    # an engine NEW entrypoint. The G1 checker opens separately generated files.
    generator = build / 'stock-public-generator'
    compile_logged(common + [str(SRC / 'binlog-public-fixture.cpp'), *link, '-o', str(generator)],
                   build / 'generator-link.log')
    binary = generator
    objects = []
    flags = common
    if variant == 'g1':
        patched = build / 'patched'
        for rel in manifest['sourceHashes']:
            target = patched / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((BASE / rel).read_bytes())
        applied = run(['patch', '--batch', '--fuzz=0', '-p1', '-d', str(patched),
                       '-i', str(SRC / 'binlog-g1.patch')])
        (build / 'patch.log').write_text(applied.stdout + applied.stderr)
        if maintenance:
            applied_extra = run(['patch', '--batch', '--fuzz=0', '-p1', '-d', str(patched),
                                 '-i', str(SRC / 'binlog-maintenance-g1.patch')])
            (build / 'maintenance-patch.log').write_text(applied_extra.stdout + applied_extra.stderr)
        if creation:
            applied_new = run(['patch', '--batch', '--fuzz=0', '-p1', '-d', str(patched),
                               '-i', str(SRC / 'binlog-new-g1.patch')])
            (build / 'new-patch.log').write_text(applied_new.stdout + applied_new.stderr)
            (patched / 'tddb/td/db/G1StoreAdmission.h').write_bytes((SRC / 'store-admission.h').read_bytes())
        flags = [*common, '-I' + str(patched / 'tddb')]
        # Put the paired patched header first, not after the cached-header roots.
        flags.insert(1, flags.pop())
        obj = build / 'binlog.o'
        compile_logged(flags + ['-c', str(patched / 'tddb/td/db/binlog/Binlog.cpp'), '-o', str(obj)],
                       build / 'binlog-compile.log')
        objects = [str(obj)]
        binary = build / 'g1-public-checker'
        compile_logged(flags + [str(SRC / 'binlog-public-fixture.cpp'), str(obj), *link, '-o', str(binary)],
                       build / 'checker-link.log')
    maintenance_binary = None
    if maintenance:
        maintenance_binary = build / 'maintenance-public-checker'
        compile_logged(flags + [str(SRC / 'maintenance-public-fixture.cpp'), *objects, *link,
                                '-Wl,--wrap=rename', '-o', str(maintenance_binary)],
                       build / 'maintenance-link.log')
    (build / 'compiler.txt').write_text(run(['g++', '--version']).stdout)
    cases = [('missing', None), ('empty', None), ('promote', 'plain'), ('ambiguous', 'plain'),
             ('tail', 'plain'), ('crc', 'plain'), ('processor', 'processor'), ('partial', 'partial'),
             ('wrong-key', 'encrypted'), ('old-key', 'encrypted'), ('format-migration', 'plain'),
             ('debug-callback', 'plain'), ('healthy', 'encrypted')]
    (STAGE / 'cases').mkdir(mode=0o700)
    receipt = {'variant': variant, 'scope': manifest['scope'],
               'clientCreated': False, 'publicFixturesOnly': True, 'cases': [], 'allPassed': False,
               'baseLibraryHashes': {str(p): digest(p) for p in libs},
               'patchSha256': digest(SRC / 'binlog-g1.patch'),
               'generatorBinarySha256': digest(generator), 'checkerBinarySha256': digest(binary)}
    for name, seed in cases:
        folder = STAGE / 'cases' / name
        folder.mkdir(mode=0o700)
        db = folder / 'store.bin'
        if seed:
            target = Path(str(db) + '.new') if name == 'promote' else db
            created = run([str(generator), 'seed', seed, str(target)], timeout=30)
            if created.stdout.strip() != 'PUBLIC_BINLOG_CREATED':
                raise RuntimeError('PUBLIC generator not confirmed')
        if name == 'empty':
            db.write_bytes(b'')
        elif name == 'ambiguous':
            Path(str(db) + '.new').write_bytes(db.read_bytes())
        elif name == 'tail':
            with db.open('ab') as stream:
                stream.write(b'\0\0\0\0')
        elif name == 'crc':
            data = bytearray(db.read_bytes())
            data[-1] ^= 128
            db.write_bytes(data)
        before = snapshot(folder)
        try:
            checked = run([str(binary), 'check', name, str(db)], timeout=30)
            code, output, errors = checked.returncode, checked.stdout, checked.stderr
        except subprocess.CalledProcessError as error:
            code, output, errors = error.returncode, error.stdout, error.stderr
        after = snapshot(folder)
        unchanged = name != 'healthy'
        preserved = before == after
        passed = code == 0 and (preserved or not unchanged)
        receipt['cases'].append({'case': name, 'exitCode': code, 'passed': passed,
                                 'preservationRequired': unchanged, 'preserved': preserved,
                                 'stdout': output[-4096:], 'stderr': errors[-4096:],
                                 'before': before, 'after': after})
        (STAGE / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print('PASS' if passed else 'FAIL', name, 'exit=' + str(code), 'preserved=' + str(preserved), flush=True)
    if maintenance:
        receipt['maintenancePatchSha256'] = digest(SRC / 'binlog-maintenance-g1.patch')
        receipt['maintenanceBinarySha256'] = digest(maintenance_binary)
        maintenance_checks(generator, maintenance_binary, receipt)
    if creation:
        spec = importlib.util.spec_from_file_location('public_new_binlog_checks', SRC / 'run-new-binlog.py')
        assert spec and spec.loader
        suite = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(suite)
        suite.checks(flags, objects, link, compile_logged, receipt)
        receipt['newPatchSha256'] = digest(SRC / 'binlog-new-g1.patch')
    if policy:
        spec = importlib.util.spec_from_file_location('public_policy_checks', SRC / 'run-policy.py')
        if not spec or not spec.loader:
            raise RuntimeError('PUBLIC policy runner unavailable; no fallback')
        suite = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(suite)
        suite.checks(flags, objects, link, compile_logged, receipt)
    receipt['allPassed'] = all(c['passed'] for c in receipt['cases'])
    receipt['failedCases'] = [c['case'] for c in receipt['cases'] if not c['passed']]
    (STAGE / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return 0 if receipt['allPassed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
