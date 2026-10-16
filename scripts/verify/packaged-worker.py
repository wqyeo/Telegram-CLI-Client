#!/usr/bin/env python3
"""Explicit trusted-local Linux image test. Synthetic profile only; no account/DB."""
import fcntl
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path


def main(image: Path) -> None:
    app = image.resolve(strict=True) / 'bin/telegram-cli'
    with tempfile.TemporaryDirectory(prefix='tgc-packaged-worker-') as temporary:
        root = Path(temporary)
        workspace = root / 'synthetic-workspace'
        workspace.mkdir(mode=0o700)
        env = {'LC_ALL': 'C.UTF-8', 'LANG': 'C.UTF-8', 'HOME': str(root / 'unused-home'),
               'XDG_CONFIG_HOME': str(root / 'unused-config'), 'XDG_DATA_HOME': str(root / 'unused-data')}

        def run(*args: str, success: bool = True) -> dict:
            result = subprocess.run([str(app), *args, '--json'], env=env, cwd='/', capture_output=True, timeout=40, check=False)
            assert result.returncode == (0 if success else 1), 'Unexpected command status'
            assert not result.stderr, 'Unexpected command stderr'
            try:
                report = json.loads(result.stdout)
            except (json.JSONDecodeError, UnicodeDecodeError):
                raise AssertionError('Packaged command returned invalid JSON') from None
            assert report['schemaVersion'] == 1 and report['status'] == ('ok' if success else 'error')
            return report

        run('profile-init', '--workspace', str(workspace), '--profile', 'synthetic')
        lock = workspace / 'synthetic/profile.lock'
        metadata = (workspace / 'synthetic/profile.json').read_bytes()
        check = ['session-check', '--workspace', str(workspace), '--profile', 'synthetic']
        for _ in range(2):
            report = run(*check)
            assert report['workerClosed'] and not report['tdlibClientCreated']
            assert not report['accountDatabaseOpened'] and not report['telegramConnectionMade']
        missing = root / 'DO_NOT_PRINT_missing'
        run('session-check', '--workspace', str(missing), '--profile', 'synthetic', success=False)
        assert not missing.exists(), 'Implicit workspace creation'

        # Observe POSIX record locking from a different process, never flock().
        # Closing duplicate descriptors in the SAME owner process could release locks.
        descriptor = os.open(lock, os.O_WRONLY | os.O_NOFOLLOW)

        def locked() -> bool:
            try:
                fcntl.lockf(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                return True
            fcntl.lockf(descriptor, fcntl.LOCK_UN)
            return False

        def await_lock(expected: bool) -> None:
            deadline = time.monotonic() + 10
            while locked() != expected:
                if time.monotonic() >= deadline:
                    raise AssertionError('Packaged worker lease deadline exceeded')
                time.sleep(0.02)  # Bounded readiness check on this test's owned child.

        owner = subprocess.Popen([str(app), *check, '--hold-seconds', '30', '--json'],
                                 env=env, cwd='/', stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            await_lock(True)
            run(*check, success=False)
            assert locked(), 'Failed contender released another worker lease'
            owner.kill()  # Only the exact parent spawned by this synthetic test.
            out, err = owner.communicate(timeout=5)
            assert owner.returncode != 0 and not out and not err, 'Parent-loss fabricated success'
            await_lock(False)  # Worker must release only its OWN lease after stdin EOF.
        finally:
            if owner.poll() is None:
                owner.kill()
            owner.communicate(timeout=5)
            os.close(descriptor)
        run(*check)

        # Test actual installed runtime's invalid-JNI containment without changing image.
        broken = root / 'synthetic-invalid-bundle'
        shutil.copytree(image.resolve() / 'lib/app/native', broken)
        invalid = b'SYNTHETIC_INVALID_LIBRARY_DO_NOT_PRINT'
        (broken / 'libtdjni.so').write_bytes(invalid)
        try:
            manifest = json.loads((broken / 'manifest.json').read_text())
        except (OSError, json.JSONDecodeError, UnicodeDecodeError):
            raise AssertionError('Trusted local bundle manifest is invalid') from None
        manifest['files']['libtdjni.so'].update(size=len(invalid), sha256=hashlib.sha256(invalid).hexdigest())
        (broken / 'manifest.json').write_text(json.dumps(manifest))
        run(*check, '--bundle', str(broken), success=False)
        run(*check)
        assert not list(broken.glob('hs_err*')), 'Worker crash artifact'
        assert (workspace / 'synthetic/profile.json').read_bytes() == metadata
        assert {str(path.relative_to(workspace)) for path in workspace.rglob('*')} == {
            'synthetic', 'synthetic/profile.json', 'synthetic/profile.lock'}
        assert all(not Path(env[name]).exists() for name in ('HOME', 'XDG_CONFIG_HOME', 'XDG_DATA_HOME'))
    print('Packaged account-free worker: installed runtime/JNI, clean closure/restart, exclusion, parent EOF and invalid-JNI containment passed. No account database.')


if __name__ == '__main__':
    if not __debug__:
        raise SystemExit('Run this verifier without Python optimization; assertions are required.')
    if sys.platform != 'linux' or len(sys.argv) != 2:
        raise SystemExit('Usage (Linux only): python3 scripts/verify/packaged-worker.py TRUSTED_LOCAL_IMAGE')
    main(Path(sys.argv[1]))
