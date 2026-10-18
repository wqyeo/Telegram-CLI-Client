#!/usr/bin/env python3
"""Opt-in existing-binlog/replay PUBLIC native component test, no SDK switch."""
import argparse
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('g1_container_controls', Path(__file__).with_name('test-g1-sqlite.py'))
assert SPEC and SPEC.loader
controls = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(controls)
digest = controls.digest


def main(component='replay'):
    if component not in ('replay', 'maintenance', 'creation', 'policy'):
        raise SystemExit('Unknown PUBLIC component; no fallback.')
    policy = component == 'policy'
    creation = component in ('creation', 'policy')
    maintenance = component in ('maintenance', 'creation', 'policy')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--variant', choices=('stock', 'g1'), required=True)
    args = parser.parse_args()
    if creation and args.variant != 'g1':
        parser.error('Creation is a new G1-only API; no stock behavioral RED is claimed.')
    if os.uname().sysname != 'Linux' or os.uname().machine != 'x86_64':
        raise SystemExit('Linux x86_64 required; no fallback.')
    code = ROOT / 'native/hardening'
    try:
        manifest_name = ('policy-base.json' if policy else
                         ('new-binlog-base.json' if creation else ('maintenance-base.json' if maintenance else 'binlog-base.json')))
        manifest = json.loads((code / manifest_name).read_text())
        lock = json.loads((ROOT / 'native/tdlib.lock.json').read_text())
    except (OSError, ValueError):
        raise SystemExit('Native manifest/lock unavailable or invalid.') from None
    if manifest['revision'] != lock['revision']:
        raise SystemExit('Native revision mismatch.')
    cache = ROOT / 'native-build'
    upstream = cache / ('tdlib-' + manifest['revision'])
    references = {**manifest['sourceHashes'], **manifest.get('referenceHashes', {})}
    for rel, expected in references.items():
        if digest(upstream / rel) != expected:
            raise SystemExit('Original binlog source/header/reference changed; refuse.')
    if maintenance and digest(code / 'binlog-g1.patch') != manifest['basePatchSha256']:
        raise SystemExit('B1b base patch changed; refuse cumulative maintenance build.')
    if creation and digest(code / 'binlog-maintenance-g1.patch') != manifest['maintenancePatchSha256']:
        raise SystemExit('B1c patch changed; refuse cumulative NEW build.')
    if policy and digest(code / 'binlog-new-g1.patch') != manifest['newPatchSha256']:
        raise SystemExit('B1e patch changed; refuse policy/layout binding build.')
    if digest(cache / 'downloads' / ('tdlib-' + manifest['revision'] + '.tar.gz')) != lock['sourceSha256']:
        raise SystemExit('Cached archive hash mismatch.')
    image = subprocess.check_output(['podman', 'image', 'inspect', 'localhost/telegram-cli-native-builder',
                                     '--format', '{{.Id}}'], text=True, timeout=20).strip()
    if image.removeprefix('sha256:') != manifest['builderImageId']:
        raise SystemExit('Cached builder identity changed; no rebuild/pull/fallback.')
    label = 'policy' if policy else ('binlog-new' if creation else ('maintenance' if maintenance else 'binlog'))
    workspace = Path(tempfile.mkdtemp(prefix='native-g1-' + label + '-' + args.variant + '-', dir=ROOT / '.pi'))
    frozen, output = workspace / 'inputs', workspace / 'output'
    frozen.mkdir(mode=0o700)
    output.mkdir(mode=0o700)
    cidfile = workspace / 'container.cid'
    names = ['binlog-base.json', 'binlog-g1.patch', 'binlog-public-fixture.cpp', 'run-binlog.py', 'run-sqlite.py']
    if maintenance:
        names += ['maintenance-base.json', 'binlog-maintenance-g1.patch', 'maintenance-public-fixture.cpp']
    if creation:
        names += ['new-binlog-base.json', 'binlog-new-g1.patch', 'new-binlog-public-fixture.cpp',
                  'run-new-binlog.py', 'store-admission.h', 'admission-public-fixture.cpp', 'run-admission.py']
    if policy:
        names += ['policy-base.json', 'binlog-only-policy.h', 'binlog-only-policy.cpp',
                  'policy-public-fixture.cpp', 'run-policy.py']
    for name in names:
        shutil.copy2(code / name, frozen / name)
    protected = {str(upstream / p): digest(upstream / p) for p in references}
    for path in sorted((cache / 'bundles/linux-x86_64').rglob('*')):
        if path.is_file():
            protected[str(path)] = digest(path)
    for rel in ('tddb/libtddb.a', 'tdactor/libtdactor.a', 'tdutils/libtdutils.a', 'sqlite/libtdsqlite.a'):
        path = upstream / 'jnibuild' / rel
        protected[str(path)] = digest(path)
    if creation:
        for path in sorted(code.iterdir()):
            if path.is_file():
                protected[str(path)] = digest(path)
        for path in (ROOT / 'target/telegram-cli.jar', ROOT / 'docs/native-g1-contract.md',
                     ROOT / 'scripts/native/test-g1-binlog-new.py'):
            protected[str(path)] = digest(path)
    if policy:
        path = ROOT / 'scripts/native/test-g1-policy.py'
        protected[str(path)] = digest(path)
    state = {'variant': args.variant, 'workspace': str(workspace), 'output': str(output), 'builderImageId': image,
             'revision': manifest['revision'], 'scope': manifest['scope'],
             'inputHashes': {p.name: digest(p) for p in frozen.iterdir()},
             'hostCodeHashes': {str(p): digest(p) for p in (Path(__file__).resolve(), ROOT / 'scripts/native/test-g1-sqlite.py')},
             'protectedHashes': protected, 'network': 'none', 'clientCreated': False,
             'publicFixturesOnly': True, 'status': 'prepared', 'originalInputsUnchanged': False,
             'frozenInputsUnchanged': False, 'preservationFinalized': False}
    receipt = workspace / 'host.json'
    receipt.write_text(json.dumps(state, indent=2) + '\n')
    command = controls.container_command(cache, frozen, output, cidfile, image, args.variant)
    assert command[-2] == '/g1/run-sqlite.py'
    command[-2] = '/g1/run-binlog.py'
    if maintenance:
        command.append('policy' if policy else ('creation' if creation else 'maintenance'))
    state['status'] = 'running'
    receipt.write_text(json.dumps(state, indent=2) + '\n')
    print('Fresh native PUBLIC binlog stage:', workspace, flush=True)
    exit_code = 2
    timed_out = False
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
        state['containerLifecycle'] = controls.finish_container(cidfile, timed_out)
        controls.finalize_preservation(state, protected, frozen)
        receipt.write_text(json.dumps(state, indent=2) + '\n')
    if not state['preservationFinalized'] or not state['containerLifecycle']['removed']:
        raise SystemExit('Lifecycle/cleanup or evidence unconfirmed; retain stage; no retry.')
    if not state['originalInputsUnchanged'] or not state['frozenInputsUnchanged']:
        raise SystemExit('Original/frozen input preservation failed.')
    print('Retained receipt:', receipt, flush=True)
    return exit_code


if __name__ == '__main__':
    raise SystemExit(main())
