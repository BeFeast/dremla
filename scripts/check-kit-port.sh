#!/usr/bin/env bash
# Brand-kit port check (issue #2). No arguments; scripts/test-kit-port.sh is its self-test.
#
# Reads docs/kit-port.sha256 and fails on any listed file that is missing or whose SHA-256 differs
# from the one in the manifest, and on a listed path that is not canonical or names a file twice.
# Exactly one file carries a substitution, the one pinned below (SUBST_*); the manifest must have
# exactly one "substituted" line, with that path, line and SHA-256 of the listed file without that
# line. The pinned file is checked three ways instead of against its listed SHA-256: its committed
# bytes must hash to the committed SHA-256 the manifest records; with the pinned line deleted it
# must hash to the pinned SHA-256, so it differs from the listed file on that line only; and that
# line must be exactly the documented text. A "# was <sha256> (#N)" comment directly above a listed
# line records a later change to that file (docs/kit-port.md).
set -euo pipefail

if [[ $# -ne 0 ]]; then
  echo "usage: scripts/check-kit-port.sh (no arguments)" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

readonly MANIFEST="docs/kit-port.sha256"
# The issue lists 38 files; a dropped manifest line must not shrink the check silently.
readonly EXPECTED_LISTED=38
# The one accepted substitution (docs/kit-port.md): line 180 of LibraryTest.kt, where a private
# LAN address becomes the documentation host collection.example (RFC 2606). SUBST_WITHOUT_SHA is
# the SHA-256 of the listed file with that line deleted, computed from the baseline when the file
# was ported. SUBST_LINE_SHA is the SHA-256 of the committed line 180 with its LF.
readonly SUBST_PATH="app/src/test/java/com/befeast/sleepmusic/LibraryTest.kt"
readonly SUBST_LINE=180
readonly SUBST_LISTED_SHA="22baee644fdea92d6347e9a0b16b539f83ff63e6b408ec87fd48d12733af4ded"
readonly SUBST_WITHOUT_SHA="e68af20f5ee74bc387fba51d1cfe6de39b1c915e008ca80f55871822de41a08b"
readonly SUBST_LINE_SHA="c2501e1e96317a882aa96cece7600b7ff5e69bf252b9bfe35018f45f8de6148e"
readonly SUBST_HOST="collection.example"

die() {
  echo "check-kit-port: $*" >&2
  exit 2
}

[[ -f "$MANIFEST" ]] || die "missing $MANIFEST"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum not found"

failures=0
fail() {
  echo "FAIL  $*"
  failures=$((failures + 1))
}

# Every listed line counts toward EXPECTED_LISTED, so an alias that replaces another file's line
# fails once, as an alias, instead of also shrinking the list.
listed_entries=0
listed_paths=()
declare -A listed_sha=()
declare -A folded_paths=()
declare -A change_record=()
subst_entries=0
subst_committed=""

re_listed='^([0-9a-f]{64})  ([^[:space:]]+)$'
re_subst='^substituted  ([0-9a-f]{64})  ([^[:space:]]+)  ([1-9][0-9]*)  ([0-9a-f]{64})$'
re_record='^# was ([0-9a-f]{64}) \(#([1-9][0-9]*)\)$'
# Relative, no empty, "." or ".." segment, no leading "./" or "/": the form git records.
re_canonical='^[A-Za-z0-9_][A-Za-z0-9_.-]*(/[A-Za-z0-9_][A-Za-z0-9_.-]*)*$'
lineno=0
record=""
record_lineno=0
while IFS= read -r entry || [[ -n "$entry" ]]; do
  lineno=$((lineno + 1))
  if [[ -n "$record" && ! "$entry" =~ $re_listed ]]; then
    fail "$MANIFEST:$record_lineno: change record is not directly above a listed line"
    record=""
  fi
  if [[ "$entry" == "# was "* ]]; then
    if [[ "$entry" =~ $re_record ]]; then
      record="#${BASH_REMATCH[2]}; listed in issue #2 as ${BASH_REMATCH[1]}"
      record_lineno=$lineno
    else
      fail "$MANIFEST:$lineno: malformed change record (expected '# was <sha256> (#<issue>)'): $entry"
    fi
    continue
  fi
  [[ -z "$entry" || "$entry" == \#* ]] && continue
  if [[ "$entry" =~ $re_listed ]]; then
    sha="${BASH_REMATCH[1]}"
    path="${BASH_REMATCH[2]}"
    listed_entries=$((listed_entries + 1))
    folded="${path,,}"
    if [[ ! "$path" =~ $re_canonical ]]; then
      fail "$MANIFEST:$lineno: $path is not a canonical path"
    elif [[ -n "${folded_paths[$folded]+x}" ]]; then
      if [[ "${folded_paths[$folded]}" == "$path" ]]; then
        fail "$MANIFEST:$lineno: $path is listed twice"
      else
        fail "$MANIFEST:$lineno: $path and ${folded_paths[$folded]} differ only in case"
      fi
    else
      folded_paths["$folded"]="$path"
      listed_sha["$path"]="$sha"
      listed_paths+=("$path")
      [[ -z "$record" ]] || change_record["$path"]="$record"
    fi
    record=""
  elif [[ "$entry" =~ $re_subst ]]; then
    subst_entries=$((subst_entries + 1))
    path="${BASH_REMATCH[2]}"
    line="${BASH_REMATCH[3]}"
    without="${BASH_REMATCH[4]}"
    if [[ "$path" != "$SUBST_PATH" ]]; then
      fail "$MANIFEST:$lineno: substituted entry for $path; only $SUBST_PATH line $SUBST_LINE is substituted"
    elif [[ -n "$subst_committed" ]]; then
      fail "$MANIFEST:$lineno: second substituted entry for $path"
    else
      subst_committed="${BASH_REMATCH[1]}"
      if [[ "$line" != "$SUBST_LINE" ]]; then
        fail "$MANIFEST:$lineno: substituted entry names line $line, pinned $SUBST_LINE"
      fi
      if [[ "$without" != "$SUBST_WITHOUT_SHA" ]]; then
        fail "$MANIFEST:$lineno: substituted entry records $without without line $SUBST_LINE, pinned $SUBST_WITHOUT_SHA"
      fi
    fi
  else
    die "$MANIFEST:$lineno: malformed line: $entry"
  fi
done <"$MANIFEST"
if [[ -n "$record" ]]; then
  fail "$MANIFEST:$record_lineno: change record is not directly above a listed line"
fi

if [[ $listed_entries -ne $EXPECTED_LISTED ]]; then
  fail "$MANIFEST lists $listed_entries files, expected $EXPECTED_LISTED"
fi
if [[ -z "${listed_sha[$SUBST_PATH]+x}" ]]; then
  fail "$MANIFEST: the substituted file $SUBST_PATH is not listed"
elif [[ "${listed_sha[$SUBST_PATH]}" != "$SUBST_LISTED_SHA" ]]; then
  fail "$MANIFEST: $SUBST_PATH listed as ${listed_sha[$SUBST_PATH]}, pinned $SUBST_LISTED_SHA from the issue"
fi
if [[ -z "$subst_committed" ]]; then
  fail "$MANIFEST: no substituted entry for $SUBST_PATH"
fi

sha_of() {
  sha256sum | cut -d' ' -f1
}

for path in "${listed_paths[@]}"; do
  if [[ ! -f "$path" ]]; then
    fail "$path: missing"
    continue
  fi
  actual="$(sha_of <"$path")"
  listed="${listed_sha[$path]}"
  if [[ "$path" != "$SUBST_PATH" ]]; then
    if [[ "$actual" != "$listed" ]]; then
      fail "$path: sha256 $actual, listed $listed"
    elif [[ -n "${change_record[$path]+x}" ]]; then
      echo "ok    $path (changed by ${change_record[$path]})"
    else
      echo "ok    $path"
    fi
    continue
  fi

  before=$failures
  if [[ -n "$subst_committed" && "$actual" != "$subst_committed" ]]; then
    fail "$path: sha256 $actual, recorded committed $subst_committed"
  fi
  without="$(LC_ALL=C sed "${SUBST_LINE}d" "$path" | sha_of)"
  if [[ "$without" != "$SUBST_WITHOUT_SHA" ]]; then
    fail "$path: with line $SUBST_LINE deleted sha256 $without, pinned $SUBST_WITHOUT_SHA for the listed file"
  fi
  if [[ "$(LC_ALL=C sed -n "${SUBST_LINE}p" "$path" | sha_of)" != "$SUBST_LINE_SHA" ]]; then
    if ! LC_ALL=C sed -n "${SUBST_LINE}p" "$path" | grep -q -F -e "$SUBST_HOST"; then
      fail "$path: line $SUBST_LINE does not contain $SUBST_HOST"
    else
      fail "$path: line $SUBST_LINE is not the documented text (sha256 pinned $SUBST_LINE_SHA)"
    fi
  fi
  if [[ $failures -eq $before && -n "$subst_committed" ]]; then
    echo "ok    $path (committed $actual; the listed $listed with line $SUBST_LINE substituted)"
  fi
done

if [[ $failures -ne 0 ]]; then
  echo "check-kit-port: FAIL ($failures problem(s) in $listed_entries listed files)" >&2
  exit 1
fi
echo "check-kit-port: PASS ($listed_entries listed files, $subst_entries substitution)"
