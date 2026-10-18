#!/usr/bin/env python3
"""Opt-in cached rootless Linux builder: one fresh PUBLIC SQLite component test stage."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def container_command(cache, frozen, output, cidfile, image, variant):
    # Only output is writable: frozen inputs, cidfile and host.json are siblings,
    # not descendants of that bind mount. No writable alias to /g1 exists.
    return ['podman', 'run', '--cidfile', str(cidfile), '--timeout=570',
            '--pull=never', '--network', 'none', '--read-only',
            '--cap-drop', 'all', '--security-opt', 'no-new-privileges', '--cpus', '2',
            '--memory', '2g', '--pids-limit', '256', '--ulimit', 'core=0:0',
            '--tmpfs', '/tmp:rw,size=128m,mode=1777',
            '--volume', str(cache) + ':/work:ro',
            '--volume', str(frozen) + ':/g1:ro,z',
            '--volume', str(output) + ':/stage:rw,z',
            image, 'python3', '/g1/run-sqlite.py', variant]


def finish_container(cidfile, timed_out):
    """Confirm this run's container stopped BEFORE hashing/finalizing evidence.

    No --rm race: retain the container until wait + inspect confirm termination.
    Only the private host-written CID is used; no name-based killing/fallback.
    Any uncertainty remains a failed, unfinalized receipt; no automatic retry.
    """
    evidence = {'terminationConfirmed': False, 'removed': False, 'timedOut': timed_out}
    try:
        if cidfile.stat().st_size > 80:
            raise ValueError('Invalid CID length')
        cid = cidfile.read_text().strip()
        if re.fullmatch('[0-9a-f]{64}', cid) is None:
            raise ValueError('Invalid CID')
        evidence['containerId'] = cid
        if timed_out:
            killed = subprocess.run(['podman', 'kill', '--signal', 'KILL', cid],
                                    check=False, capture_output=True, text=True, timeout=20)
            # Already exited is possible; wait and inspect, not kill's return code,
            # determine termination. Never repeat a kill or native execution.
            evidence['killExitCode'] = killed.returncode
        waited = subprocess.run(['podman', 'wait', cid], check=False, capture_output=True,
                                text=True, timeout=20)
        evidence['waitExitCode'] = waited.returncode
        if waited.returncode != 0:
            return evidence
        inspected = subprocess.run(['podman', 'container', 'inspect', '--format',
                                    '{{json .State}}', cid], check=False, capture_output=True,
                                   text=True, timeout=20)
        evidence['inspectExitCode'] = inspected.returncode
        if inspected.returncode != 0:
            return evidence
        state = json.loads(inspected.stdout)
        if not isinstance(state, dict):
            raise ValueError('Invalid container state')
        evidence['state'] = {key: state.get(key) for key in ('Running', 'Status', 'ExitCode')}
        if state.get('Running') is not False or state.get('Status') not in ('exited', 'stopped'):
            return evidence
        evidence['terminationConfirmed'] = True
        removed = subprocess.run(['podman', 'rm', cid], check=False, capture_output=True,
                                 text=True, timeout=20)
        evidence['removeExitCode'] = removed.returncode
        evidence['removed'] = removed.returncode == 0
    except (OSError, ValueError, subprocess.SubprocessError):
        evidence['controlError'] = 'CONTAINER_LIFECYCLE_UNCONFIRMED_OR_CLEANUP_FAILED'
    return evidence


def finalize_preservation(state, protected, frozen):
    # Do not claim a stable post-run snapshot if the container may still be alive.
    state['preservationFinalized'] = False
    if not state['containerLifecycle']['terminationConfirmed']:
        state['status'] = 'container-termination-unconfirmed-no-retry'
        return
    try:
        state['originalInputsUnchanged'] = all(digest(p) == h for p, h in protected.items())
        state['frozenInputsUnchanged'] = all(digest(frozen / p) == h for p, h in state['inputHashes'].items())
        state['preservationFinalized'] = True
    except OSError:
        state['status'] = 'preservation-check-failed-no-retry'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--variant', choices=('stock', 'g1'), required=True)
    args = parser.parse_args()
    if os.uname().sysname != 'Linux' or os.uname().machine != 'x86_64':
        raise SystemExit('Linux x86_64 required; no fallback.')
    code = ROOT / 'native/hardening'
    try:
        manifest = json.loads((code / 'base.json').read_text())
        lock = json.loads((ROOT / 'native/tdlib.lock.json').read_text())
    except (OSError, ValueError):
        raise SystemExit('Native hardening manifest/lock unavailable or invalid.') from None
    if manifest['revision'] != lock['revision']:
        raise SystemExit('Native revision mismatch.')
    cache = ROOT / 'native-build'
    upstream = cache / ('tdlib-' + manifest['revision'])
    for rel, expected in manifest['sourceHashes'].items():
        if digest(upstream / rel) != expected:
            raise SystemExit('Original source changed; do not patch/reuse.')
    if digest(cache / 'downloads' / ('tdlib-' + manifest['revision'] + '.tar.gz')) != lock['sourceSha256']:
        raise SystemExit('Cached archive hash mismatch.')
    image = subprocess.check_output(['podman', 'image', 'inspect',
                                     'localhost/telegram-cli-native-builder', '--format', '{{.Id}}'],
                                    text=True, timeout=20).strip()
    if image.removeprefix('sha256:') != manifest['builderImageId']:
        raise SystemExit('Cached builder identity changed; no rebuild/pull/fallback.')
    workspace = Path(tempfile.mkdtemp(prefix='native-g1-sqlite-' + args.variant + '-', dir=ROOT / '.pi'))
    frozen = workspace / 'inputs'
    frozen.mkdir(mode=0o700)
    output = workspace / 'output'
    output.mkdir(mode=0o700)
    cidfile = workspace / 'container.cid'
    for name in ('base.json', 'sqlite-g1.patch', 'sqlite-public-fixture.cpp', 'run-sqlite.py'):
        shutil.copy2(code / name, frozen / name)
    # Protect original source/bundle/build inputs. The cache mount is READ ONLY.
    protected = {str(upstream / p): digest(upstream / p) for p in manifest['sourceHashes']}
    for path in sorted((cache / 'bundles/linux-x86_64').rglob('*')):
        if path.is_file():
            protected[str(path)] = digest(path)
    for rel in ('tddb/libtddb.a', 'tdactor/libtdactor.a', 'tdutils/libtdutils.a', 'sqlite/libtdsqlite.a'):
        path = upstream / 'jnibuild' / rel
        protected[str(path)] = digest(path)
    state = {'variant': args.variant, 'workspace': str(workspace), 'output': str(output), 'builderImageId': image,
             'revision': manifest['revision'], 'scope': 'B1a SQLite only, NOT engine-wide G1 or activation',
             'inputHashes': {p.name: digest(p) for p in frozen.iterdir()},
             'protectedHashes': protected, 'network': 'none', 'clientCreated': False,
             'publicFixturesOnly': True, 'status': 'prepared', 'originalInputsUnchanged': False,
             'frozenInputsUnchanged': False, 'preservationFinalized': False}
    receipt = workspace / 'host.json'
    receipt.write_text(json.dumps(state, indent=2) + '\n')
    print('Fresh native PUBLIC stage:', workspace, flush=True)
    command = container_command(cache, frozen, output, cidfile, image, args.variant)
    state['status'] = 'running'
    receipt.write_text(json.dumps(state, indent=2) + '\n')
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
        state['containerLifecycle'] = finish_container(cidfile, timed_out)
        finalize_preservation(state, protected, frozen)
        receipt.write_text(json.dumps(state, indent=2) + '\n')
    lifecycle = state['containerLifecycle']
    if not state['preservationFinalized'] or not lifecycle['removed']:
        raise SystemExit('Container lifecycle/cleanup or evidence unconfirmed; retain stage; no retry.')
    if not state['originalInputsUnchanged'] or not state['frozenInputsUnchanged']:
        raise SystemExit('Source/bundle/frozen-input preservation failure.')
    print('Retained receipt:', receipt, flush=True)
    return exit_code


if __name__ == '__main__':
    raise SystemExit(main())
