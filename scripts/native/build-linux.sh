#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
command -v podman >/dev/null || { echo 'Rootless Podman is required.' >&2; exit 2; }
command -v python3 >/dev/null || { echo 'Python 3 is required.' >&2; exit 2; }
: "${JAVA_HOME:?Run with mise exec -- scripts/native/build-linux.sh (JDK 25 required).}"
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'Only Linux x86_64 is supported by this recipe.' >&2; exit 2; }
readarray -t lock < <(python3 - <<'PY'
import json,xml.etree.ElementTree as E
lock=json.load(open('native/tdlib.lock.json'))
pom=E.parse('pom.xml')
if pom.findtext('.//{*}tdlib.revision') != lock['revision']:
    raise SystemExit('TDLib revision mismatch')
print(lock['revision']);print(lock['sourceSha256']);print(lock['sourceUrl'])
PY
)
revision=${lock[0]}; sha256=${lock[1]}; url=${lock[2]}
mkdir -p native-build/downloads
archive="native-build/downloads/tdlib-$revision.tar.gz"
if [[ ! -f $archive ]]; then
    curl --fail --location --proto '=https' --tlsv1.2 --output "$archive.part" "$url"
    mv "$archive.part" "$archive"
fi
printf '%s  %s\n' "$sha256" "$archive" | sha256sum --check --status || { echo 'TDLib source checksum mismatch.' >&2; exit 1; }
podman build --tag localhost/telegram-cli-native-builder --file native/linux/Containerfile native/linux
image=$(podman image inspect localhost/telegram-cli-native-builder --format '{{.Id}}')
cp scripts/native/build-in-container.sh native-build/build-in-container.sh
cp native/linux/collect-provenance.py native-build/collect-provenance.py
# Shared SELinux labels are confined to the build cache and the read-only JDK.
# No home, account data, credentials, repository metadata, or host socket is mounted.
podman run --rm --network none --cpus 4 --memory 10g \
    --volume "$PWD/native-build:/work:z" --volume "$JAVA_HOME:/opt/jdk:ro,z" \
    --env "REVISION=$revision" --env "SOURCE_SHA256=$sha256" --env "BUILDER_IMAGE=$image" \
    --env "JOBS=${JOBS:-2}" --env JAVA_TOOL_OPTIONS=-Xmx2g \
    localhost/telegram-cli-native-builder bash /work/build-in-container.sh
./mvnw org.apache.maven.plugins:maven-install-plugin:3.2.0:install-file \
    -Dfile=native-build/bundles/linux-x86_64/tdlib-java.jar \
    -DgroupId=com.gitlab.wqyeo.thirdparty -DartifactId=tdlib-java \
    "-Dversion=$revision" -Dpackaging=jar -DgeneratePom=true
printf '\nNext: ./mvnw -Pnative clean verify\n'
