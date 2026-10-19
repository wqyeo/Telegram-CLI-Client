#!/usr/bin/env python3
"""Explicit private compilation/PUBLIC context or storage-actor fixtures; never a client."""
import hashlib
import json
import os
import resource
import shutil
import subprocess
import sys
from pathlib import Path

SRC = Path('/g1')
OUT = Path('/stage')
BASE = Path('/work/tdlib-a94b83d67ab4c8397c6245e541b31a4131ea2f79')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    if len(sys.argv) != 2 or sys.argv[1] not in ('compile-only', 'context-public', 'sync-public', 'snapshot-public'):
        raise RuntimeError('Explicit compile-only or PUBLIC low-level context/storage-actor scope required')
    context_public = sys.argv[1] == 'context-public'
    sync_public = sys.argv[1] == 'sync-public'
    snapshot_public = sys.argv[1] == 'snapshot-public'
    try:
        manifest = json.loads((SRC / 'compile-manifest.json').read_text())
    except (OSError, ValueError):
        raise RuntimeError('Frozen compile manifest unavailable or invalid') from None
    for rel, expected in manifest['inputHashes'].items():
        if digest(SRC / rel) != expected:
            raise RuntimeError('Frozen compile input changed')
    java_home = SRC / 'toolchain'
    if not (java_home / 'include/jni.h').is_file() or not (java_home / 'include/linux/jni_md.h').is_file():
        raise RuntimeError('Frozen cached Java 25 headers unavailable; no toolchain fallback')
    version = subprocess.run([str(java_home / 'bin/javac'), '-version'], check=True,
                             capture_output=True, text=True, timeout=20,
                             env={'PATH': '/usr/bin:/bin', 'HOME': '/nonexistent', 'LC_ALL': 'C'})
    (OUT / 'java-compiler.txt').write_text(version.stdout + version.stderr)
    if not any(line.startswith('javac 25.') for line in (version.stdout + version.stderr).splitlines()):
        raise RuntimeError('Java 25 required; no alternate toolchain')
    build_source = OUT / 'source'
    shutil.copytree(SRC / 'source', build_source)  # Fresh output copy, never input/cache mutation.
    for patch in ('binlog-g1.patch', 'binlog-maintenance-g1.patch', 'binlog-new-g1.patch'):
        applied = subprocess.run(['patch', '--batch', '--fuzz=0', '-p1', '-d', str(build_source),
                                  '-i', str(SRC / patch)], capture_output=True, text=True, timeout=20)
        (OUT / (patch + '.log')).write_text(applied.stdout + applied.stderr)
        if applied.returncode != 0:
            raise RuntimeError('Private baseline patch assembly failed')
    flags = ['g++', '-std=c++17', '-O0', '-g0', '-fPIC', '-fno-exceptions', '-fno-rtti',
             '-D_FILE_OFFSET_BITS=64', '-D_GNU_SOURCE', '-Wall', '-Wextra', '-Wno-unused-parameter',
             '-I/stage/source', '-I/stage/source/tddb', '-I/stage/source/tdutils',
             '-I/g1/toolchain/include', '-I/g1/toolchain/include/linux']
    # Includes matched against cached jnibuild/compile_commands.json. JNI
    # headers come from the frozen Java25 copy, not an image-local /opt/jdk.
    for rel in ('', 'td/generate/auto', 'tdnet', 'tde2e', 'tddb', 'tdactor', 'tdutils', 'jnibuild/tdutils', 'sqlite'):
        flags.append('-I' + str(BASE / rel))
    result = {'scope': 'unaccepted integration source compilation only', 'tdDbOrClientExecuted': False,
              'linkedSdk': False, 'sdkActivated': False, 'checks': [], 'allCompiled': False}
    sources = ['td/telegram/g1/binlog-only-policy.cpp', 'td/telegram/g1/g1-storage-context.cpp',
               'tddb/td/db/binlog/Binlog.cpp', 'td/telegram/TdDb.cpp', 'td/telegram/Client.cpp',
               'td/telegram/Td.cpp', 'td/telegram/AuthManager.cpp', 'example/java/td_jni.cpp',
               'td/telegram/net/NetQueryDispatcher.cpp', 'tddb/td/db/binlog/ConcurrentBinlog.cpp',
               'td/telegram/OptionManager.cpp']
    if context_public:
        sources = sources[:3]  # No TdDb, Client, Td, AuthManager or JNI in the fixture executable.
        result['scope'] = 'PUBLIC context primitives; native authorization/sync handoffs simulated only'
    if sync_public or snapshot_public:
        sources = sources[:3] + ['tddb/td/db/binlog/ConcurrentBinlog.cpp']
        result['scope'] = 'PUBLIC ConcurrentBinlog storage actors only; no TdDb/Client/account execution'
        if snapshot_public:
            result['scope'] = 'PUBLIC actual key-value snapshot/storage actor only; no TdDb/Client/account execution'
    for index, rel in enumerate(sources):
        command = [*flags]
        if '/g1/' in rel:
            command.append('-Werror')
        if rel == 'example/java/td_jni.cpp':
            command.append('-DPACKAGE_NAME="org/drinkless/tdlib"')
        output = OUT / (str(index) + '.o')
        command += ['-c', str(build_source / rel), '-o', str(output)]
        (OUT / (str(index) + '-command.json')).write_text(json.dumps(command, indent=2) + '\n')
        try:
            checked = subprocess.run(command, capture_output=True, text=True, timeout=100,
                                     env={'PATH': '/usr/bin:/bin', 'HOME': '/nonexistent', 'LC_ALL': 'C'})
            (OUT / (str(index) + '-compile.log')).write_text(checked.stdout + checked.stderr)
            entry = {'source': rel, 'exitCode': checked.returncode, 'compiled': checked.returncode == 0,
                     'objectSha256': digest(output) if checked.returncode == 0 else None}
        except subprocess.TimeoutExpired as error:
            (OUT / (str(index) + '-compile.log')).write_bytes((error.stdout or b'') + (error.stderr or b''))
            entry = {'source': rel, 'compiled': False, 'timeout': True}
        result['checks'].append(entry)
        (OUT / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print('COMPILED' if entry['compiled'] else 'COMPILE_FAILED', rel, flush=True)
    if context_public or sync_public or snapshot_public:
        result['allCompiled'] = len(result['checks']) == (4 if sync_public or snapshot_public else 3) and all(c['compiled'] for c in result['checks'])
        (OUT / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        if not result['allCompiled']:
            return 1
        import runpy
        if snapshot_public:
            return runpy.run_path(str(SRC / 'run-snapshots.py'))['main'](flags)
        if sync_public:
            return runpy.run_path(str(SRC / 'run-sync.py'))['main'](flags)
        suite = runpy.run_path(str(SRC / 'run-context.py'))
        contexts = suite['main'](flags)
        values = runpy.run_path(str(SRC / 'run-values.py'))['main'](flags)
        return 0 if contexts == 0 and values == 0 else 1
    # Compile just the private wrapper against the unchanged, hash-protected
    # official generated classes. javac does not initialize/load Client/tdjni.
    java_out = OUT / 'classes'
    java_out.mkdir(mode=0o700)
    command = [str(java_home / 'bin/javac'), '-J-Xmx512m', '-proc:none', '-implicit:none', '-encoding', 'UTF-8',
               '-classpath', str(SRC / 'generated-tdlib-java.jar'), '-d', str(java_out),
               str(build_source / 'example/java/org/drinkless/tdlib/Client.java')]
    (OUT / 'java-command.json').write_text(json.dumps(command, indent=2) + '\n')
    checked = subprocess.run(command, capture_output=True, text=True, timeout=90,
                             env={'PATH': '/usr/bin:/bin', 'HOME': '/nonexistent', 'LC_ALL': 'C'})
    (OUT / 'java-compile.log').write_text(checked.stdout + checked.stderr)
    result['checks'].append({'source': 'Client.java', 'exitCode': checked.returncode,
                             'compiled': checked.returncode == 0})
    version = subprocess.run(['g++', '--version'], check=True, capture_output=True, text=True, timeout=10)
    (OUT / 'compiler.txt').write_text(version.stdout)
    result['allCompiled'] = len(result['checks']) == 12 and all(c['compiled'] for c in result['checks'])
    (OUT / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print('JAVA_COMPILED' if checked.returncode == 0 else 'JAVA_COMPILE_FAILED', flush=True)
    return 0 if result['allCompiled'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
