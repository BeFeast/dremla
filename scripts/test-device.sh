#!/usr/bin/env bash
# No arguments; configuration is read by the isolated Python implementation.
set -euo pipefail
[[ $# -eq 0 ]] || { echo 'usage: scripts/test-device.sh (no arguments)' >&2; exit 2; }
exec python3 -I "$(dirname "${BASH_SOURCE[0]}")/test-device.py"
