#!/usr/bin/env bash
# No arguments; configuration is read by the isolated Python implementation.
set -euo pipefail
[[ $# -eq 0 ]] || { echo 'usage: scripts/probe-target.sh (no arguments)' >&2; exit 2; }
exec python3 -I "$(dirname "${BASH_SOURCE[0]}")/device-tools.py" probe
