#!/usr/bin/env python3
"""Opt-in Linux experiment, not a build prerequisite or production terminal backend.

mise exec -- ./mvnw clean verify
mise exec -- python3 scripts/terminal-probe/probe.py prepare  # explicit network downloads
mise exec -- python3 scripts/terminal-probe/probe.py run      # offline, interactive

The ordinary Maven build and packaged application never include these dependencies.
Sources/POMs and the upstream BSD license stay beside the local experimental jars.
Hashes check pinned bytes, not publisher authenticity or an OS sandbox.
"""
import hashlib
import http.client
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[2]
LOCK = pathlib.Path(__file__).with_name('jline.lock.json')
ARTIFACTS = ('jline-terminal', 'jline-terminal-ffm', 'jline-native')
SOURCE = ROOT / 'src/terminal-probe/java/com/gitlab/wqyeo/telegramcli/probe/JlineTerminalProbe.java'


def read_lock(path=LOCK):
    try:
        lock = json.loads(path.read_text(encoding='utf-8'))
    except (OSError, UnicodeError, ValueError) as error:
        raise ValueError('Cannot read experimental dependency lock') from error
    version = lock['version']
    if (lock['schemaVersion'] != 1 or not re.fullmatch(r'\d+\.\d+\.\d+', version)
            or not re.fullmatch(r'[a-f0-9]{40}', lock['sourceRevision'])):
        raise ValueError('Invalid experimental dependency lock')
    expected = {f'{artifact}-{version}{suffix}' for artifact in ARTIFACTS
                for suffix in ('.jar', '-sources.jar', '.pom')} | {'JLine-LICENSE.txt'}
    names = [entry['name'] for entry in lock['files']]
    if len(names) != len(expected) or set(names) != expected:
        raise ValueError('Unexpected experimental dependency inventory')
    for entry in lock['files']:
        if (not re.fullmatch(r'[a-f0-9]{64}', entry['sha256'])
                or not isinstance(entry['size'], int) or not 0 < entry['size'] <= 4_194_304):
            raise ValueError('Invalid experimental dependency integrity entry')
    return lock


def verify_file(path, entry):
    if path.is_symlink() or not path.is_file() or path.stat().st_size != entry['size']:
        raise ValueError('Experimental dependency missing, linked or wrong size: ' + entry['name'])
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != entry['sha256']:
        raise ValueError('Experimental dependency checksum mismatch: ' + entry['name'])


def artifact_url(lock, entry):
    if entry['name'] == 'JLine-LICENSE.txt':
        return ('https://raw.githubusercontent.com/jline/jline3/'
                + lock['sourceRevision'] + '/LICENSE.txt')
    artifact = entry['name'].split('-' + lock['version'], 1)[0]
    return ('https://repo.maven.apache.org/maven2/org/jline/' + artifact + '/'
            + lock['version'] + '/' + entry['name'])


def download(lock, entry):
    url = urllib.parse.urlsplit(artifact_url(lock, entry))
    if url.scheme != 'https' or url.hostname not in ('repo.maven.apache.org', 'raw.githubusercontent.com'):
        raise ValueError('Experimental dependencies require an approved HTTPS source')
    connection = http.client.HTTPSConnection(url.hostname, timeout=30)
    try:
        connection.request('GET', url.path)
        response = connection.getresponse()
        if response.status != 200:
            raise ValueError('Experimental dependency download failed: ' + entry['name'])
        # No redirects, credentials, arbitrary schemes, unbounded bodies or archive extraction.
        return response.read(entry['size'] + 1)
    finally:
        connection.close()


def prepare(lock, directory):
    directory.mkdir(parents=True, exist_ok=True)
    for entry in lock['files']:
        destination = directory / entry['name']
        if destination.exists() or destination.is_symlink():
            # Never overwrite a corrupt or linked cache entry, including one in use.
            verify_file(destination, entry)
            continue
        with tempfile.NamedTemporaryFile(dir=directory, delete=False) as temporary:
            temporary_path = pathlib.Path(temporary.name)
            try:
                temporary.write(download(lock, entry))
                temporary.flush()
                verify_file(temporary_path, entry)
                # Fail rather than overwrite if another preparation won the race.
                os.link(temporary_path, destination)
            finally:
                temporary_path.unlink(missing_ok=True)
    print('Pinned experimental jars, sources, POMs and BSD license verified. Windows is unverified.')


def java_command(lock, directory, inject_failure=False):
    for entry in lock['files']:
        verify_file(directory / entry['name'], entry)
    classpath = [ROOT / 'target/telegram-cli.jar', ROOT / 'target/lib/lanterna-3.1.5.jar']
    for path in [*classpath, SOURCE]:
        if not path.is_file():
            raise ValueError('Build the native-free application first: mise exec -- ./mvnw clean verify')
    modulepath = [directory / f'{artifact}-{lock["version"]}.jar' for artifact in ARTIFACTS]
    if any(os.pathsep in str(path) for path in [*classpath, *modulepath]):
        raise ValueError('Experimental class/module paths must not contain the path-list separator')
    java = shutil.which('java')
    if not java:
        raise ValueError('Use mise exec with a full JDK 25')
    for variable in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
        if os.environ.get(variable):
            raise ValueError('Unset ambient JVM options for this explicit probe: ' + variable)
    version = subprocess.run([java, '--version'], capture_output=True, text=True, timeout=10, check=True)
    if not re.match(r'(?:openjdk|java) 25(?:[. +\-]|$)', version.stdout):
        raise ValueError('The experimental source-file launcher requires JDK 25')
    return [java, '-Xmx256m', '-XX:-UsePerfData', '-Dfile.encoding=UTF-8',
            '--module-path', os.pathsep.join(map(str, modulepath)),
            '--add-modules', 'org.jline.terminal.ffm',
            '--enable-native-access=org.jline.terminal.ffm', '--illegal-native-access=deny',
            '--class-path', os.pathsep.join(map(str, classpath)),
            '--source', '25', str(SOURCE), '--demo',
            *(['--inject-failure'] if inject_failure else [])]


def main():
    if sys.platform != 'linux':
        raise ValueError('This experimental runner is Linux-only; Windows remains unverified')
    if sys.argv[1:] not in (['prepare'], ['command'], ['run'], ['run', '--inject-failure']):
        raise ValueError('Usage: probe.py prepare | command | run [--inject-failure]')
    lock = read_lock()
    directory = ROOT / 'native-build/terminal-probe' / lock['version']
    if not directory.resolve().is_relative_to(ROOT):
        raise ValueError('Experimental cache must remain inside the project workspace')
    if sys.argv[1] == 'prepare':
        prepare(lock, directory)
        return
    command = java_command(lock, directory, '--inject-failure' in sys.argv)
    if sys.argv[1] == 'command':
        print(json.dumps(command))
    else:
        # Keep launch errors in this runner's error handler and relay the JVM exit status.
        # This is a literal argv list, never shell-interpreted command text.
        raise SystemExit(subprocess.run([*command], shell=False, check=False).returncode)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print('Experimental terminal probe: ' + str(error), file=sys.stderr)
        raise SystemExit(2) from None
