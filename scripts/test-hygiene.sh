#!/usr/bin/env bash
# Self-test for scripts/check-public-hygiene.sh. Builds fixtures at runtime in a scratch git
# repository, asserts that every forbidden pattern is caught, in file content and in path names,
# and that clean or allowed content passes. Fixture strings are concatenated at runtime, so no
# violation is ever committed. No arguments.
set -euo pipefail

if [[ $# -ne 0 ]]; then
  echo "usage: scripts/test-hygiene.sh (no arguments)" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checker="$repo_root/scripts/check-public-hygiene.sh"
[[ -f "$checker" ]] || { echo "test-hygiene: missing $checker" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
repo="$work/repo"

# Fragments joined at runtime.
home_linux="/ho""me/"
home_mac="/Us""ers/"
net10="1""0"
net172="17""2"
net192="19""2.16""8"
adb_port=":555""5"
# Wireless-debugging style five-digit ports.
port5=":37""123"
port5_pair=":41""235"
# 203.0.113.0/24 is the TEST-NET-3 documentation range: public, so only the endpoint rule fires.
doc_endpoint="203.0.113.7"":8080"
# fd00::/8 is a unique-local IPv6 range.
ipv6_literal="[fd""00::12]"
state_dir=".maes""tro"
state_db="maes""tro.db"

readonly RULE_HOME="absolute home-directory path"
readonly RULE_RFC1918="RFC 1918 address"
readonly RULE_IPV4="IPv4 host:port endpoint"
readonly RULE_ADB="adb host:port endpoint"
readonly RULE_FIVE="five-digit-port host:port endpoint"
readonly RULE_STATE="private orchestrator state path"

failures=0
cases=0

# new_repo: an empty scratch repository with a copy of the checker.
new_repo() {
  rm -rf "$repo"
  mkdir -p "$repo/scripts"
  git -C "$repo" init -q
  cp "$checker" "$repo/scripts/check-public-hygiene.sh"
}

# write_text <path> <content>
write_text() {
  mkdir -p "$(dirname "$repo/$1")"
  printf '%s\n' "$2" >"$repo/$1"
}

# write_binary <path> <payload>: a file git treats as binary (it contains NUL bytes).
write_binary() {
  mkdir -p "$(dirname "$repo/$1")"
  printf 'PNG\000\001%s\000' "$2" >"$repo/$1"
}

# check <name> <expect: pass|fail> <rule label that must fire> <hit location that must be printed>
# Stages the scratch repository and runs the checker on it.
check() {
  local name="$1" expect="$2" rule="$3" location="$4"
  git -C "$repo" add -A
  local out rc=0
  out="$(bash "$repo/scripts/check-public-hygiene.sh" 2>&1)" || rc=$?
  cases=$((cases + 1))

  local verdict="ok"
  if [[ "$expect" == "fail" ]]; then
    [[ $rc -eq 1 && "$out" == *"$location"* && "$out" == *"FAIL  $rule"* ]] || verdict="FAIL"
  else
    [[ $rc -eq 0 ]] || verdict="FAIL"
  fi
  if [[ "$verdict" == "ok" ]]; then
    echo "ok    $name (expected $expect)"
  else
    echo "FAIL  $name (expected $expect, exit $rc)"
    printf '%s\n' "$out" | sed 's/^/        /'
    failures=$((failures + 1))
  fi
}

# run_case <name> <expect: pass|fail> <fixture content> [rule label that must fire]
# The content goes to fixture.txt; a hit must be reported on its first line.
run_case() {
  new_repo
  write_text fixture.txt "$3"
  check "$1" "$2" "${4:-}" "fixture.txt:1:"
}

# run_path_case <name> <expect: pass|fail> <path> <text content> [rule label that must fire]
# A hit must be reported on the path name itself.
run_path_case() {
  new_repo
  write_text "$3" "$4"
  check "$1" "$2" "${5:-}" "$3:0:$3"
}

# run_binary_case <name> <expect: pass|fail> <path> <binary payload> [rule label that must fire]
run_binary_case() {
  new_repo
  write_binary "$3" "$4"
  check "$1" "$2" "${5:-}" "$3:0:$3"
}

# Must be caught: file content.
run_case "linux home path" fail "see ${home_linux}alice/src/app" "$RULE_HOME"
run_case "macOS home path" fail "open ${home_mac}alice/Library" "$RULE_HOME"
run_case "file URI to a home path" fail "[notes](file://${home_linux}alice/notes.md)" "$RULE_HOME"
run_case "home path with a variable" fail "cd ${home_linux}\$USER/src" "$RULE_HOME"
run_case "macOS home path with a placeholder" fail "${home_mac}<name>/Library" "$RULE_HOME"
run_case "home path glued to a hostname" fail "host${home_linux}x" "$RULE_HOME"
run_case "public URL with a home segment" fail "https://example.com${home_linux}index.html" "$RULE_HOME"
run_case "bare home root" fail "under ${home_linux} on the box" "$RULE_HOME"
run_case "RFC 1918 10/8" fail "collection at ${net10}.20.30.40 on the LAN" "$RULE_RFC1918"
run_case "RFC 1918 172.16/12" fail "gateway ${net172}.20.0.1" "$RULE_RFC1918"
run_case "RFC 1918 192.168/16" fail "box=${net192}.1.20" "$RULE_RFC1918"
run_case "partial 192.168 address" fail "the box is ${net192}.1.x" "$RULE_RFC1918"
run_case "partial 172.16 address" fail "the box is ${net172}.16.5.x" "$RULE_RFC1918"
run_case "172.31 upper bound" fail "gateway ${net172}.31.255.254" "$RULE_RFC1918"
run_case "nip.io host with a 192.168 address" fail "open http://tv.${net192}.1.20.nip.io/" "$RULE_RFC1918"
run_case "nip.io host with a 10/8 address" fail "open http://tv.${net10}.0.0.5.nip.io/" "$RULE_RFC1918"
run_case "IPv4 endpoint with port" fail "adb connect ${doc_endpoint}" "$RULE_IPV4"
run_case "hostname with adb port" fail "adb connect tv-box.example${adb_port}" "$RULE_ADB"
run_case "IPv6 literal with adb port" fail "adb connect ${ipv6_literal}${adb_port}" "$RULE_ADB"
run_case "hostname starting with 127. and adb port" fail "adb connect 127.tv-box.example${adb_port}" "$RULE_ADB"
run_case "hostname with a five-digit port" fail "adb connect tv-box.example${port5}" "$RULE_FIVE"
run_case "adb pair with a five-digit port" fail "adb pair tv-box.example${port5_pair} 123456" "$RULE_FIVE"
run_case "URL with a five-digit port" fail "https://tv-box.example.net${port5}/json" "$RULE_FIVE"
run_case "IPv6 literal with a five-digit port" fail "adb connect ${ipv6_literal}${port5}" "$RULE_FIVE"
run_case "loopback then public endpoint" fail "127.0.0.1:80 ${doc_endpoint}" "$RULE_IPV4"
run_case "localhost then public adb host" fail "localhost${adb_port} tv-box.example${adb_port}" "$RULE_ADB"
run_case "localhost then public five-digit host" fail "localhost${port5} tv-box.example${port5}" "$RULE_FIVE"
run_case "orchestrator state directory" fail "state lives in ~/${state_dir}/runs" "$RULE_STATE"
run_case "orchestrator database" fail "sqlite3 ${state_db} .tables" "$RULE_STATE"

# Must be caught: path names, whatever the content.
run_binary_case "binary orchestrator database file" fail "data/${state_db}" "SQLite format 3" "$RULE_STATE"
run_path_case "clean file under the orchestrator directory" fail "${state_dir}/runs/notes.txt" \
  "Dremla plays calm piano." "$RULE_STATE"
run_path_case "orchestrator directory in another case" fail "docs/.MAES""TRO/notes.txt" \
  "Dremla plays calm piano." "$RULE_STATE"
run_path_case "file name with an RFC 1918 address" fail "notes/${net192}.1.20-box.md" \
  "Dremla plays calm piano." "$RULE_RFC1918"
run_path_case "file name with a five-digit endpoint" fail "notes/tv-box.example${port5}.md" \
  "Dremla plays calm piano." "$RULE_FIVE"

# Must pass.
run_case "clean text" pass "Dremla plays calm piano until the timer ends."
run_case "loopback endpoint" pass "server listens on 127.0.0.1:8080 and localhost${adb_port}"
run_case "loopback right after a letter" pass "ref=x127.0.0.1:8080"
run_case "port longer than five digits" pass "id 1.2.3.4:123456 is not an endpoint"
run_case "SDK path outside home" pass "ANDROID_HOME=/opt/android-sdk"
run_case "address outside 172.16/12" pass "${net172}.32.0.1 is public"
run_case "address below 172.16/12" pass "${net172}.15.0.1 is public"
run_case "version numbers" pass "versions 1.${net10}.0.0 and 2024.12.01"
run_case "localhost with a five-digit port" pass "dev server on localhost${port5} and http://LocalHost${port5}/"
run_case "127.0.0.1 with a five-digit port" pass "dev server on 127.0.0.1${port5}"
run_case "IPv6 loopback with ports" pass "listens on [::1]${port5}, [::1]${adb_port} and [::]${port5}"
run_case "Gradle coordinates" pass \
  "androidx.compose:compose-bom:2024.12.01 io.github.takahirom.roborazzi:roborazzi:1.32.2"
run_case "digests, schemes and times" pass "sha256:12345abcdef at 12:34:56 via https://example.com/x"
run_path_case "clean path names" pass "app/src/main/res/values/strings_brand.xml" "<resources/>"

# Binary content is skipped (only the name is checked).
run_binary_case "binary file skipped" pass "image.bin" "${home_linux}alice"

if [[ $failures -ne 0 ]]; then
  echo "test-hygiene: FAIL ($failures of $cases case(s))" >&2
  exit 1
fi
echo "test-hygiene: PASS ($cases cases)"
