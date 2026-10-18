#!/usr/bin/env bash
# Public-repository hygiene. Scans every git-tracked file: the content of text files (binary
# content is skipped) and the path name of every file, binary files included. Fails on:
#   - absolute home-directory paths (content only): any occurrence of the Linux or macOS home
#     root followed by a slash, wherever it appears (bare, mid-word, inside a URL),
#   - RFC 1918 IPv4 addresses: a full 10/8 address (four octets, so version strings such as
#     1.10.0.0 pass), and 192.168/16 or 172.16/12 as soon as the third octet starts, so partial
#     addresses and dotted hosts (nip.io style) are caught too,
#   - host:port device endpoints:
#       an IPv4 address with any port,
#       a host name (a token with a letter) or a bracketed IPv6 literal with the adb port,
#       a host name or a bracketed IPv6 literal with a five-digit port (Android 11+ wireless
#       debugging listens on a random five-digit port);
#     loopback (127/8, 0.0.0.0, localhost in any case, [::1], [::]) is allowed, and every
#     endpoint on a line is checked on its own, so a loopback endpoint never hides a public one
#     next to it,
#   - private orchestrator state paths (its dot-directory as a path segment, its database file).
# Content hits print as file:line:content, path-name hits as path:0:path. No arguments;
# scripts/test-hygiene.sh is its self-test.
#
# The patterns are written so that this file never matches itself (for example /hom[e]/), so
# nothing has to be excluded from the scan.
set -euo pipefail

if [[ $# -ne 0 ]]; then
  echo "usage: scripts/check-public-hygiene.sh (no arguments)" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

readonly OCTET='[0-9]{1,3}'
readonly IPV4="${OCTET}\\.${OCTET}\\.${OCTET}\\.${OCTET}"
readonly RE_HOME_PATH='/(hom[e]|User[s])/'
# 10/8: a whole address; a leading dot is allowed only after a letter (tv.<address>.nip.io).
readonly RE_RFC1918_NET10="(^|[^0-9.]|[[:alpha:]]\\.)1[0]\\.${OCTET}\\.${OCTET}\\.${OCTET}([^0-9]|$)"
# 192.168/16 and 172.16/12: the two fixed octets plus the first digit of the third one.
readonly RE_RFC1918_PREFIXED="(^|[^0-9])(19[2]\\.16[8]|17[2]\\.(1[6-9]|2[0-9]|3[01]))\\.[0-9]"
readonly RE_RFC1918="${RE_RFC1918_NET10}|${RE_RFC1918_PREFIXED}"
readonly RE_ORCHESTRATOR_STATE='(^|[^[:alnum:]_-])\.maestr[o]([^[:alnum:]_-]|$)|maestr[o]\.db'

# Endpoint rules: the endpoint itself, the character class that must not come right before it,
# and the one that must not come right after it (the port would be longer, or it is not a port).
# A match that starts with "[" (an IPv6 literal) has no constraint on the character before it.
readonly HOST_NAME='[[:alnum:]_.-]*[[:alpha:]][[:alnum:]_.-]*'
readonly HOST_IPV6='\[[0-9A-Fa-f.:]*:[0-9A-Fa-f.:]*(%[[:alnum:]_.-]+)?\]'
readonly RE_IPV4_PORT_CORE="${IPV4}:[0-9]{1,5}"
readonly RE_IPV4_PORT_BAD_PREV='^[0-9.]$'
readonly RE_IPV4_PORT_BAD_NEXT='^[0-9]$'
readonly RE_ADB_HOST_CORE="(${HOST_NAME}|${HOST_IPV6}):555[5]"
readonly RE_FIVE_DIGIT_HOST_CORE="(${HOST_NAME}|${HOST_IPV6}):[0-9]{5}"
readonly RE_HOST_BAD_PREV='^[[:alnum:]_.-]$'
readonly RE_HOST_BAD_NEXT='^[[:alnum:]_]$'

readonly RE_LOOPBACK_IPV4="^127\\.${OCTET}\\.${OCTET}\\.${OCTET}:"
readonly RE_LOOPBACK_IPV6='^\[::1?\]:'

hits_file="$(mktemp)"
names_file="$(mktemp)"
trap 'rm -f "$hits_file" "$names_file"' EXIT

git ls-files -z >"$names_file"
tracked_names=()
while IFS= read -r -d '' name; do
  tracked_names+=("$name")
done <"$names_file"

total_hits=0
hits=()

# is_loopback <endpoint>
is_loopback() {
  local endpoint="$1" result=1
  shopt -s nocasematch
  if [[ "$endpoint" =~ $RE_LOOPBACK_IPV4 || "$endpoint" =~ $RE_LOOPBACK_IPV6 ||
    "$endpoint" == 0.0.0.0:* || "$endpoint" == localhost:* ]]; then
    result=0
  fi
  shopt -u nocasematch
  return "$result"
}

# has_public_endpoint <endpoint regex> <bad-previous regex> <bad-next regex> <text>
# Walks every occurrence of the endpoint regex in the text, applies the boundary rules without
# consuming the neighbouring characters, and succeeds on the first non-loopback endpoint.
has_public_endpoint() {
  local core="$1" bad_prev="$2" bad_next="$3" rest="$4" prev="" match before after
  while [[ "$rest" =~ $core ]]; do
    match="${BASH_REMATCH[0]}"
    # The regex has no context, so its leftmost match is the first literal occurrence.
    before="${rest%%"$match"*}"
    if [[ -n "$before" ]]; then
      prev="${before: -1}"
    fi
    after="${rest:$((${#before} + ${#match}))}"
    if [[ ("$match" == "["* || ! "$prev" =~ $bad_prev) && ! "${after:0:1}" =~ $bad_next ]]; then
      if ! is_loopback "$match"; then
        return 0
      fi
      prev="${match: -1}"
      rest="$after"
    else
      # Not a whole endpoint here; a later start inside the match may still be one.
      prev="${match:0:1}"
      rest="${match:1}${after}"
    fi
  done
  return 1
}

# matches <regex> <case-insensitive: 0|1> <text>
matches() {
  local regex="$1" ci="$2" text="$3" result=1
  if [[ "$ci" == 1 ]]; then
    shopt -s nocasematch
  fi
  if [[ "$text" =~ $regex ]]; then
    result=0
  fi
  shopt -u nocasematch
  return "$result"
}

# report <rule label>: prints and counts the hits collected in $hits, then clears them.
report() {
  local label="$1"
  if [[ ${#hits[@]} -eq 0 ]]; then
    echo "ok    $label"
  else
    echo "FAIL  $label: ${#hits[@]} hit(s)"
    printf '        %s\n' "${hits[@]}"
    total_hits=$((total_hits + ${#hits[@]}))
  fi
  hits=()
}

# grep_content <rule label> <case-insensitive: 0|1> <pattern>
# Writes the matching lines of tracked text files to $hits_file as NUL-separated "file", "line",
# "content" records.
grep_content() {
  local label="$1" ci="$2" pattern="$3" options=(-I -n -z --no-color -E) rc=0
  if [[ "$ci" == 1 ]]; then
    options+=(-i)
  fi
  git grep "${options[@]}" -e "$pattern" >"$hits_file" || rc=$?
  if [[ $rc -gt 1 ]]; then
    echo "check-public-hygiene: git grep failed (exit $rc) for rule '$label'" >&2
    exit 2
  fi
}

# scan <rule label> <case-insensitive: 0|1> <check path names: 0|1> <pattern>
scan() {
  local label="$1" ci="$2" check_names="$3" pattern="$4" file lineno content name
  grep_content "$label" "$ci" "$pattern"
  while IFS= read -r -d '' file && IFS= read -r -d '' lineno && IFS= read -r content; do
    hits+=("$file:$lineno:$content")
  done <"$hits_file"
  if [[ "$check_names" == 1 ]]; then
    for name in ${tracked_names[@]+"${tracked_names[@]}"}; do
      if matches "$pattern" "$ci" "$name"; then
        hits+=("$name:0:$name")
      fi
    done
  fi
  report "$label"
}

# scan_endpoints <rule label> <endpoint regex> <bad-previous regex> <bad-next regex>
# Content and path names.
scan_endpoints() {
  local label="$1" core="$2" bad_prev="$3" bad_next="$4" file lineno content name
  grep_content "$label" 0 "$core"
  while IFS= read -r -d '' file && IFS= read -r -d '' lineno && IFS= read -r content; do
    if has_public_endpoint "$core" "$bad_prev" "$bad_next" "$content"; then
      hits+=("$file:$lineno:$content")
    fi
  done <"$hits_file"
  for name in ${tracked_names[@]+"${tracked_names[@]}"}; do
    if has_public_endpoint "$core" "$bad_prev" "$bad_next" "$name"; then
      hits+=("$name:0:$name")
    fi
  done
  report "$label"
}

echo "public hygiene: scanning ${#tracked_names[@]} tracked files (text content and every path name)"
scan "absolute home-directory path" 0 0 "$RE_HOME_PATH"
scan "RFC 1918 address" 0 1 "$RE_RFC1918"
scan_endpoints "IPv4 host:port endpoint" \
  "$RE_IPV4_PORT_CORE" "$RE_IPV4_PORT_BAD_PREV" "$RE_IPV4_PORT_BAD_NEXT"
scan_endpoints "adb host:port endpoint" \
  "$RE_ADB_HOST_CORE" "$RE_HOST_BAD_PREV" "$RE_HOST_BAD_NEXT"
scan_endpoints "five-digit-port host:port endpoint" \
  "$RE_FIVE_DIGIT_HOST_CORE" "$RE_HOST_BAD_PREV" "$RE_HOST_BAD_NEXT"
scan "private orchestrator state path" 1 1 "$RE_ORCHESTRATOR_STATE"

if [[ $total_hits -ne 0 ]]; then
  echo "check-public-hygiene: FAIL ($total_hits hit(s)); remove them before publishing" >&2
  exit 1
fi
echo "check-public-hygiene: PASS"
