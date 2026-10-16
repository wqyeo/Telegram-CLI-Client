#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?Run with mise exec -- scripts/package/linux.sh (full JDK 25).}"
[[ $(uname -m) == x86_64 && $(uname -s) == Linux ]] || { echo 'Linux x86_64 is required.' >&2; exit 2; }
for tool in jdeps jlink jpackage python3; do command -v "$tool" >/dev/null; done
bundle=native-build/bundles/linux-x86_64
[[ -f $bundle/manifest.json ]] || { echo 'Build the native bundle first: scripts/native/build-linux.sh' >&2; exit 2; }
./mvnw -Pnative clean verify
java -jar target/telegram-cli.jar native-check --bundle "$bundle" --json
readarray -t versions < <(python3 - <<'PY'
import json,xml.etree.ElementTree as E
root=E.parse('pom.xml').getroot()
version=root.findtext('{*}version').removesuffix('-SNAPSHOT')
lanterna=root.findtext('.//{*}lanterna.version')
if lanterna != json.load(open('licenses/lanterna-source.json'))['version']:
    raise SystemExit('Lanterna source version mismatch')
crypto=root.findtext('.//{*}bouncycastle.version')
if crypto != json.load(open('licenses/bouncycastle-source.json'))['version']:
    raise SystemExit('Bouncy Castle source version mismatch')
print(version);print(lanterna);print(crypto)
PY
)
version=${versions[0]}; lanterna=${versions[1]}; crypto=${versions[2]}
input=target/package-input
mkdir -p "$input/lib" "$input/licenses" "$input/build-provenance"
cp target/telegram-cli.jar "$input/"
cp "target/lib/lanterna-$lanterna.jar" "target/lib/bcprov-jdk18on-$crypto.jar" "$input/lib/"
cp -R "$bundle" "$input/native"
cp LICENSE THIRD-PARTY-NOTICES.txt licenses/*.txt "$input/licenses/"
cp native/tdlib.lock.json licenses/lanterna-source.json licenses/bouncycastle-source.json pom.xml "$input/build-provenance/"
cp native-build/linux-builder-packages.txt "$input/build-provenance/"
./mvnw org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy \
    "-Dartifact=com.googlecode.lanterna:lanterna:$lanterna:jar:sources" "-DoutputDirectory=$PWD/$input/licenses"
./mvnw org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy \
    "-Dartifact=com.googlecode.lanterna:lanterna:$lanterna:pom" "-DoutputDirectory=$PWD/$input/licenses"
for classifier in jar:sources pom; do
    ./mvnw org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy \
        "-Dartifact=org.bouncycastle:bcprov-jdk18on:$crypto:$classifier" "-DoutputDirectory=$PWD/$input/licenses"
done
python3 - <<'PY'
import hashlib,json,pathlib,urllib.request
for entry in json.load(open('licenses/bouncycastle-source.json'))['files']:
    base = 'target/package-input/lib' if entry['name'].endswith('.jar') and not entry['name'].endswith('-sources.jar') else 'target/package-input/licenses'
    file = pathlib.Path(base, entry['name'])
    if file.stat().st_size != entry['bytes'] or hashlib.sha256(file.read_bytes()).hexdigest() != entry['sha256']:
        raise SystemExit('Bouncy Castle artifact integrity mismatch')
lock=json.load(open('licenses/lanterna-source.json'))
archive=pathlib.Path('native-build/downloads/lanterna-'+lock['version']+'-source.tar.gz')
if not archive.exists():
    with urllib.request.urlopen(lock['url'],timeout=60) as response: archive.write_bytes(response.read())
if hashlib.sha256(archive.read_bytes()).hexdigest() != lock['sha256']:
    raise SystemExit('Lanterna source checksum mismatch')
pathlib.Path('target/package-input/licenses/lanterna-source.tar.gz').write_bytes(archive.read_bytes())
PY
modules=$(jdeps --multi-release 25 --ignore-missing-deps --print-module-deps \
    target/telegram-cli.jar "target/lib/lanterna-$lanterna.jar" "target/lib/bcprov-jdk18on-$crypto.jar" "$bundle/tdlib-java.jar")
printf '%s\n' "$modules" > "$input/build-provenance/jdeps-modules.txt"
# Lanterna loads sun.misc.Signal reflectively for SIGWINCH; jdeps cannot infer it.
jlink --add-modules "$modules,jdk.crypto.ec,jdk.unsupported" --output target/runtime \
    --strip-debug --no-man-pages --no-header-files --compress=zip-6
# Keep runtime/bin/java: the native probe needs a bounded child JVM.
[[ -x target/runtime/bin/java ]]
python3 - <<'PY'
import json,subprocess,pathlib
record={'schemaVersion':1,'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
        'workingTreeDirty':bool(subprocess.check_output(['git','status','--porcelain'],text=True).strip()),
        'javaVersion':subprocess.check_output(['java','--version'],text=True).splitlines()[0],
        'windowsValidated':False,'reproducibleNativeBuildClaimed':False}
pathlib.Path('target/package-input/build-provenance/application.json').write_text(json.dumps(record,indent=2)+'\n')
PY
# --license-file is installer-only; app-image notices are staged under licenses/.
# Remove only the previous generated app image, never user profiles or data.
rm -rf -- dist/telegram-cli
jpackage --type app-image --name telegram-cli --app-version "$version" \
    --vendor 'Yeo Wen Qin' --description 'Offline unofficial Telegram terminal prototype' \
    --input "$input" --main-jar telegram-cli.jar --main-class com.gitlab.wqyeo.telegramcli.Main \
    --runtime-image target/runtime --dest dist \
    --java-options '-Dtgc.native.bundle=$APPDIR/native' --java-options '-Dfile.encoding=UTF-8' \
    --java-options '-XX:-UsePerfData' --java-options '--add-modules=jdk.unsupported'
python3 - <<'PY'
import hashlib,pathlib
root=pathlib.Path('dist/telegram-cli')
lines=[]
for path in sorted(root.rglob('*')):
    if path.is_file():
        lines.append(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+path.relative_to(root).as_posix())
(root/'SHA256SUMS').write_text('\n'.join(lines)+'\n')
PY
archive="telegram-cli-$version-linux-x86_64.tar.gz"
tar --sort=name --owner=0 --group=0 --numeric-owner -C dist -czf "dist/$archive" telegram-cli
(cd dist && sha256sum "$archive" > "$archive.sha256")
printf '\nCreated dist/%s (manual Linux validation still required).\n' "$archive"
