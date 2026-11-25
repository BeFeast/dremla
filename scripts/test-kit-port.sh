#!/usr/bin/env bash
# Self-test for scripts/check-kit-port.sh. Copies the checker, docs/kit-port.sha256 and every file
# it lists into a scratch tree, asserts that the intact copy passes, then breaks one thing per case
# (a flipped byte, a missing file, manifest edits: a dropped line, path aliases, substituted
# entries and change records; edits to the substituted file) and asserts that the checker fails
# with the expected problem, exactly one unless the case says otherwise. A documented later change
# to a ported file must pass. No arguments.
set -euo pipefail

if [[ $# -ne 0 ]]; then
  echo "usage: scripts/test-kit-port.sh (no arguments)" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checker="$repo_root/scripts/check-kit-port.sh"
manifest="docs/kit-port.sha256"
[[ -f "$checker" ]] || { echo "test-kit-port: missing $checker" >&2; exit 1; }
[[ -f "$repo_root/$manifest" ]] || { echo "test-kit-port: missing $repo_root/$manifest" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
tree="$work/tree"

readonly BINARY_FILE="app/src/main/res/font/archivo_regular.ttf"
readonly TEXT_FILE="app/src/main/res/values/colors.xml"
# Listed after TEXT_FILE, so an alias of TEXT_FILE on its line is the second of the two.
readonly LATER_FILE="app/src/main/res/values/themes.xml"
readonly SUBST_FILE="app/src/test/java/com/befeast/sleepmusic/LibraryTest.kt"
readonly ONE_PROBLEM="(1 problem(s) in"
# A SHA-256 that no listed file or substitution value has: the SHA-256 of the single byte "x".
readonly OTHER_SHA="2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881"

failures=0
cases=0

# fresh_tree: the checker, the manifest and every listed file, copied from the repository.
fresh_tree() {
  rm -rf "$tree"
  mkdir -p "$tree/scripts" "$tree/docs"
  cp "$checker" "$tree/scripts/check-kit-port.sh"
  cp "$repo_root/$manifest" "$tree/$manifest"
  local entry path
  while IFS= read -r entry; do
    [[ "$entry" =~ ^[0-9a-f]{64}\ \ ([^[:space:]]+)$ ]] || continue
    path="${BASH_REMATCH[1]}"
    mkdir -p "$(dirname "$tree/$path")"
    cp "$repo_root/$path" "$tree/$path"
  done <"$repo_root/$manifest"
}

# flip_byte <file> <offset>: XORs one byte with 0x01 in place.
flip_byte() {
  local file="$1" offset="$2" byte
  byte="$(od -An -tu1 -j "$offset" -N1 "$file" | tr -d ' ')"
  [[ -n "$byte" ]] || { echo "test-kit-port: $file is shorter than $((offset + 1)) bytes" >&2; exit 1; }
  # shellcheck disable=SC2059 # the format is an octal escape built on purpose
  printf "\\$(printf '%03o' $((byte ^ 1)))" | dd of="$file" bs=1 seek="$offset" conv=notrunc status=none
}

# sha_in_tree <path>: the SHA-256 of the scratch copy of <path>.
sha_in_tree() {
  sha256sum "$tree/$1" | cut -d' ' -f1
}

# edit_listed <path> [line...]: replaces the manifest's listed line for <path> with the given
# lines, in place.
edit_listed() {
  local target="$1" entry out=()
  shift
  while IFS= read -r entry; do
    if [[ "$entry" =~ ^[0-9a-f]{64}\ \ (.*)$ && "${BASH_REMATCH[1]}" == "$target" ]]; then
      out+=("$@")
    else
      out+=("$entry")
    fi
  done <"$tree/$manifest"
  printf '%s\n' "${out[@]}" >"$tree/$manifest"
}

# rerecord_committed: writes the scratch copy's current SHA-256 of the substituted file into the
# manifest's "substituted" line, as someone hiding an edit behind a new committed hash would.
rerecord_committed() {
  local committed
  committed="$(sha256sum "$tree/$SUBST_FILE" | cut -d' ' -f1)"
  sed -i "s/^substituted  [0-9a-f]\{64\}  /substituted  $committed  /" "$tree/$manifest"
}

# check <name> <expect: pass|fail> [text the output must contain...]
check() {
  local name="$1" expect="$2" out rc=0 needle
  shift 2
  out="$(bash "$tree/scripts/check-kit-port.sh" 2>&1)" || rc=$?
  cases=$((cases + 1))
  local verdict="ok"
  if [[ "$expect" == "fail" ]]; then
    [[ $rc -eq 1 ]] || verdict="FAIL"
  else
    [[ $rc -eq 0 ]] || verdict="FAIL"
  fi
  for needle in "$@"; do
    [[ "$out" == *"$needle"* ]] || verdict="FAIL"
  done
  if [[ "$verdict" == "ok" ]]; then
    echo "ok    $name (expected $expect)"
  else
    echo "FAIL  $name (expected $expect, exit $rc)"
    printf '%s\n' "$out" | sed 's/^/        /'
    failures=$((failures + 1))
  fi
}

fresh_tree
check "intact copy" pass

fresh_tree
flip_byte "$tree/$BINARY_FILE" 1000
check "one byte flipped in a font" fail "FAIL  $BINARY_FILE: sha256" "$ONE_PROBLEM"

fresh_tree
flip_byte "$tree/$TEXT_FILE" 50
check "one byte flipped in an XML resource" fail "FAIL  $TEXT_FILE: sha256" "$ONE_PROBLEM"

fresh_tree
rm "$tree/$TEXT_FILE"
check "missing file" fail "FAIL  $TEXT_FILE: missing" "$ONE_PROBLEM"

fresh_tree
sed -i "\\#  $TEXT_FILE\$#d" "$tree/$manifest"
check "manifest line dropped" fail "lists 37 files, expected 38" "$ONE_PROBLEM"

# Path aliases take the line of another file, so the manifest still lists 38 entries and only the
# alias itself can fail.
fresh_tree
edit_listed "$BINARY_FILE" "$(sha_in_tree "$TEXT_FILE")  ./$TEXT_FILE"
check "./ alias of a listed file in place of another file's line" fail \
  "./$TEXT_FILE is not a canonical path" "$ONE_PROBLEM"

fresh_tree
edit_listed "$BINARY_FILE" "$(sha_in_tree "$TEXT_FILE")  app/src/main/res/font/../values/colors.xml"
check "alias with a .. segment in place of another file's line" fail \
  "font/../values/colors.xml is not a canonical path" "$ONE_PROBLEM"

fresh_tree
edit_listed "$BINARY_FILE" "$(sha_in_tree "$TEXT_FILE")  $TEXT_FILE"
check "duplicate path in place of another file's line" fail \
  "$TEXT_FILE is listed twice" "$ONE_PROBLEM"

fresh_tree
edit_listed "$LATER_FILE" "$(sha_in_tree "$TEXT_FILE")  app/src/main/res/Values/colors.xml"
check "path that differs from a listed one only in case" fail \
  "app/src/main/res/Values/colors.xml and $TEXT_FILE differ only in case" "$ONE_PROBLEM"

# The substitution is pinned in the checker: one entry, for LibraryTest.kt line 180 with the
# pinned SHA-256 of the listed file without that line.
fresh_tree
printf 'substituted  %s  %s  2  %s\n' "$(sha_in_tree "$TEXT_FILE")" "$TEXT_FILE" "$OTHER_SHA" >>"$tree/$manifest"
check "extra substituted entry for another listed file" fail \
  "substituted entry for $TEXT_FILE; only $SUBST_FILE line 180 is substituted" "$ONE_PROBLEM"

fresh_tree
sed -i '/^substituted  /p' "$tree/$manifest"
check "second substituted entry for the substituted file" fail \
  "second substituted entry for $SUBST_FILE" "$ONE_PROBLEM"

fresh_tree
sed -i '/^substituted  /d' "$tree/$manifest"
check "substituted entry dropped" fail "no substituted entry for $SUBST_FILE" "$ONE_PROBLEM"

fresh_tree
sed -i "/^substituted  /s/  [0-9a-f]\{64\}\$/  $OTHER_SHA/" "$tree/$manifest"
check "substituted entry with another SHA-256 without the line" fail \
  "records $OTHER_SHA without line 180, pinned" "$ONE_PROBLEM"

fresh_tree
edit_listed "$SUBST_FILE" "$OTHER_SHA  $SUBST_FILE"
check "listed sha256 of the substituted file changed" fail \
  "pinned 22baee644fdea92d6347e9a0b16b539f83ff63e6b408ec87fd48d12733af4ded from the issue" "$ONE_PROBLEM"

fresh_tree
sed -i '/^substituted  /s/  180  /  181  /' "$tree/$manifest"
check "substituted entry naming another line" fail \
  "names line 181, pinned 180" "$ONE_PROBLEM"

# A later change to a ported file: new SHA-256 in the manifest, the listed one in a record above.
fresh_tree
listed_before="$(sha_in_tree "$TEXT_FILE")"
flip_byte "$tree/$TEXT_FILE" 50
edit_listed "$TEXT_FILE" "# was $listed_before (#99)" "$(sha_in_tree "$TEXT_FILE")  $TEXT_FILE"
check "ported file changed later with its change record" pass \
  "ok    $TEXT_FILE (changed by #99; listed in issue #2 as $listed_before)" "PASS (38 listed files"

fresh_tree
edit_listed "$TEXT_FILE" "# was ${OTHER_SHA:0:12} (#99)" "$(sha_in_tree "$TEXT_FILE")  $TEXT_FILE"
check "malformed change record" fail "malformed change record" "$ONE_PROBLEM"

fresh_tree
printf '# was %s (#99)\n' "$OTHER_SHA" >>"$tree/$manifest"
check "change record not directly above a listed line" fail \
  "change record is not directly above a listed line" "$ONE_PROBLEM"

# A blank line between the record and a listed line detaches it, even in the middle of the file.
fresh_tree
listed_before="$(sha_in_tree "$TEXT_FILE")"
edit_listed "$TEXT_FILE" "# was $listed_before (#99)" "" "$listed_before  $TEXT_FILE"
check "change record separated from its listed line by a blank line" fail \
  "change record is not directly above a listed line" "$ONE_PROBLEM"

# The committed SHA-256 follows the edit, so only the hash with line 180 deleted can catch it.
fresh_tree
sed -i '20s/$/ /' "$tree/$SUBST_FILE"
rerecord_committed
check "substituted file edited outside line 180, committed sha256 re-recorded" fail \
  "FAIL  $SUBST_FILE: with line 180 deleted" "$ONE_PROBLEM"

# Line 180 still names the documentation host and every other line is intact: the recorded
# committed SHA-256 and the pinned line-180 text both catch it.
fresh_tree
sed -i '180s#collection\.example:8790/#collection.example:8791/#' "$tree/$SUBST_FILE"
check "another line-180 text, not re-recorded" fail \
  "FAIL  $SUBST_FILE: sha256" "recorded committed" \
  "FAIL  $SUBST_FILE: line 180 is not the documented text" "(2 problem(s) in"

# Re-recording the committed SHA-256 leaves only the pinned line-180 text to catch it.
fresh_tree
sed -i '180s#.*#        // collection.example#' "$tree/$SUBST_FILE"
rerecord_committed
check "line 180 gutted but still naming the documentation host, committed sha256 re-recorded" fail \
  "FAIL  $SUBST_FILE: line 180 is not the documented text" "$ONE_PROBLEM"

fresh_tree
sed -i '180s/collection\.example/collection.invalid/' "$tree/$SUBST_FILE"
rerecord_committed
check "line 180 without the documentation host, committed sha256 re-recorded" fail \
  "FAIL  $SUBST_FILE: line 180 does not contain collection.example" "$ONE_PROBLEM"

if [[ $failures -ne 0 ]]; then
  echo "test-kit-port: FAIL ($failures of $cases case(s))" >&2
  exit 1
fi
echo "test-kit-port: PASS ($cases cases)"
