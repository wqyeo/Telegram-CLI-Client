#!/usr/bin/env python3
"""PUBLIC real ConcurrentBinlog storage actors; no TdDb/Client/account execution."""
import hashlib
import json
import runpy
import subprocess
from pathlib import Path

SRC, OUT = Path('/g1'), Path('/stage')
BASE = Path('/work/tdlib-a94b83d67ab4c8397c6245e541b31a4131ea2f79')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main(flags):
    inspect = runpy.run_path(str(SRC / 'run-context.py'))
    original = BASE / 'tddb/td/db/binlog/ConcurrentBinlog.cpp'
    stock = OUT / 'stock-concurrent.o'
    command = [*flags, '-c', str(original), '-o', str(stock)]
    (OUT / 'stock-concurrent-command.json').write_text(json.dumps(command, indent=2) + '\n')
    compiled = subprocess.run(command, capture_output=True, timeout=100)
    (OUT / 'stock-concurrent-compile.log').write_bytes(compiled.stdout + compiled.stderr)
    if compiled.returncode != 0:
        raise RuntimeError('PUBLIC stock-actor control compilation failed; no runtime claim')
    libraries = [BASE / x for x in ('jnibuild/tddb/libtddb.a', 'jnibuild/tdactor/libtdactor.a',
                                   'jnibuild/tdutils/libtdutils.a', 'jnibuild/sqlite/libtdsqlite.a')]
    checks = []
    for variant, actor in (('stock', stock), ('g1', OUT / '3.o')):
        binary = OUT / ('public-sync-' + variant)
        command = [*flags, '-Werror', '-I/stage/source/td/telegram/g1', str(SRC / 'sync-public-fixture.cpp'),
                   *[str(OUT / (str(i) + '.o')) for i in range(3)], str(actor),
                   '-Wl,--start-group', *map(str, libraries), '-Wl,--end-group',
                   '-lcrypto', '-lz', '-pthread', '-o', str(binary)]
        (OUT / ('sync-' + variant + '-command.json')).write_text(json.dumps(command, indent=2) + '\n')
        linked = subprocess.run(command, capture_output=True, timeout=90)
        (OUT / ('sync-' + variant + '-link.log')).write_bytes(linked.stdout + linked.stderr)
        if linked.returncode != 0:
            raise RuntimeError('PUBLIC storage actor fixture link failed; no runtime claim')
        symbols = subprocess.run(['nm', '-C', str(binary)], check=True, capture_output=True, text=True, timeout=20).stdout
        (OUT / ('sync-' + variant + '-symbols.txt')).write_text(symbols)
        if ('td::ConcurrentBinlog::force_sync(' not in symbols or
                any(name in symbols for name in ('td::TdDb::', 'td::ClientManager::', 'td::Td::',
                                                 'td::AuthManager::', 'td::Session::', 'td::AuthDataShared::'))):
            raise RuntimeError('Unexpected actor symbol scope; refuse execution')
        case = OUT / 'sync-cases' / variant
        case.mkdir(parents=True, mode=0o700)
        profile = case / 'PUBLIC'
        profile.mkdir(mode=0o700)
        with (profile / 'PUBLIC_LEASE').open('xb'):
            pass
        (profile / 'PUBLIC_LEASE').chmod(0o600)
        before = inspect['tree'](profile)
        outcome = subprocess.run([str(binary), str(profile)], capture_output=True, timeout=8)
        (case / 'stdout').write_bytes(outcome.stdout)
        (case / 'stderr').write_bytes(outcome.stderr)
        after = inspect['tree'](profile)
        early = 1 if variant == 'stock' else 0
        expected_exit = 21 if variant == 'stock' else 0
        expected = ('PUBLIC_STORAGE_SYNC entered=1 early=' + str(early) +
                    ' before_bytes=128 after_bytes=160 replay=1 closed=1 account_engine=0\n').encode()
        exact = outcome.returncode == expected_exit and outcome.stdout == expected and outcome.stderr == b''
        record = profile / 'native-g1/native/g1-admission'
        main_file = profile / 'native-g1/native/td.binlog'
        physical = (record.is_file() and len(record.read_bytes()) == 112 and
                    record.read_bytes()[:8] == b'TGCG1ADM' and main_file.is_file() and main_file.stat().st_size == 160)
        result = {'variant': variant, 'passed': exact and physical, 'exitCode': outcome.returncode,
                  'exactProcessOutcome': exact, 'physicalOutcome': physical,
                  'before': before, 'after': after, 'actorObjectSha256': digest(actor),
                  'binarySha256': digest(binary), 'originalConcurrentSourceSha256': digest(original),
                  'scope': 'PUBLIC actual storage actor gap/sync/close/reopen; non-account event',
                  'actualConcurrentBinlogExecuted': True, 'tdDbOrClientExecuted': False,
                  'authorizationHandoff': 'NONE; admission record intentionally remains unsealed'}
        (case / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        checks.append(result)
        print('PUBLIC_STORAGE_SYNC', variant, 'PASS' if result['passed'] else 'FAIL', flush=True)
    summary = {'scope': 'PUBLIC ConcurrentBinlog storage actors only; not account lifecycle acceptance',
               'checks': checks, 'allPassed': all(x['passed'] for x in checks),
               'stockControlObservesPrematureSync': checks[0]['passed'],
               'g1WaitsForReservedEventThenRealCloseReopen': checks[1]['passed'],
               'actualConcurrentBinlogExecuted': True, 'tdDbOrClientExecuted': False, 'sdkActivated': False}
    (OUT / 'sync-result.json').write_text(json.dumps(summary, indent=2) + '\n')
    return 0 if summary['allPassed'] else 1
