#!/usr/bin/env python3
"""PUBLIC context primitives only; seal handoffs simulated, account engine never linked."""
import hashlib
import hmac
import json
import stat
import struct
import subprocess
import zlib
from pathlib import Path

SRC = Path('/g1')
OUT = Path('/stage')
BASE = Path('/work/tdlib-a94b83d67ab4c8397c6245e541b31a4131ea2f79')
CASES = ('wrong-device', 'wrong-inode', 'unsafe-profile', 'namespace-exists', 'existing-missing',
         'bad-generation', 'bad-key', 'new-pending', 'seal-hook-simulated', 'runtime-temp-simulated',
         'registry-abandon', 'registry-taken', 'files-replaced', 'existing-header-simulated')
READ_ONLY = set(CASES[:6])

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def tree(root):
    result = {}
    for node in [root, *sorted(root.rglob('*'))]:
        attrs = node.lstat()
        if not (stat.S_ISDIR(attrs.st_mode) or stat.S_ISREG(attrs.st_mode)):
            raise RuntimeError('Unexpected PUBLIC fixture node')
        result[str(node.relative_to(root))] = {'mode': attrs.st_mode, 'uid': attrs.st_uid,
            'gid': attrs.st_gid, 'device': attrs.st_dev, 'inode': attrs.st_ino, 'links': attrs.st_nlink,
            'size': attrs.st_size, 'mtimeNs': attrs.st_mtime_ns,
            'sha256': digest(node) if node.is_file() else None}
    return result

def public_header_checks(path: Path) -> dict[str, bool]:
    """Pinned native header only, with PUBLIC raw-A key; no account records/codecs."""
    checks: dict[str, bool] = dict.fromkeys(('present', 'size128', 'framing', 'crc32', 'serviceFlags',
                            'tlLayout', 'publicRawKeyHash'), False)
    checks['present'] = path.is_file()
    if not checks['present']:
        return checks
    with path.open('rb') as stream:
        data = stream.read(129)
    checks['size128'] = len(data) == 128
    if not checks['size128']:
        return checks
    # 28 framing + 4 flags + 36 salt + 20 IV + 36 hash + 4 CRC = 128.
    # B1e's 160-byte healthy fixture additionally appends a 32-byte user event.
    checks['framing'] = struct.unpack_from('<IQiiQ', data, 0) == (128, 0, -3, 0, 0)
    checks['crc32'] = struct.unpack_from('<I', data, 124)[0] == zlib.crc32(data[:124])
    checks['serviceFlags'] = struct.unpack_from('<I', data, 28)[0] == 0
    offset = 32
    values = []
    for size in (32, 16, 32):
        padding = (-(size + 1)) % 4
        if (data[offset] != size or
                data[offset + size + 1:offset + size + 1 + padding] != b'\0' * padding):
            return checks
        values.append(data[offset + 1:offset + 1 + size])
        offset += 1 + size + padding
    checks['tlLayout'] = offset == 124
    salt, _, key_hash = values
    public_key = hashlib.pbkdf2_hmac('sha256', b'A' * 32, salt, 2, dklen=32)
    checks['publicRawKeyHash'] = hmac.compare_digest(
        hmac.digest(public_key, b'cucumbers everywhere', 'sha256'), key_hash)
    return checks


def main(flags):
    binary = OUT / 'public-context-checker'
    libraries = [BASE / path for path in ('jnibuild/tddb/libtddb.a', 'jnibuild/tdactor/libtdactor.a',
                  'jnibuild/tdutils/libtdutils.a', 'jnibuild/sqlite/libtdsqlite.a')]
    command = [*flags, '-Werror', '-I/stage/source/td/telegram/g1', str(SRC / 'context-public-fixture.cpp'),
               *[str(OUT / (str(i) + '.o')) for i in range(3)], *map(str, libraries),
               '-lcrypto', '-lz', '-pthread', '-o', str(binary)]
    (OUT / 'context-link-command.json').write_text(json.dumps(command, indent=2) + '\n')
    checked = subprocess.run(command, capture_output=True, timeout=90)
    (OUT / 'context-link.log').write_bytes(checked.stdout + checked.stderr)
    if checked.returncode != 0:
        raise RuntimeError('PUBLIC context fixture compile/link failed; no runtime checks passed')
    # Check symbol inventory, without executing or loading a client/SDK.
    symbols = subprocess.run(['nm', '-C', str(binary)], check=True, capture_output=True, text=True, timeout=20).stdout
    (OUT / 'context-symbols.txt').write_text(symbols)
    if 'td::TdDb::' in symbols or 'td::ClientManager::' in symbols:
        raise RuntimeError('Account/client symbols unexpectedly linked')
    results = []
    for mode in CASES:
        case = OUT / 'cases' / mode
        case.mkdir(parents=True, mode=0o700)
        profile = case / 'PUBLIC'
        profile.mkdir(mode=0o700)
        with (profile / 'PUBLIC_LEASE').open('xb'):
            pass
        (profile / 'PUBLIC_LEASE').chmod(0o600)
        if mode == 'unsafe-profile':
            profile.chmod(0o750)
        if mode == 'namespace-exists':
            (profile / 'native-g1').mkdir(mode=0o700)
        before = tree(profile)
        error = None
        exit_code = None
        exact = False
        physical = False
        physical_checks: dict[str, bool] = {}
        try:
            completed = subprocess.run([str(binary), str(profile), mode], capture_output=True, timeout=8)
            (case / 'stdout').write_bytes(completed.stdout)
            (case / 'stderr').write_bytes(completed.stderr)
            exit_code = completed.returncode
            after = tree(profile)
            exact = (completed.returncode == 0 and completed.stderr == b'' and completed.stdout ==
                     ('PUBLIC_CONTEXT_ACCEPTED case=' + mode + ' account_engine=0\n').encode())
            physical_checks = {'readOnlyTreeUnchanged': before == after} if mode in READ_ONLY else {}
            record = profile / 'native-g1/native/g1-admission'
            if mode not in READ_ONLY:
                data = record.read_bytes()
                sealed = mode in {'seal-hook-simulated', 'runtime-temp-simulated', 'files-replaced', 'existing-header-simulated'}
                physical_checks['admissionRecord'] = (
                    len(data) == (120 if sealed else 112) and data[:8] == b'TGCG1ADM' and
                    data[40:80] == b'a94b83d67ab4c8397c6245e541b31a4131ea2f79' and
                    (not sealed or data[112:] == b'G1SEALED'))
                main_file = profile / 'native-g1/native/td.binlog'
                has_main = mode not in {'bad-key', 'registry-abandon', 'registry-taken'}
                if has_main:
                    physical_checks.update(public_header_checks(main_file))
                else:
                    physical_checks['mainAbsent'] = not main_file.exists()
            physical = all(physical_checks.values())
            passed = exact and physical
        except (OSError, subprocess.TimeoutExpired) as failure:
            error = type(failure).__name__
            passed = False
            after = tree(profile)
        result = {'case': mode, 'passed': passed, 'readOnlyRequired': mode in READ_ONLY,
                  'exitCode': exit_code, 'exactProcessOutcome': exact, 'physicalOutcome': physical,
                  'physicalChecks': physical_checks,
                  'before': before, 'after': after, 'infrastructureError': error,
                  'tdDbOrClientExecuted': False, 'authorizationHandoff': 'SIMULATED_ONLY'}
        (case / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        results.append({'case': mode, 'passed': passed})
        print('PUBLIC_CONTEXT_CHECK', mode, 'PASS' if passed else 'FAIL', flush=True)
    summary = {'scope': 'PUBLIC low-level context/identity/registry tests; no actual account data/authorization/compaction engine',
               'tdDbOrClientExecuted': False, 'sdkActivated': False, 'clientEngineSymbolsLinked': False,
               'binarySha256': digest(binary), 'checks': results, 'allPassed': all(x['passed'] for x in results)}
    (OUT / 'context-result.json').write_text(json.dumps(summary, indent=2) + '\n')
    return 0 if summary['allPassed'] else 1
