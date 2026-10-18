#!/usr/bin/env bash
# Removes the signing directory made by scripts/signing-dir-create.sh, whatever state the job
# left it in. The cleanup step of the signing smoke workflow and the release workflow runs it
# with if: always(); scripts/test-signing.sh is its self-test. See docs/release-signing.md.
#
# No arguments. Environment:
#   DREMLA_SIGNING_DIR  the directory; unset or empty means it was never created
#   RUNNER_TEMP         the same value signing-dir-create.sh ran with
#
# Refuses, removing nothing, a DREMLA_SIGNING_DIR that is not exactly
# $RUNNER_TEMP/sign.<letters and digits> (the mktemp template), that is a symbolic link or not a
# directory, or that is not owned by the current user. Otherwise it overwrites every regular file
# with shred when shred is on PATH (a shred failure is reported and removal goes on), removes the
# directory with rm -rf and checks that the path is gone. Paths are not printed.
#
# Exit 0: removed, never created or already gone. 1: refused, or the directory is still present.
# 2: arguments.
set -euo pipefail
set +x

if [[ $# -ne 0 ]]; then
  echo "usage: scripts/signing-dir-remove.sh (no arguments; see the header for the environment)" >&2
  exit 2
fi

die() {
  echo "signing-dir-remove: FAIL: $*" >&2
  exit 1
}

dir="${DREMLA_SIGNING_DIR:-}"
if [[ -z "$dir" ]]; then
  echo "signing directory: never created"
  exit 0
fi

parent="${RUNNER_TEMP:-}"
[[ -n "$parent" && "$parent" == /* ]] ||
  die "RUNNER_TEMP is not an absolute path; refusing to remove DREMLA_SIGNING_DIR"
while [[ "$parent" == */ && "$parent" != / ]]; do
  parent="${parent%/}"
done
prefix="${parent%/}/sign."
[[ "$dir" == "$prefix"* && "${dir#"$prefix"}" =~ ^[A-Za-z0-9]+$ ]] ||
  die "refusing to remove DREMLA_SIGNING_DIR: it is not \$RUNNER_TEMP/sign.<mktemp suffix>"
[[ ! -L "$dir" ]] || die "refusing to remove DREMLA_SIGNING_DIR: it is a symbolic link"
if [[ ! -e "$dir" ]]; then
  echo "signing directory: already gone"
  exit 0
fi
[[ -d "$dir" ]] || die "refusing to remove DREMLA_SIGNING_DIR: it is not a directory"
[[ -O "$dir" ]] || die "refusing to remove DREMLA_SIGNING_DIR: it is not owned by the current user"

# rm -rf needs write and search permission on every directory. With \; chmod runs on each
# directory before find reads it. shred --force makes each file writable itself. find does not
# follow symbolic links, so nothing outside the directory is touched. Error messages would name
# paths and are dropped; the check after rm decides.
find "$dir" -type d -exec chmod u+rwx -- {} \; 2>/dev/null || true
if command -v shred >/dev/null 2>&1; then
  find "$dir" -type f -exec shred --force --remove --iterations=1 -- {} + 2>/dev/null ||
    echo "signing-dir-remove: shred failed on some files; removing them without overwrite" >&2
else
  echo "signing directory: shred is not on PATH; removing the files without overwrite"
fi

rc=0
rm -rf -- "$dir" 2>/dev/null || rc=$?
if [[ -e "$dir" || -L "$dir" ]]; then
  die "the signing directory is still present (rm exit $rc)"
fi
echo "signing directory: removed"
