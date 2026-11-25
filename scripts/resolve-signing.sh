#!/usr/bin/env bash
# Resolves the release signing material from the secret store (Infisical) through Universal Auth
# and writes it to DREMLA_SIGNING_DIR. Used by the signing smoke workflow and the release
# workflow; scripts/test-signing.sh is its self-test. See docs/release-signing.md.
#
# No arguments. Environment:
#   INFISICAL_API_URL        API base, https only, ending in /api (https://<host>/api)
#   INFISICAL_PROJECT_ID     project that holds the signing secrets
#   INFISICAL_CLIENT_ID      Universal Auth client ID of the read-only machine identity
#   INFISICAL_CLIENT_SECRET  Universal Auth client secret
#   DREMLA_SIGNING_DIR       existing, empty directory owned by the caller with mode 0700: owner
#                            rwx, nothing for group and other; setuid, setgid and sticky bits
#                            are ignored (scripts/signing-dir-create.sh makes one)
#   JAVA_HOME                keytool comes from $JAVA_HOME/bin when set, else from PATH
#
# Reads environment prod, folder /dremla/release/signing, keys RELEASE_KEYSTORE_B64,
# RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD, and writes, mode 0600:
#   release.p12  the decoded PKCS12 keystore
#   storepass    keystore password, no trailing newline (keytool -storepass:file, apksigner file:)
#   keypass      key password, no trailing newline
#   alias        key alias (always dremla-release), no trailing newline
# It passes only when the keystore opens with the password, the alias is a private key entry and
# its private key decrypts (keytool -certreq signs with it; the request is discarded).
#
# Secret values never reach argv, stdout, stderr or xtrace: request bodies go to curl on stdin,
# the bearer token goes through a header file, jq reads the credentials from its environment and
# writes values straight to files. The client credentials are kept out of the environment of
# every other child process. Every failure exits non-zero with a message that carries no secret
# and leaves DREMLA_SIGNING_DIR empty.
set -euo pipefail
set +x
umask 077

if [[ $# -ne 0 ]]; then
  echo "usage: scripts/resolve-signing.sh (no arguments; see the header for the environment)" >&2
  exit 2
fi

readonly SECRET_ENVIRONMENT="prod"
readonly SECRET_PATH_QUERY="%2Fdremla%2Frelease%2Fsigning" # /dremla/release/signing
# Raw values from this folder only, whatever the server defaults: no reference expansion, no
# imported secrets, no subfolders.
readonly SECRET_OPTIONS_QUERY="expandSecretReferences=false&include_imports=false&recursive=false"
readonly EXPECTED_ALIAS="dremla-release"
readonly KEY_B64="RELEASE_KEYSTORE_B64"
readonly KEY_STOREPASS="RELEASE_KEYSTORE_PASSWORD"
readonly KEY_ALIAS="RELEASE_KEY_ALIAS"
readonly KEY_KEYPASS="RELEASE_KEY_PASSWORD"

die() {
  echo "resolve-signing: FAIL: $*" >&2
  exit 1
}

# --- Configuration (nothing is written before every check passes) ---------------------------
for name in INFISICAL_API_URL INFISICAL_PROJECT_ID INFISICAL_CLIENT_ID INFISICAL_CLIENT_SECRET \
  DREMLA_SIGNING_DIR; do
  [[ -n "${!name:-}" ]] || die "$name is not set"
done
# Only jq, which builds the login body, gets the client credentials in its environment.
export -n INFISICAL_CLIENT_ID INFISICAL_CLIENT_SECRET

api="${INFISICAL_API_URL%/}"
re_api='^https://[^/?#@[:space:]]+(/[^?#[:space:]]*)?/api$'
[[ "$api" =~ $re_api ]] ||
  die "INFISICAL_API_URL must be an https:// URL ending in /api (https://<host>/api)"
project="$INFISICAL_PROJECT_ID"
[[ "$project" =~ ^[A-Za-z0-9_-]+$ ]] ||
  die "INFISICAL_PROJECT_ID may contain only letters, digits, '-' and '_'"

dir="$DREMLA_SIGNING_DIR"
[[ "$dir" == /* ]] || die "DREMLA_SIGNING_DIR must be an absolute path"
[[ ! -L "$dir" ]] || die "DREMLA_SIGNING_DIR must not be a symbolic link"
[[ -d "$dir" ]] || die "DREMLA_SIGNING_DIR does not exist or is not a directory"
[[ -O "$dir" ]] || die "DREMLA_SIGNING_DIR is not owned by the current user"
# Permission bits only: a setgid bit inherited from the parent does not open the directory.
[[ -n "$(find "$dir" -maxdepth 0 -perm -0700 ! -perm /0077)" ]] ||
  die "DREMLA_SIGNING_DIR must have mode 0700 (owner rwx, nothing for group and other)"
[[ -z "$(find "$dir" -mindepth 1 -maxdepth 1 -print -quit)" ]] ||
  die "DREMLA_SIGNING_DIR is not empty"

for tool in curl jq base64; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is not on PATH"
done
if [[ -n "${JAVA_HOME:-}" ]]; then
  keytool="$JAVA_HOME/bin/keytool"
  [[ -x "$keytool" ]] || die "JAVA_HOME is set but has no bin/keytool"
else
  keytool="$(command -v keytool)" ||
    die "keytool not found: set JAVA_HOME to a JDK 17 or put keytool on PATH"
fi
# English messages under any locale, so the "keytool error:" line is recognised. Only the message
# language changes: the encoding that keytool reads the password files with stays as it is.
keytool_cmd=("$keytool" -J-Duser.language=en)

# --- Files -----------------------------------------------------------------------------------
p12="$dir/release.p12"
storepass="$dir/storepass"
keypass="$dir/keypass"
alias_file="$dir/alias"
login_json="$dir/.login.json"
auth_header="$dir/.auth-header"
secrets_json="$dir/.secrets.json"

# wipe <file...>: overwrite (when shred exists) and delete.
wipe() {
  local file
  for file in "$@"; do
    [[ -e "$file" ]] || continue
    if command -v shred >/dev/null 2>&1; then
      shred --remove --iterations=1 -- "$file" 2>/dev/null || rm -f -- "$file"
    else
      rm -f -- "$file"
    fi
  done
}

# The directory was empty, so every file in it is ours: intermediates always go, outputs go on
# failure.
on_exit() {
  local rc=$?
  wipe "$login_json" "$auth_header" "$secrets_json"
  if [[ $rc -ne 0 ]]; then
    wipe "$p12" "$storepass" "$keypass" "$alias_file"
  fi
  exit "$rc"
}
trap on_exit EXIT

# At most about 3 minutes for the two requests together (3 tries of 30 s each), well inside the
# 5 minute timeout of the workflow step.
curl_options=(--silent --show-error --proto "=https" --connect-timeout 15 --max-time 30
  --retry 2 --retry-delay 2)

# login_hint <status> / secrets_hint <status>: a non-secret hint for common statuses.
login_hint() {
  case "$1" in
    000) echo " (no response; check INFISICAL_API_URL and the network)" ;;
    400 | 401 | 403)
      echo " (check INFISICAL_CLIENT_ID, INFISICAL_CLIENT_SECRET and the identity's trusted IPs)"
      ;;
    404) echo " (check INFISICAL_API_URL)" ;;
    *) echo "" ;;
  esac
}
secrets_hint() {
  case "$1" in
    000) echo " (no response; check INFISICAL_API_URL and the network)" ;;
    400 | 404) echo " (check INFISICAL_PROJECT_ID, the environment and the folder)" ;;
    401) echo " (the access token was rejected)" ;;
    403) echo " (the identity has no read access to $SECRET_ENVIRONMENT /dremla/release/signing)" ;;
    *) echo "" ;;
  esac
}

# --- Universal Auth login ------------------------------------------------------------------
rc=0
status="$(
  INFISICAL_CLIENT_ID="$INFISICAL_CLIENT_ID" INFISICAL_CLIENT_SECRET="$INFISICAL_CLIENT_SECRET" \
    jq -cn '{clientId: env.INFISICAL_CLIENT_ID, clientSecret: env.INFISICAL_CLIENT_SECRET}' |
    curl "${curl_options[@]}" --request POST --header "Content-Type: application/json" \
      --data-binary @- --output "$login_json" --write-out "%{http_code}" \
      "$api/v1/auth/universal-auth/login"
)" || rc=$?
[[ $rc -eq 0 ]] || die "Infisical login failed: curl exit $rc$(login_hint "${status:-000}")"
[[ "$status" == "200" ]] || die "Infisical login failed: HTTP $status$(login_hint "$status")"
# jq stderr is dropped everywhere below: its error messages can quote parts of the input.
jq -e 'type == "object" and (.accessToken | type == "string" and test("^[!-~]+$"))' \
  "$login_json" >/dev/null 2>&1 || die "Infisical login failed: the response has no access token"
jq -r '"Authorization: Bearer " + .accessToken' "$login_json" >"$auth_header" 2>/dev/null ||
  die "cannot write the authorization header file"
wipe "$login_json"

# --- Secrets -------------------------------------------------------------------------------
rc=0
query="workspaceId=$project&environment=$SECRET_ENVIRONMENT&secretPath=$SECRET_PATH_QUERY"
query+="&$SECRET_OPTIONS_QUERY"
status="$(
  curl "${curl_options[@]}" --header "@$auth_header" --output "$secrets_json" \
    --write-out "%{http_code}" "$api/v3/secrets/raw?$query"
)" || rc=$?
wipe "$auth_header"
[[ $rc -eq 0 ]] ||
  die "Infisical secrets request failed: curl exit $rc$(secrets_hint "${status:-000}")"
[[ "$status" == "200" ]] ||
  die "Infisical secrets request failed: HTTP $status$(secrets_hint "$status")"
jq -e 'type == "object" and (.secrets | type == "array") and all(.secrets[]; type == "object")' \
  "$secrets_json" >/dev/null 2>&1 || die "unexpected secrets response (no secrets array)"

# check_key <key> <single-line: 0|1>: exactly one readable, non-empty string value.
problems=()
check_key() {
  local key="$1" single_line="$2" count
  count="$(jq -r --arg k "$key" '[.secrets[] | select(.secretKey == $k)] | length' \
    "$secrets_json" 2>/dev/null)" || die "cannot read the secrets response"
  if [[ "$count" == "0" ]]; then
    problems+=("$key is missing")
    return
  fi
  if [[ "$count" != "1" ]]; then
    problems+=("$key appears $count times")
    return
  fi
  if jq -e --arg k "$key" '.secrets[] | select(.secretKey == $k) | .secretValueHidden == true' \
    "$secrets_json" >/dev/null 2>&1; then
    problems+=("$key value is hidden from the identity (it needs permission to read values)")
    return
  fi
  if ! jq -e --arg k "$key" \
    '.secrets[] | select(.secretKey == $k) | .secretValue | type == "string" and length > 0' \
    "$secrets_json" >/dev/null 2>&1; then
    problems+=("$key is empty")
    return
  fi
  if [[ "$single_line" == 1 ]] && ! jq -e --arg k "$key" \
    '.secrets[] | select(.secretKey == $k) | .secretValue | test("[\r\n]") | not' \
    "$secrets_json" >/dev/null 2>&1; then
    problems+=("$key contains a line break")
  fi
}
check_key "$KEY_B64" 0
check_key "$KEY_STOREPASS" 1
check_key "$KEY_KEYPASS" 1
check_key "$KEY_ALIAS" 1
if [[ ${#problems[@]} -ne 0 ]]; then
  summary="${problems[0]}"
  for problem in "${problems[@]:1}"; do
    summary+="; $problem"
  done
  die "secrets in $SECRET_ENVIRONMENT /dremla/release/signing: $summary"
fi

# write_value <key> <file>: the raw value, no trailing newline.
write_value() {
  jq -j --arg k "$1" '.secrets[] | select(.secretKey == $k) | .secretValue' \
    "$secrets_json" >"$2" 2>/dev/null || die "cannot write the value of $1"
}
write_value "$KEY_ALIAS" "$alias_file"
[[ "$(<"$alias_file")" == "$EXPECTED_ALIAS" ]] || die "$KEY_ALIAS is not '$EXPECTED_ALIAS'"
write_value "$KEY_STOREPASS" "$storepass"
write_value "$KEY_KEYPASS" "$keypass"
# A PKCS12 keystore made by keytool or openssl has one password for the store and the key, and
# keytool ignores a separate key password for PKCS12, so a different value would only fail later,
# at signing time.
[[ "$(<"$storepass")" == "$(<"$keypass")" ]] ||
  die "$KEY_KEYPASS differs from $KEY_STOREPASS (a PKCS12 keystore has one password)"

# Whitespace (line wrapping) is dropped before decoding.
jq -e --arg k "$KEY_B64" '.secrets[] | select(.secretKey == $k) | .secretValue | gsub("\\s"; "")
    | test("^[A-Za-z0-9+/]+={0,2}$") and length % 4 == 0' "$secrets_json" >/dev/null 2>&1 ||
  die "$KEY_B64 is not valid base64"
jq -j --arg k "$KEY_B64" '.secrets[] | select(.secretKey == $k) | .secretValue | gsub("\\s"; "")' \
  "$secrets_json" 2>/dev/null | base64 -d >"$p12" 2>/dev/null || die "$KEY_B64 does not decode"
[[ -s "$p12" ]] || die "$KEY_B64 decodes to an empty file"
wipe "$secrets_json"

# --- Keystore ------------------------------------------------------------------------------
# keytool prints its errors on stdout; only its "keytool error:" line is shown.
rc=0
listing="$("${keytool_cmd[@]}" -list -keystore "$p12" -storetype PKCS12 \
  -storepass:file "$storepass" -alias "$EXPECTED_ALIAS" </dev/null 2>&1)" || rc=$?
if [[ $rc -ne 0 ]]; then
  reason="$(grep -m 1 '^keytool error:' <<<"$listing" || true)"
  die "the keystore does not open with $KEY_STOREPASS and alias $EXPECTED_ALIAS:" \
    "${reason:-keytool exit $rc}"
fi
[[ "$listing" == *"PrivateKeyEntry"* ]] || die "alias $EXPECTED_ALIAS is not a private key entry"

# -list does not decrypt the private key; a certificate request is signed with it. The request
# (public data) goes to stdout and is discarded with the rest of the output.
rc=0
output="$("${keytool_cmd[@]}" -certreq -keystore "$p12" -storetype PKCS12 \
  -storepass:file "$storepass" -keypass:file "$keypass" -alias "$EXPECTED_ALIAS" \
  </dev/null 2>&1)" || rc=$?
if [[ $rc -ne 0 ]]; then
  reason="$(grep -m 1 '^keytool error:' <<<"$output" || true)"
  die "the private key of alias $EXPECTED_ALIAS does not decrypt with $KEY_KEYPASS:" \
    "${reason:-keytool exit $rc}"
fi

echo "resolve-signing: PASS (release.p12, storepass, keypass and alias in DREMLA_SIGNING_DIR)"
