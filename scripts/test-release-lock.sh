#!/usr/bin/env bash
# Runtime fixtures only; no network, SDK, real credentials or Gradle.
set -euo pipefail
[[ $# -eq 0 ]] || { echo 'usage: scripts/test-release-lock.sh (no arguments)' >&2; exit 2; }
exec python3 -I "$(dirname "${BASH_SOURCE[0]}")/test-release-lock.py"
