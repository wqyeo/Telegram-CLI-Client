#!/usr/bin/env bash
# Explicit trusted-local NEW image/archive verification. Synthetic profiles only.
set -euo pipefail
cd "$(dirname "$0")/../.."
[[ $# == 2 ]] || { echo 'Usage: scripts/verify/packaged-worker.sh IMAGE ARCHIVE' >&2; exit 2; }
image=$(realpath "$1")
archive=$(realpath "$2")
scripts/verify/linux-distribution.sh "$image" "$archive"
python3 scripts/verify/packaged-worker.py "$image"
fixtures=$(realpath target/test-classes)
[[ -f $fixtures/com/gitlab/wqyeo/telegramcli/session/CredentialRuntimeProbe.class ]] || {
    echo 'Compile the synthetic CredentialRuntimeProbe fixture before this explicit verification.' >&2; exit 2;
}
# Execute only PUBLIC synthetic crypto and injected vault-CLI fixtures with the
# ACTUAL installed runtime/JAR/libs. NEVER select a genuine Console input factory.
"$image/lib/runtime/bin/java" -XX:-UsePerfData -cp "$fixtures:$image/lib/app/telegram-cli.jar" \
    com.gitlab.wqyeo.telegramcli.session.CredentialRuntimeProbe
base=$(python3 - <<'PY'
import json
print(json.load(open('native/tdlib.lock.json'))['linuxBuilderBase'])
PY
)
# No system Java/Python/Maven, writable installation, network or operator data exposed.
timeout 90s podman run --rm --network none --read-only --memory 768m \
    --tmpfs /tmp:rw,nosuid,nodev,size=64m --workdir / \
    --env HOME=/tmp/tgc-home --env XDG_CONFIG_HOME=/tmp/tgc-config \
    --env XDG_DATA_HOME=/tmp/tgc-data --env LANG=C.UTF-8 \
    --volume "$image:/opt/Telegram CLI 测试:ro,z" --volume "$fixtures:/opt/synthetic-fixtures:ro,z" "$base" \
    sh -eu -c '
      for tool in java mvn; do if command -v "$tool" >/dev/null 2>&1; then exit 9; fi; done
      app="/opt/Telegram CLI 测试/bin/telegram-cli"
      mkdir -m 700 /tmp/tgc-synthetic-workspace
      "$app" profile-init --workspace /tmp/tgc-synthetic-workspace --profile synthetic --json
      "$app" session-check --workspace /tmp/tgc-synthetic-workspace --profile synthetic --json
      "$app" session-check --workspace /tmp/tgc-synthetic-workspace --profile synthetic --json
      "/opt/Telegram CLI 测试/lib/runtime/bin/java" -XX:-UsePerfData \
        -cp "/opt/synthetic-fixtures:/opt/Telegram CLI 测试/lib/app/telegram-cli.jar" \
        com.gitlab.wqyeo.telegramcli.session.CredentialRuntimeProbe
      test "$(stat -c %a /tmp/tgc-synthetic-workspace/synthetic)" = 700
      test "$(stat -c %a /tmp/tgc-synthetic-workspace/synthetic/profile.json)" = 600
      test "$(stat -c %a /tmp/tgc-synthetic-workspace/synthetic/profile.lock)" = 600
      test "$(find /tmp/tgc-synthetic-workspace -type f | wc -l)" = 2
      for location in /tmp/tgc-home /tmp/tgc-config /tmp/tgc-data; do test ! -e "$location"; done
    ' > .pi/distribution-verification/packaged-worker-clean-room.jsonl
python3 - <<'PY'
import json,pathlib
lines=pathlib.Path('.pi/distribution-verification/packaged-worker-clean-room.jsonl').read_text().splitlines()
try:
    reports=[json.loads(line) for line in lines]
except json.JSONDecodeError:
    raise SystemExit('Packaged worker returned invalid JSON') from None
if len(reports)!=4 or any(report.get('status')!='ok' for report in reports):
    raise SystemExit('Packaged worker clean-room verification failed')
if any(report.get('accountDatabaseOpened') is not False for report in reports[:3]) or reports[3].get('accountDatabaseCreated') is not False:
    raise SystemExit('Unexpected account database claim')
if reports[3].get('mode')!='synthetic-envelope-runtime-check' or reports[3].get('vaultCliChecked') is not True or reports[3].get('injectedPublicInputOnly') is not True:
    raise SystemExit('Missing installed-runtime envelope/injected vault CLI check')
print('Read-only relocated/no-network/no-system-Java image: profile metadata, packaged worker close/restart, envelope crypto and PUBLIC injected vault CLI passed.')
PY
(cd "$image" && sha256sum --check --quiet SHA256SUMS)
printf '\nComplete packaged account-free worker verification passed. No live account or Windows evidence.\n'
