#!/usr/bin/env bash
# Platform-neutral catalog/readiness contract vectors (docs/collection-client-contract.md). No arguments.
set -euo pipefail
[[ $# -eq 0 ]] || { echo 'usage: scripts/check-catalog-contract.sh (no arguments)' >&2; exit 2; }
exec python3 -I "$(dirname "${BASH_SOURCE[0]}")/../contracts/check-catalog.py"
