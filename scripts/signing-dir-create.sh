#!/usr/bin/env bash
# Creates the private signing directory for one CI job: mktemp -d "$RUNNER_TEMP/sign.XXXXXX",
# mode 0700 with the setuid, setgid and sticky bits cleared (a setgid parent would otherwise pass
# its setgid bit on). scripts/resolve-signing.sh writes the signing material into it and
# scripts/signing-dir-remove.sh removes it. Used by the signing smoke workflow and the release
# workflow; scripts/test-signing.sh is its self-test. See docs/release-signing.md.
#
# No arguments. Environment:
#   RUNNER_TEMP         absolute path of an existing directory (the job's runner temp directory)
#   GITHUB_ENV          when set, DREMLA_SIGNING_DIR=<path> is appended to that file, so later
#                       steps of the job get the variable; when not set, that line is printed on
#                       stdout instead
#   DREMLA_SIGNING_DIR  must not be set yet: a second directory would hide the first one from
#                       the cleanup step
#
# Exit 0 when the directory exists with mode 0700, 1 on any failure (nothing is left behind),
# 2 on arguments.
set -euo pipefail
set +x
umask 077

if [[ $# -ne 0 ]]; then
  echo "usage: scripts/signing-dir-create.sh (no arguments; see the header for the environment)" >&2
  exit 2
fi

die() {
  echo "signing-dir-create: FAIL: $*" >&2
  exit 1
}

[[ -z "${DREMLA_SIGNING_DIR:-}" ]] ||
  die "DREMLA_SIGNING_DIR is already set; remove it first with scripts/signing-dir-remove.sh"

parent="${RUNNER_TEMP:-}"
[[ -n "$parent" ]] || die "RUNNER_TEMP is not set"
[[ "$parent" == /* ]] || die "RUNNER_TEMP must be an absolute path"
[[ "$parent" != *[[:cntrl:]]* ]] || die "RUNNER_TEMP contains a control character"
while [[ "$parent" == */ && "$parent" != / ]]; do
  parent="${parent%/}"
done
[[ -d "$parent" ]] || die "RUNNER_TEMP does not exist or is not a directory"

dir="$(mktemp -d "${parent%/}/sign.XXXXXX")" || die "mktemp -d failed under RUNNER_TEMP"

created=0
on_exit() {
  local rc=$?
  if [[ $rc -ne 0 && $created -eq 0 ]]; then
    rmdir -- "$dir" 2>/dev/null || true
  fi
  exit "$rc"
}
trap on_exit EXIT

# Explicit special-bit removal: on a directory, chmod 700 and u=rwx,go= keep an inherited setgid
# bit on some systems.
chmod u=rwx,go=,u-s,g-s,o-t -- "$dir" || die "chmod failed on the signing directory"
# The same predicate as scripts/resolve-signing.sh: owner rwx, nothing for group and other.
[[ -n "$(find "$dir" -maxdepth 0 -perm -0700 ! -perm /0077)" ]] ||
  die "the signing directory does not have mode 0700"

if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf 'DREMLA_SIGNING_DIR=%s\n' "$dir" >>"$GITHUB_ENV" || die "cannot append to GITHUB_ENV"
  created=1
  echo "signing directory: created (mode 0700, DREMLA_SIGNING_DIR set for the next steps)"
else
  printf 'DREMLA_SIGNING_DIR=%s\n' "$dir"
  created=1
fi
