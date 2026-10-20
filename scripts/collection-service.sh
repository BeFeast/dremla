#!/usr/bin/env bash
# Host-only, offline collection authoring. Run on a media-processing host.
set -euo pipefail
[[ $# -eq 0 ]] || { echo 'usage: scripts/collection-service.sh (no arguments)' >&2; exit 2; }
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
exec python3 -I collection-service/run.py
