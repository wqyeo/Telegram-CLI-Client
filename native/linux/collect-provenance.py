#!/usr/bin/env python3
"""Run inside the trusted local builder, after JNI compilation, never on downloaded binaries."""
import hashlib
import json
import pathlib
import re
import subprocess


def output(*arguments):
    return subprocess.check_output(arguments, text=True, stderr=subprocess.PIPE).strip()


def cache_values(path):
    wanted = {'CMAKE_BUILD_TYPE', 'CMAKE_CXX_COMPILER', 'CMAKE_CXX_FLAGS_RELEASE',
              'CMAKE_SHARED_LINKER_FLAGS', 'OPENSSL_USE_STATIC_LIBS', 'ZLIB_USE_STATIC_LIBS',
              'TD_ENABLE_JNI', 'CMAKE_POSITION_INDEPENDENT_CODE'}
    result = {}
    for line in path.read_text().splitlines():
        match = re.match(r'([^:#=]+):[^=]+=(.*)', line)
        if match and match[1] in wanted:
            result[match[1]] = match[2]
    return result


def main():
    root = pathlib.Path('/work/bundles/linux-x86_64')
    try:
        manifest = json.loads((root / 'manifest.json').read_text())
        revision = manifest['tdlibRevision']
    except (OSError, ValueError, KeyError, TypeError):
        raise SystemExit('Native manifest is missing or malformed.') from None
    if not isinstance(revision, str) or not re.fullmatch('[a-f0-9]{40}', revision):
        raise SystemExit('Invalid pinned TDLib revision')
    source = pathlib.Path('/work') / ('tdlib-' + revision)
    library = root / 'libtdjni.so'
    dynamic = output('readelf', '--dynamic', str(library))
    versions = output('readelf', '--version-info', str(library))
    dependencies = output('ldd', str(library))
    if 'not found' in dependencies:
        raise SystemExit('JNI dependency resolution failed')
    required_glibc = set(re.findall(r'GLIBC_([0-9.]+)', versions))
    report = {
        'schemaVersion': 1,
        'compiler': output('g++', '--version').splitlines()[0],
        'cmake': output('cmake', '--version').splitlines()[0],
        'ninja': output('ninja', '--version'),
        'jdk': output('java', '--version').splitlines()[0],
        'operatingSystem': pathlib.Path('/etc/os-release').read_text(),
        'tdlibFlags': cache_values(source / 'jnibuild/CMakeCache.txt'),
        'jniFlags': cache_values(source / 'example/java/build/CMakeCache.txt'),
        'elfNeeded': re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic),
        'elfDynamicSection': dynamic,
        'resolvedDependencies': dependencies,
        'maximumJniGlibcSymbol': max(required_glibc, key=lambda item: tuple(map(int, item.split('.')))) if required_glibc else None,
        'minimumWholeApplicationOsClaimed': False,
        'installedBuilderPackages': output('dpkg-query', '-W'),
    }
    (root / 'build-info.json').write_text(json.dumps(report, indent=2) + '\n')
    files = {}
    for path in sorted(root.rglob('*')):
        if path.is_file() and path.name != 'manifest.json':
            digest = hashlib.sha256()
            with path.open('rb') as stream:
                for block in iter(lambda: stream.read(1_048_576), b''):
                    digest.update(block)
            files[path.relative_to(root).as_posix()] = {'sha256': digest.hexdigest(), 'size': path.stat().st_size}
    manifest['files'] = files
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'status': 'ok', 'elfNeeded': report['elfNeeded'],
                      'maximumJniGlibcSymbol': report['maximumJniGlibcSymbol']}))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        raise SystemExit('Native provenance collection failed; inspect builder prerequisites.') from None
