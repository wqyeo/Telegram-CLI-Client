#!/usr/bin/env python3
"""PUBLIC actual key-value snapshots/storage actor; never account execution."""
import hashlib
import json
import runpy
import subprocess
from pathlib import Path

SRC, OUT = Path('/g1'), Path('/stage')
BASE = Path('/work/tdlib-a94b83d67ab4c8397c6245e541b31a4131ea2f79')
WAKE_CHECKS = (
    'wake-observed-pair-acquired-without-ticket', 'wake-invalid-pair-lifecycle-is-error-not-busy',
    'wake-first-map-held-lock-release-signals', 'wake-second-map-excludes-partial-first-self-wake',
    'wake-release-before-subscribe-is-not-lost', 'wake-ticket-subscribe-is-one-use',
    'wake-native-nonmutating-visitor-unlock-no-revision', 'wake-read-lock-adapter-unlock-signals',
    'wake-noop-write-lock-adapter-unlock-signals', 'wake-one-shot-does-not-consume-next-epoch',
    'wake-registration-cancel-idempotent', 'wake-ticket-and-subscription-retain-owner-until-cancel',
    'wake-lifecycle-signal-does-not-grant-a-view', 'wake-invalid-epoch-owner-are-errors',
    'wake-epoch-exhaustion-never-wraps', 'wake-thread-release-registration-race-not-lost',
    'wake-thread-release-cancel-keeps-channel-safe', 'wake-native-visitor-thread-signals-without-scheduler',
    'wake-real-poll-actor-delivery-after-guard-release', 'wake-real-poll-actor-waits-for-cross-thread-unlock',
    'wake-real-poll-actor-cancels-before-late-release',
)
CHECKS = (
    'uninitialized-is-error-not-busy', 'initialized-native-map-snapshot',
    'unchanged-set-retains-revision', 'aba-changes-token-not-just-predicate',
    'borrowed-empty-versus-absent', 'borrowed-getters-do-not-relock', 'same-map-contention-is-busy',
    'busy-second-releases-first', 'dual-borrowed-native-views',
    'deferred-owner-continuation-after-guard-release', 'erase-noop-retains-revision',
    'erase-changes-revision', 'batch-mutation-and-duplicate-keys',
    'prefix-mutation-changes-revision', 'empty-batch-prefix-retain-revision',
    'reset-incarnation-defeats-zero-revision-aba', 'view-retains-owner-and-unlocks-before-release',
    'value-move-out-back-cannot-restore-token', 'finish-rebinding-invalidates-incarnation',
    'invalid-lock-is-error-not-busy', 'revision-exhaustion-never-wraps', *WAKE_CHECKS,
    'handoff-deferred-stable-required-map', 'handoff-aba-is-changed-not-ready',
    'handoff-contention-is-not-ready', 'handoff-waits-for-observed-reservation-gap',
    'handoff-after-actual-healthy-compaction', 'close-cancels-unsynced-handoff',
    'native-sync-close-invalidates-snapshot', 'actual-native-reopen-replay',
    'import-replaces-both-incarnations',
)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main(flags):
    inspect = runpy.run_path(str(SRC / 'run-context.py'))
    binary = OUT / 'public-kv-snapshots'
    libraries = [BASE / x for x in ('jnibuild/tddb/libtddb.a', 'jnibuild/tdactor/libtdactor.a',
                                   'jnibuild/tdutils/libtdutils.a', 'jnibuild/sqlite/libtdsqlite.a')]
    command = [*flags, '-Werror', '-I/stage/source/td/telegram/g1', str(SRC / 'snapshot-public-fixture.cpp'),
               *[str(OUT / (str(i) + '.o')) for i in range(4)], '-Wl,--start-group',
               *map(str, libraries), '-Wl,--end-group', '-lcrypto', '-lz', '-pthread', '-o', str(binary)]
    (OUT / 'snapshot-command.json').write_text(json.dumps(command, indent=2) + '\n')
    compiled = subprocess.run(command, capture_output=True, timeout=100)
    (OUT / 'snapshot-link.log').write_bytes(compiled.stdout + compiled.stderr)
    if compiled.returncode != 0:
        raise RuntimeError('PUBLIC snapshot fixture compile/link failed; no behavior claim')
    symbols = subprocess.run(['nm', '-C', str(binary)], check=True, capture_output=True, text=True, timeout=20).stdout
    (OUT / 'snapshot-symbols.txt').write_text(symbols)
    if (any(name not in symbols for name in ('td::ConcurrentBinlog::force_sync(', 'td::ConcurrentBinlog::force_sync_handoff(',
                                             'td::detail::BinlogActor::finish_sync_handoff(', '::try_snapshot_pair(',
                                             'DeferredSnapshotActor::inspect()', 'PublicGapWindow::timeout_expired()',
                                             '::try_observed_snapshot_pair(', '::BusyTicket::subscribe() &&',
                                             'PublicWakeReceiver::start_up()', 'PublicWakeReceiver::loop()',
                                             'PublicWakeReceiver::cancel_wait()')) or
            any(name in symbols for name in ('td::TdDb::', 'td::ClientManager::', 'td::Td::',
                                             'td::AuthManager::', 'td::Session::', 'td::AuthDataShared::', 'td::Global::'))):
        raise RuntimeError('Unexpected PUBLIC key-value/actor symbol scope; refuse execution')
    case = OUT / 'snapshot-case'
    case.mkdir(mode=0o700)
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
    expected = ''.join('PUBLIC_SNAPSHOT_CHECK ' + name + ' PASS\n' for name in CHECKS)
    expected += 'PUBLIC_KV_SNAPSHOTS_ACCEPTED account_engine=0\n'
    exact = outcome.returncode == 0 and outcome.stdout == expected.encode() and outcome.stderr == b''
    record, main_file = profile / 'native-g1/native/g1-admission', profile / 'native-g1/native/td.binlog'
    physical = (record.is_file() and len(record.read_bytes()) == 112 and record.read_bytes()[:8] == b'TGCG1ADM' and
                main_file.is_file() and main_file.stat().st_size > 128)
    validated_wake_families = [name for name in WAKE_CHECKS
                               if ('PUBLIC_SNAPSHOT_CHECK ' + name + ' PASS\n').encode() in outcome.stdout]
    wake_runtime_validated = len(validated_wake_families) == len(WAKE_CHECKS) and outcome.stderr == b''
    result = {'scope': 'PUBLIC actual key-value snapshots/storage actors; not stable account sealing/publication',
              'passed': exact and physical, 'allPassed': exact and physical, 'exitCode': outcome.returncode,
              'exactProcessOutcome': exact, 'physicalOutcome': physical, 'namedFamilies': list(CHECKS),
              'before': before, 'after': after, 'binarySha256': digest(binary),
              'objectsSha256': {str(i) + '.o': digest(OUT / (str(i) + '.o')) for i in range(4)},
              'actualKeyValueAndConcurrentBinlogExecuted': True, 'tdDbClientOrGlobalExecuted': False,
              'newObservedPairTemplateInstantiationProved': True, 'wakeSubscriptionRuntimeExercised': wake_runtime_validated,
              'publicPollReceiverExercised': wake_runtime_validated, 'productionActorBridgeOrPendingSchedulingProved': False,
              'namedWakeFamilies': list(WAKE_CHECKS), 'validatedWakeFamilies': validated_wake_families,
              'coalescedAdmissionAttemptsOrInitializerBarrierProved': False,
              'authorizationHandoff': 'NONE; record remains unsealed', 'sdkActivated': False}
    (OUT / 'snapshot-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print('PUBLIC_KV_SNAPSHOTS', 'PASS' if result['passed'] else 'FAIL', flush=True)
    return 0 if result['passed'] else 1
