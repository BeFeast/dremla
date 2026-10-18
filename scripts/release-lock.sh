#!/usr/bin/env bash
# No arguments. See docs/release.md for environment and schema.
set -euo pipefail
set +x
[[ $# -eq 0 ]] || { echo 'usage: scripts/release-lock.sh (no arguments)' >&2; exit 2; }
exec python3 -I "$(dirname "${BASH_SOURCE[0]}")/release-tools.py" lock
