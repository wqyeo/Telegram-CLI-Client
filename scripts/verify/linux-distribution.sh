#!/usr/bin/env bash
# Explicit local/manual verification only; never run by CI as a hidden prerequisite.
set -euo pipefail
cd "$(dirname "$0")/../.."
image=$(realpath "${1:-dist/telegram-cli}")
[[ -x $image/bin/telegram-cli ]] || { echo 'Build the Linux app image first.' >&2; exit 2; }
command -v podman >/dev/null
(cd "$image" && sha256sum --check --quiet SHA256SUMS)
version=$(python3 - <<'PY'
import xml.etree.ElementTree as E
print(E.parse('pom.xml').getroot().findtext('{*}version').removesuffix('-SNAPSHOT'))
PY
)
archive=$(realpath "${2:-$(dirname "$image")/telegram-cli-$version-linux-x86_64.tar.gz}")
(cd "$(dirname "$archive")" && sha256sum --check --quiet "$(basename "$archive").sha256")
mkdir -p .pi/distribution-verification
# Test the archive's actual payload, not just the jpackage output directory.
# These checks apply to our trusted local artifact, not arbitrary untrusted tar files.
extracted=$(mktemp -d "$PWD/.pi/distribution-verification/extracted.XXXXXX")
trap 'rm -rf -- "$extracted"' EXIT
tar --extract --gzip --no-same-owner --file "$archive" --directory "$extracted"
cmp "$image/SHA256SUMS" "$extracted/telegram-cli/SHA256SUMS"
image=$(realpath "$extracted/telegram-cli")
(cd "$image" && sha256sum --check --quiet SHA256SUMS)
base=$(python3 - <<'PY'
import json
print(json.load(open('native/tdlib.lock.json'))['linuxBuilderBase'])
PY
)
mkdir -p .pi/distribution-verification
# No host JDK/Maven/TDLib, credentials, network, or writable installation is exposed.
# The image is deliberately relocated to a Unicode path containing spaces.
timeout 60s podman run --rm --network none --read-only --memory 768m \
    --tmpfs /tmp:rw,nosuid,nodev,size=64m --workdir / \
    --env HOME=/tmp/tgc-home --env XDG_CONFIG_HOME=/tmp/tgc-config \
    --env XDG_DATA_HOME=/tmp/tgc-data --env LANG=C.UTF-8 \
    --volume "$image:/opt/Telegram CLI 测试:ro,z" "$base" \
    sh -eu -c '
      for tool in java mvn; do if command -v "$tool" >/dev/null 2>&1; then exit 9; fi; done
      app="/opt/Telegram CLI 测试/bin/telegram-cli"
      "$app" --version
      "$app" diagnostics --json
      "$app" demo --json
      "$app" native-check --json
      for location in /tmp/tgc-home /tmp/tgc-config /tmp/tgc-data; do
        test ! -e "$location"
      done
    ' > .pi/distribution-verification/clean-room.jsonl
python3 - <<'PY'
import json,pathlib
lines=pathlib.Path('.pi/distribution-verification/clean-room.jsonl').read_text().splitlines()
reports=[json.loads(line) for line in lines if line.startswith('{')]
if len(reports)!=3 or reports[-1].get('status')!='ok':
    raise SystemExit('Clean-room native probe failed')
print('Clean-room diagnostics/demo/native probe passed; no home/config/data paths created.')
PY
# Noninteractive use must fail clearly, not launch a graphical fallback.
set +e
"$image/bin/telegram-cli" tui --demo </dev/null \
    > .pi/distribution-verification/noninteractive.out 2> .pi/distribution-verification/noninteractive.err
status=$?
set -e
[[ $status == 2 && ! -s .pi/distribution-verification/noninteractive.out ]]
grep -q 'requires an interactive terminal' .pi/distribution-verification/noninteractive.err
python3 scripts/verify/pty-smoke.py -- "$image/bin/telegram-cli" tui --demo
python3 scripts/verify/pty-smoke.py -- podman run --rm -it --network none --read-only \
    --tmpfs /tmp:rw,nosuid,nodev,size=64m --workdir / --env TERM=xterm-256color --env LANG=C.UTF-8 \
    --env HOME=/tmp/tgc-home --env XDG_CONFIG_HOME=/tmp/tgc-config --env XDG_DATA_HOME=/tmp/tgc-data \
    --volume "$image:/opt/Telegram CLI 测试:ro,z" "$base" '/opt/Telegram CLI 测试/bin/telegram-cli' tui --demo
(cd "$image" && sha256sum --check --quiet SHA256SUMS)
printf '\nLinux package verification passed. Windows is not validated by these checks.\n'
