#!/usr/bin/env python3
"""Explicit private compilation/PUBLIC context codecs or storage actors; no TdDb/Client."""
import argparse
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('g1_compile_container_controls', Path(__file__).with_name('test-g1-sqlite.py'))
assert SPEC and SPEC.loader
controls = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(controls)
digest = controls.digest
SOURCES = ('CMakeLists.txt', 'td/telegram/Client.h', 'td/telegram/Client.cpp', 'td/telegram/Td.h',
           'td/telegram/Td.cpp', 'td/telegram/TdDb.h', 'td/telegram/TdDb.cpp', 'td/telegram/AuthManager.cpp',
           'td/telegram/UserId.h', 'td/telegram/net/NetQueryDispatcher.h',
           'td/telegram/net/NetQueryDispatcher.cpp',
           'example/java/org/drinkless/tdlib/Client.java', 'example/java/td_jni.cpp',
           'tddb/td/db/binlog/Binlog.cpp', 'tddb/td/db/binlog/Binlog.h',
           'tddb/td/db/binlog/ConcurrentBinlog.cpp', 'tddb/td/db/binlog/ConcurrentBinlog.h',
           'tddb/td/db/BinlogKeyValue.h',
           'tdutils/td/utils/port/RwMutex.h',
           'tddb/td/db/binlog/detail/BinlogEventsProcessor.cpp', 'tddb/td/db/binlog/detail/BinlogEventsProcessor.h')
HELPERS = ('store-admission.h', 'binlog-only-policy.h', 'binlog-only-policy.cpp',
           'g1-storage-context.h', 'g1-storage-context.cpp', 'native-dc-value.h', 'native-value-codec.h',
           'kv-wake-state.h')
WAKE_REFERENCES = ('tdactor/td/actor/impl/Scheduler-decl.h', 'tdactor/td/actor/impl/Scheduler.h',
                   'tdactor/td/actor/impl/Actor-decl.h', 'tdactor/td/actor/ConcurrentScheduler.h',
                   'tdutils/td/utils/port/EventFd.h', 'tdutils/td/utils/port/EventFdBase.h',
                   'tdutils/td/utils/port/detail/EventFdLinux.h',
                   'tdutils/td/utils/port/detail/EventFdLinux.cpp', 'tdutils/td/utils/port/detail/PollableFd.h')


def load(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        raise SystemExit('Compile manifest/preflight unavailable or invalid.') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-stage', type=Path, required=True)
    scope = parser.add_mutually_exclusive_group(required=True)
    scope.add_argument('--compile-only', action='store_true')
    scope.add_argument('--public-context-fixtures', action='store_true')
    scope.add_argument('--public-storage-actor-fixtures', action='store_true')
    scope.add_argument('--public-kv-snapshot-fixtures', action='store_true')
    args = parser.parse_args()
    mode = ('snapshot-public' if args.public_kv_snapshot_fixtures else
            'sync-public' if args.public_storage_actor_fixtures else
            'context-public' if args.public_context_fixtures else 'compile-only')
    source = args.source_stage.resolve(strict=True)
    if source.parent != ROOT / '.pi' or not source.name.startswith('g1-coordinator-draft-'):
        parser.error('Only an explicit fresh private coordinator source stage is supported.')
    if os.uname().sysname != 'Linux' or os.uname().machine != 'x86_64':
        raise SystemExit('Linux x86_64 required; no fallback.')
    preflight = load(source / 'preflight.json')
    manifest = load(ROOT / 'native/hardening/policy-base.json')
    lock = load(ROOT / 'native/tdlib.lock.json')
    if manifest['revision'] != lock['revision'] or set(preflight['originalArchiveVerified']) != set(SOURCES):
        raise SystemExit('Revision/source inventory mismatch.')
    cache = ROOT / 'native-build'
    base = cache / ('tdlib-' + manifest['revision'])
    if digest(cache / 'downloads' / ('tdlib-' + manifest['revision'] + '.tar.gz')) != lock['sourceSha256']:
        raise SystemExit('Cached archive mismatch.')
    for rel, expected in preflight['originalArchiveVerified'].items():
        if digest(base / rel) != expected:
            raise SystemExit('Original native source changed; refuse.')
    wake_provenance_path = ROOT / '.pi/g1-wake-ticket-provenance.json'
    wake_provenance = load(wake_provenance_path)
    wake_references = wake_provenance['archiveMatchedReadOnlyReferences']
    if wake_provenance['revision'] != lock['revision'] or set(wake_references) != set(WAKE_REFERENCES):
        raise SystemExit('Wake reference inventory/revision mismatch.')
    for rel, expected in wake_references.items():
        if digest(base / rel) != expected:
            raise SystemExit('Pinned notification reference changed; refuse.')
    image = subprocess.check_output(['podman', 'image', 'inspect', 'localhost/telegram-cli-native-builder',
                                     '--format', '{{.Id}}'], text=True, timeout=20).strip()
    if image.removeprefix('sha256:') != manifest['builderImageId']:
        raise SystemExit('Cached builder changed; no pull/rebuild/fallback.')
    # Resolve only the already-installed mise selection. `mise where` does not
    # provision/install. Private copy avoids relabeling or mutating the cache.
    java_home = Path(subprocess.check_output(['mise', 'where', 'java'], text=True, timeout=20).strip()).resolve(strict=True)
    release = (java_home / 'release').read_text()
    if 'JAVA_VERSION="25.' not in release or not (java_home / 'include/jni.h').is_file():
        raise SystemExit('Existing cached Java 25 toolchain required; no install/fallback.')
    toolchain_hashes = {str(p.relative_to(java_home)): digest(p) for p in java_home.rglob('*') if p.is_file()}
    prefix = ('native-g1-snapshot-public-' if args.public_kv_snapshot_fixtures else
              'native-g1-sync-public-' if args.public_storage_actor_fixtures else
              'native-g1-context-public-' if args.public_context_fixtures else 'native-g1-coordinator-compile-')
    stage = Path(tempfile.mkdtemp(prefix=prefix, dir=ROOT / '.pi'))
    frozen, output = stage / 'inputs', stage / 'output'
    frozen.mkdir(mode=0o700)
    output.mkdir(mode=0o700)
    shutil.copytree(java_home, frozen / 'toolchain', symlinks=False)
    if any(digest(frozen / 'toolchain' / rel) != expected for rel, expected in toolchain_hashes.items()):
        raise SystemExit('Cached toolchain copy mismatch; retain stage; no launch.')
    # Use an identical frozen copy rather than relabeling/chmodding the SDK
    # bundle to make the original mount readable to the builder's compiler.
    generated_java = cache / 'bundles/linux-x86_64/tdlib-java.jar'
    generated_java_hash = digest(generated_java)
    shutil.copyfile(generated_java, frozen / 'generated-tdlib-java.jar')
    if digest(frozen / 'generated-tdlib-java.jar') != generated_java_hash:
        raise SystemExit('Generated-classes copy mismatch; retain stage; no launch.')
    code = ROOT / 'native/hardening'
    for rel in SOURCES:
        target = frozen / 'source' / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / rel, target)
    for name in HELPERS:
        target = frozen / 'source/td/telegram/g1' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(code / name, target)
    target = frozen / 'source/tddb/td/db/G1StoreAdmission.h'
    shutil.copy2(code / 'store-admission.h', target)
    shutil.copy2(code / 'kv-wake-state.h', frozen / 'source/tddb/td/db/G1KvWakeState.h')
    shutil.copy2(code / 'compile-coordinator.py', frozen / 'run-sqlite.py')
    if args.public_context_fixtures:
        for name in ('run-context.py', 'context-public-fixture.cpp', 'run-values.py', 'native-value-public-fixture.cpp'):
            shutil.copy2(code / name, frozen / name)
    if args.public_storage_actor_fixtures:
        for name in ('run-context.py', 'run-sync.py', 'sync-public-fixture.cpp'):
            shutil.copy2(code / name, frozen / name)
    if args.public_kv_snapshot_fixtures:
        for name in ('run-context.py', 'run-snapshots.py', 'snapshot-public-fixture.cpp', 'wake-ticket-public-fixture.h'):
            shutil.copy2(code / name, frozen / name)
    for patch in ('binlog-g1.patch', 'binlog-maintenance-g1.patch', 'binlog-new-g1.patch'):
        expected = preflight['protectedAtStart']['native/hardening/' + patch]
        if digest(code / patch) != expected:
            raise SystemExit('Accepted baseline patch changed; refuse.')
        shutil.copy2(code / patch, frozen / patch)
    shutil.copy2(wake_provenance_path, frozen / 'wake-reference-provenance.json')
    inputs = {str(p.relative_to(frozen)): digest(p) for p in frozen.rglob('*') if p.is_file()}
    (frozen / 'compile-manifest.json').write_text(json.dumps({'inputHashes': inputs}, indent=2) + '\n')
    inputs['compile-manifest.json'] = digest(frozen / 'compile-manifest.json')
    protected = {str(base / rel): expected for rel, expected in preflight['originalArchiveVerified'].items()}
    protected.update({str(base / rel): expected for rel, expected in wake_references.items()})
    protected[str(wake_provenance_path)] = digest(wake_provenance_path)
    protected.update({str(java_home / rel): expected for rel, expected in toolchain_hashes.items()})
    protected[str(generated_java)] = generated_java_hash
    protected[str(base / 'jnibuild/compile_commands.json')] = digest(base / 'jnibuild/compile_commands.json')
    for path in [*code.iterdir(), ROOT / 'target/telegram-cli.jar', ROOT / 'target/original-telegram-cli.jar',
                 Path(__file__), ROOT / 'scripts/native/test-g1-sqlite.py', *source.rglob('*'),
                 *base.glob('jnibuild/**/*.a'), *base.glob('jnibuild/**/*.so'),
                 *(cache / 'bundles/linux-x86_64').rglob('*')]:
        if path.is_file():
            protected[str(path)] = digest(path)
    state = {'scope': mode + '; no TdDb/Client/account execution or SDK activation',
             'workspace': str(stage), 'sourceStage': str(source), 'builderImageId': image,
             'cachedJavaSource': str(java_home), 'toolchainFileCount': len(toolchain_hashes),
             'inputHashes': inputs, 'protectedHashes': protected, 'tdDbOrClientExecuted': False,
             'sdkActivated': False, 'status': 'prepared', 'preservationFinalized': False,
             'originalInputsUnchanged': False, 'frozenInputsUnchanged': False}
    receipt = stage / 'host.json'
    receipt.write_text(json.dumps(state, indent=2) + '\n')
    cidfile = stage / 'container.cid'
    command = controls.container_command(cache, frozen, output, cidfile, image, mode)
    print('Fresh ' + mode + ' stage:', stage, flush=True)
    code_status, timed_out = 2, False
    try:
        completed = subprocess.run(command, check=False, timeout=600)
        code_status = completed.returncode
        state.update(status='finished', exitCode=code_status)
    except subprocess.TimeoutExpired:
        timed_out = True
        state.update(status='timeout-no-retry')
    except OSError:
        state.update(status='launch-failed-no-retry')
    finally:
        state['containerLifecycle'] = controls.finish_container(cidfile, timed_out)
        controls.finalize_preservation(state, protected, frozen)
        receipt.write_text(json.dumps(state, indent=2) + '\n')
    if not state['preservationFinalized'] or not state['containerLifecycle']['removed']:
        raise SystemExit('Container stop/preservation unconfirmed; retain stage; no retry.')
    if not state['originalInputsUnchanged'] or not state['frozenInputsUnchanged']:
        raise SystemExit('Protected/frozen source preservation failed.')
    print('Retained compile receipt:', receipt, flush=True)
    return code_status


if __name__ == '__main__':
    raise SystemExit(main())
