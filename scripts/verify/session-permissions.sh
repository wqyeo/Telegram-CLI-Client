#!/usr/bin/env bash
# Explicit offline Linux permission checks. Not part of Maven or CI.
set -euo pipefail
cd "$(dirname "$0")/../.."
runtime=$(realpath "${1:-dist/telegram-cli/lib/runtime}")
probe=com/gitlab/wqyeo/telegramcli/session/ProfilePermissionProbe.class
[[ -x $runtime/bin/java && -f target/telegram-cli.jar && -f target/test-classes/$probe ]] || {
    echo 'Build the default JAR/tests and supply an existing Linux app-image runtime.' >&2; exit 2;
}
command -v podman >/dev/null
base=$(python3 - <<'PY'
import json
print(json.load(open('native/tdlib.lock.json'))['linuxBuilderBase'])
PY
)
# No implicit image downloads, host sudo, host ownership changes or live credentials.
podman image exists "$base"
mkdir -p .pi
stage=$(mktemp -d "$PWD/.pi/session-permissions.XXXXXX")
trap 'rm -rf -- "$stage"' EXIT
# Relabel only disposable copies, never the installed app/runtime or Maven outputs.
cp -a "$runtime" "$stage/runtime"
cp target/telegram-cli.jar "$stage/application.jar"
mkdir -p "$stage/test/$(dirname "$probe")"
cp "target/test-classes/$probe" "$stage/test/$probe"
timeout 45s podman run --rm --pull=never --network none --read-only --memory 256m --pids-limit 64 \
    --tmpfs /tmp:rw,nosuid,nodev,size=32m --env TGC_PERMISSION_CONTAINER=1 \
    --volume "$stage:/opt/probe:ro,z" "$base" \
    /opt/probe/runtime/bin/java -Xmx64m -XX:-UsePerfData -cp /opt/probe/application.jar:/opt/probe/test \
    com.gitlab.wqyeo.telegramcli.session.ProfilePermissionProbe
