#!/usr/bin/env bash
# Offline runtime fixtures; ephemeral loopback HTTP and generated audio only.
set -euo pipefail
[[ $# -eq 0 ]] || { echo 'usage: scripts/test-collection-service.sh (no arguments)' >&2; exit 2; }
exec python3 -I "$(dirname "${BASH_SOURCE[0]}")/../collection-service/tests/run.py"
