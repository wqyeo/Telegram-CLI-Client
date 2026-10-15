#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
[[ $# == 1 ]] || { echo 'Usage: verify-bundle.sh BUNDLE_DIRECTORY' >&2; exit 2; }
exec java -jar "$root/target/telegram-cli.jar" native-check --bundle "$1" --json
