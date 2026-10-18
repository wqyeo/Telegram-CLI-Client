#!/usr/bin/env python3
"""Opt-in native PUBLIC admission-record tests, never an account database or SDK activation."""
import argparse
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('g1_admission_container_controls', Path(__file__).with_name('test-g1-sqlite.py'))
assert SPEC and SPEC.loader
controls = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(controls)
digest = controls.digest


def public_document(path):
    try:
        value = json.loads(path.read_text())
    except (OSError, ValueError):
        raise SystemExit('PUBLIC hardening manifest unavailable or invalid; no fallback.') from None
    if not isinstance(value, dict):
        raise SystemExit('PUBLIC hardening manifest must be an object.')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--public-record-fixtures', action='store_true', required=True,
                        help='Explicitly run metadata/filesystem fixtures, not TDLib data validation')
    parser.parse_args()
    if os.uname().sysname != 'Linux' or os.uname().machine != 'x86_64':
        raise SystemExit('Linux x86_64 required; no fallback.')
    code = ROOT / 'native/hardening'
    manifest = public_document(code / 'admission-base.json')
    lock = public_document(ROOT / 'native/tdlib.lock.json')
    if manifest['revision'] != lock['revision']:
        raise SystemExit('Native revision mismatch.')
    cache = ROOT / 'native-build'
    upstream = cache / ('tdlib-' + manifest['revision'])
    for rel, expected in manifest['referenceHashes'].items():
        if digest(upstream / rel) != expected:
            raise SystemExit('Original native reference changed; refuse.')
    if digest(cache / 'downloads' / ('tdlib-' + manifest['revision'] + '.tar.gz')) != lock['sourceSha256']:
        raise SystemExit('Cached archive hash mismatch.')
    image = subprocess.check_output(['podman', 'image', 'inspect', 'localhost/telegram-cli-native-builder',
                                     '--format', '{{.Id}}'], text=True, timeout=20).strip()
    if image.removeprefix('sha256:') != manifest['builderImageId']:
        raise SystemExit('Cached builder identity changed; no rebuild/pull/fallback.')
    workspace = Path(tempfile.mkdtemp(prefix='native-g1-admission-', dir=ROOT / '.pi'))
    frozen, output = workspace / 'inputs', workspace / 'output'
    frozen.mkdir(mode=0o700)
    output.mkdir(mode=0o700)
    for name in ('admission-base.json', 'store-admission.h', 'admission-public-fixture.cpp', 'run-admission.py'):
        shutil.copy2(code / name, frozen / name)
    protected = {str(upstream / p): digest(upstream / p) for p in manifest['referenceHashes']}
    for path in sorted((cache / 'bundles/linux-x86_64').rglob('*')):
        if path.is_file():
            protected[str(path)] = digest(path)
    for rel in ('tddb/libtddb.a', 'tdactor/libtdactor.a', 'tdutils/libtdutils.a', 'sqlite/libtdsqlite.a'):
        path = upstream / 'jnibuild' / rel
        protected[str(path)] = digest(path)
    # Preserve current public hardening sources without treating historical B1b
    # helper hashes as current. Never load arbitrary paths from an old receipt.
    paths = list(code.iterdir()) + list((ROOT / 'scripts/native').glob('test*g1*.py'))
    for path in paths:
        if path.is_symlink():
            raise SystemExit('Linked hardening source refused.')
        if path.is_file():
            protected[str(path)] = digest(path)
    protected[str(ROOT / 'target/telegram-cli.jar')] = digest(ROOT / 'target/telegram-cli.jar')
    host_paths = (Path(__file__).resolve(), ROOT / 'scripts/native/test-g1-sqlite.py')
    state = {'workspace': str(workspace), 'output': str(output), 'builderImageId': image,
             'revision': manifest['revision'], 'scope': manifest['scope'],
             'inputHashes': {p.name: digest(p) for p in frozen.iterdir()},
             'hostCodeHashes': {str(p): digest(p) for p in host_paths},
             'protectedHashes': protected, 'network': 'none', 'clientCreated': False,
             'accountDatabaseCreated': False, 'tdlibLinkedOrExecuted': False,
             'publicFixturesOnly': True, 'status': 'prepared', 'originalInputsUnchanged': False,
             'frozenInputsUnchanged': False, 'preservationFinalized': False}
    receipt = workspace / 'host.json'
    receipt.write_text(json.dumps(state, indent=2) + '\n')
    command = controls.container_command(cache, frozen, output, workspace / 'container.cid', image, 'record-only')
    assert command[-2] == '/g1/run-sqlite.py'
    command[-2] = '/g1/run-admission.py'
    state['status'] = 'running'
    receipt.write_text(json.dumps(state, indent=2) + '\n')
    print('Fresh native PUBLIC admission-record stage:', workspace, flush=True)
    exit_code, timed_out = 2, False
    try:
        result = subprocess.run(command, check=False, timeout=600)
        exit_code = result.returncode
        state.update(exitCode=exit_code, status='finished')
    except subprocess.TimeoutExpired:
        timed_out = True
        state.update(status='timeout-no-retry')
    except OSError:
        state.update(status='launch-failed-no-retry')
    finally:
        state['containerLifecycle'] = controls.finish_container(workspace / 'container.cid', timed_out)
        controls.finalize_preservation(state, protected, frozen)
        receipt.write_text(json.dumps(state, indent=2) + '\n')
    if not state['preservationFinalized'] or not state['containerLifecycle']['removed']:
        raise SystemExit('Lifecycle/cleanup or evidence unconfirmed; retain stage; no retry.')
    if not state['originalInputsUnchanged'] or not state['frozenInputsUnchanged']:
        raise SystemExit('Protected/frozen input preservation failed.')
    print('Retained receipt:', receipt, flush=True)
    return exit_code


if __name__ == '__main__':
    raise SystemExit(main())
