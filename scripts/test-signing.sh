#!/usr/bin/env bash
# Self-test for scripts/resolve-signing.sh, scripts/signing-cert-digest.sh,
# scripts/signing-dir-create.sh and scripts/signing-dir-remove.sh. Host only: no network and no
# real secret. It creates a throwaway PKCS12 keystore (RSA 2048, alias
# dremla-release, random passwords) and runs the resolver with a stub curl first on PATH. The
# stub emulates the Universal Auth login and the raw secrets endpoint from canned JSON and
# records its argv and environment. jq and keytool are reached through logging wrappers (jq first
# on PATH, keytool through JAVA_HOME or PATH) that record their argv, and keytool's environment,
# before running the real tool. Asserts that the success path writes the four files and that the
# digest script prints the certificate SHA-256 computed independently from the keystore; that
# every failure fails closed (exit 1, a clear message, an empty signing directory); and that no
# secret value reaches the argv of curl, jq or keytool, the environment of curl or keytool, or
# any output. The signing directory scripts are run against scratch RUNNER_TEMP directories:
# create makes a 0700 directory (setgid cleared) and records it in GITHUB_ENV; remove deletes a
# filled directory, also when shred fails or is missing, refuses foreign paths and symbolic
# links, and fails when the directory survives rm. No arguments.
set -euo pipefail
set +x

if [[ $# -ne 0 ]]; then
  echo "usage: scripts/test-signing.sh (no arguments)" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
resolver="$repo_root/scripts/resolve-signing.sh"
digester="$repo_root/scripts/signing-cert-digest.sh"
dir_create="$repo_root/scripts/signing-dir-create.sh"
dir_remove="$repo_root/scripts/signing-dir-remove.sh"

die() {
  echo "test-signing: $*" >&2
  exit 1
}

for script in "$resolver" "$digester" "$dir_create" "$dir_remove"; do
  [[ -f "$script" ]] || die "missing $script"
done
for tool in jq base64 sha256sum od cmp fold shred chgrp id; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is not on PATH"
done
# Same JDK resolution as the scripts under test: $JAVA_HOME/bin when set, else PATH.
if [[ -n "${JAVA_HOME:-}" ]]; then
  keytool="$JAVA_HOME/bin/keytool"
  [[ -x "$keytool" ]] || die "JAVA_HOME is set but has no bin/keytool"
else
  keytool="$(command -v keytool)" ||
    die "keytool not found: set JAVA_HOME to a JDK 17 or put keytool on PATH"
fi
keytool="$(readlink -f "$keytool")"
# java from the same JDK, for one fixture (a key that does not decrypt).
java_bin="$(dirname "$keytool")/java"
[[ -x "$java_bin" ]] || die "no java next to $keytool"
real_jq="$(command -v jq)"
real_shred="$(command -v shred)"
# English keytool messages under any locale, as in the scripts under test.
keytool_cmd=("$keytool" -J-Duser.language=en)

umask 077
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fixtures="$work/fixtures"
stub_bin="$work/bin"
fake_jdk="$work/jdk"
mkdir -p "$fixtures" "$stub_bin" "$fake_jdk/bin"

readonly ALIAS="dremla-release"
readonly API="https://secrets.example.invalid/api"
readonly PROJECT="00000000-0000-4000-8000-000000000007"

random_hex() {
  od -An -N16 -tx1 /dev/urandom | tr -d ' \n'
}

# --- Throwaway material --------------------------------------------------------------------
# Values are written with printf (a builtin) and never passed to an external command. The
# password carries shell and JSON metacharacters to check that nothing quotes or evaluates it.
printf '%s' "test-client-$(random_hex)" >"$fixtures/client_id"
printf '%s' "test-secret-$(random_hex)" >"$fixtures/client_secret"
printf '%s' "test.$(random_hex).token" >"$fixtures/token"
printf '%s' "pw-$(random_hex)-\$x\"'%\\ !" >"$fixtures/storepass"
printf '%s' "$ALIAS" >"$fixtures/alias"

keystore="$fixtures/release.p12"
"${keytool_cmd[@]}" -genkeypair -keystore "$keystore" -storetype PKCS12 \
  -storepass:file "$fixtures/storepass" -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 2 \
  -dname "CN=Dremla signing self-test" -noprompt </dev/null >"$work/genkeypair.log" 2>&1 ||
  { cat "$work/genkeypair.log" >&2; die "keytool -genkeypair failed"; }

# Expected digest from the keystore listing: a different code path from the export and sha256sum
# in scripts/signing-cert-digest.sh.
listing="$("${keytool_cmd[@]}" -list -v -keystore "$keystore" -storetype PKCS12 \
  -storepass:file "$fixtures/storepass" -alias "$ALIAS" </dev/null 2>&1)" ||
  die "keytool -list failed on the throwaway keystore"
re_fingerprint='SHA256: ([0-9A-F:]{95})'
[[ "$listing" =~ $re_fingerprint ]] || die "no SHA256 fingerprint in the keystore listing"
expected_digest="$(tr -d ':' <<<"${BASH_REMATCH[1]}" | tr 'A-F' 'a-f')"
[[ "$expected_digest" =~ ^[0-9a-f]{64}$ ]] || die "unexpected fingerprint format"

# The same key and store password, but the key encrypted with another password: keytool -list
# and -exportcert still work, signing does not. keytool cannot make this for PKCS12, the
# KeyStore API can. The passwords are read from files, not passed as arguments.
printf '%s' "other-key-$(random_hex)" >"$fixtures/other-keypass"
cat >"$work/Rekey.java" <<'JAVA'
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;

// Rekey <in.p12> <store-password-file> <out.p12> <key-password-file> <alias>
public class Rekey {
  public static void main(String[] args) throws Exception {
    char[] store = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8).toCharArray();
    char[] key = Files.readString(Path.of(args[3]), StandardCharsets.UTF_8).toCharArray();
    KeyStore in = KeyStore.getInstance("PKCS12");
    try (var stream = Files.newInputStream(Path.of(args[0]))) {
      in.load(stream, store);
    }
    KeyStore out = KeyStore.getInstance("PKCS12");
    out.load(null, store);
    out.setKeyEntry(args[4], (PrivateKey) in.getKey(args[4], store), key,
        in.getCertificateChain(args[4]));
    try (var stream = Files.newOutputStream(Path.of(args[2]))) {
      out.store(stream, store);
    }
  }
}
JAVA
# An empty class path, so no class from the current directory or CLASSPATH is loaded.
mkdir "$work/classpath"
"$java_bin" --class-path "$work/classpath" "$work/Rekey.java" "$keystore" "$fixtures/storepass" \
  "$fixtures/bad-key.p12" "$fixtures/other-keypass" "$ALIAS" </dev/null >"$work/rekey.log" 2>&1 ||
  { cat "$work/rekey.log" >&2; die "cannot build the keystore with an undecryptable key"; }

base64 <"$keystore" | tr -d '\n' >"$fixtures/b64"
fold -w 64 "$fixtures/b64" >"$fixtures/b64-wrapped"
head -c 512 /dev/urandom | base64 | tr -d '\n' >"$fixtures/b64-not-a-keystore"
base64 <"$fixtures/bad-key.p12" | tr -d '\n' >"$fixtures/b64-bad-key"

# --- Canned responses ----------------------------------------------------------------------
jq -n --rawfile token "$fixtures/token" \
  '{accessToken: $token, expiresIn: 600, accessTokenMaxTTL: 600, tokenType: "Bearer"}' \
  >"$fixtures/login-ok.json"
jq -n '{expiresIn: 600, tokenType: "Bearer"}' >"$fixtures/login-no-token.json"
jq -n --rawfile b64 "$fixtures/b64" --rawfile pass "$fixtures/storepass" --arg alias "$ALIAS" '
  {secrets: [
    {secretKey: "RELEASE_KEYSTORE_B64", secretValue: $b64, type: "shared"},
    {secretKey: "RELEASE_KEYSTORE_PASSWORD", secretValue: $pass, type: "shared"},
    {secretKey: "RELEASE_KEY_ALIAS", secretValue: $alias, type: "shared"},
    {secretKey: "RELEASE_KEY_PASSWORD", secretValue: $pass, type: "shared"},
    {secretKey: "UNRELATED_KEY", secretValue: "unrelated", type: "shared"}
  ], imports: []}' >"$fixtures/secrets-ok.json"

# variant <name> <jq filter over secrets-ok.json> [jq options...]
variant() {
  local name="$1" filter="$2"
  shift 2
  jq "$@" "$filter" "$fixtures/secrets-ok.json" >"$fixtures/secrets-$name.json"
}
# value_of <key>: a jq path to the value of one key.
value_of() {
  printf '(.secrets[] | select(.secretKey == "%s") | .secretValue)' "$1"
}
variant wrapped-b64 "$(value_of RELEASE_KEYSTORE_B64) = \$v" --rawfile v "$fixtures/b64-wrapped"
variant no-array '{secrets: "none"}'
variant missing-keypass 'del(.secrets[] | select(.secretKey == "RELEASE_KEY_PASSWORD"))'
variant empty-storepass "$(value_of RELEASE_KEYSTORE_PASSWORD) = \"\""
variant duplicate-alias \
  '.secrets += [{secretKey: "RELEASE_KEY_ALIAS", secretValue: "dremla-release"}]'
variant hidden-storepass \
  '(.secrets[] | select(.secretKey == "RELEASE_KEYSTORE_PASSWORD")) |=
    (.secretValueHidden = true | .secretValue = "<hidden-by-infisical>")'
variant multiline-storepass "$(value_of RELEASE_KEYSTORE_PASSWORD) |= . + \"\\nsecond line\""
variant wrong-alias "$(value_of RELEASE_KEY_ALIAS) = \"other-alias\""
variant different-keypass "$(value_of RELEASE_KEY_PASSWORD) = \"other-password-123\""
variant bad-b64 "$(value_of RELEASE_KEYSTORE_B64) = \"not*base64!\""
variant not-a-keystore "$(value_of RELEASE_KEYSTORE_B64) = \$v" \
  --rawfile v "$fixtures/b64-not-a-keystore"
variant bad-key "$(value_of RELEASE_KEYSTORE_B64) = \$v" --rawfile v "$fixtures/b64-bad-key"
variant wrong-storepass \
  "$(value_of RELEASE_KEYSTORE_PASSWORD) = \"wrong-password-123\" |
    $(value_of RELEASE_KEY_PASSWORD) = \"wrong-password-123\""

# --- Stub curl -----------------------------------------------------------------------------
# Accepts exactly the options resolve-signing.sh uses, so a change in the call shape fails here.
cat >"$stub_bin/curl" <<'STUB'
#!/usr/bin/env bash
# Stub curl for scripts/test-signing.sh. Scenario from STUB_* variables, canned files from
# $STUB_FIXTURES, argv and environment recorded in $STUB_LOG.
set -euo pipefail
fixtures="${STUB_FIXTURES:?}"
log="${STUB_LOG:?}"
{
  printf 'curl argv:'
  printf ' <%s>' "$@"
  printf '\n'
} >>"$log/curl-argv.log"
env >>"$log/curl-env.log"

if [[ -n "${STUB_CURL_EXIT:-}" ]]; then
  echo "curl: (7) Failed to connect (stub)" >&2
  printf '000'
  exit "$STUB_CURL_EXIT"
fi

method=GET output="" write_out="" data="" url=""
headers=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --silent | --show-error) shift ;;
    --proto | --connect-timeout | --max-time | --retry | --retry-delay) shift 2 ;;
    --request) method="$2" && shift 2 ;;
    --header) headers+=("$2") && shift 2 ;;
    --data-binary) data="$2" && shift 2 ;;
    --output) output="$2" && shift 2 ;;
    --write-out) write_out="$2" && shift 2 ;;
    -*) echo "stub curl: unexpected option $1" >&2 && exit 2 ;;
    *)
      [[ -z "$url" ]] || { echo "stub curl: more than one URL" >&2 && exit 2; }
      url="$1" && shift
      ;;
  esac
done
if [[ -z "$output" || "$write_out" != "%{http_code}" ]]; then
  echo "stub curl: expected --output <file> and --write-out %{http_code}" >&2
  exit 2
fi

status=404
body='{"statusCode":404,"message":"Not found"}'
body_file=""
secrets_url="$STUB_API/v3/secrets/raw?workspaceId=$STUB_PROJECT&environment=prod"
secrets_url+="&secretPath=%2Fdremla%2Frelease%2Fsigning"
secrets_url+="&expandSecretReferences=false&include_imports=false&recursive=false"
case "$url" in
  "$STUB_API/v1/auth/universal-auth/login")
    if [[ "$method" != POST || "$data" != "@-" ||
      " ${headers[*]-} " != *" Content-Type: application/json "* ]]; then
      status=400
      body='{"statusCode":400,"message":"Bad request"}'
    elif [[ -n "${STUB_LOGIN_STATUS:-}" ]]; then
      status="$STUB_LOGIN_STATUS"
      body='{"statusCode":'"$status"',"message":"Stub error"}'
    elif jq -e --rawfile id "$fixtures/client_id" --rawfile secret "$fixtures/client_secret" \
      '(keys == ["clientId", "clientSecret"]) and .clientId == $id and .clientSecret == $secret' \
      >/dev/null 2>&1; then
      status=200
      body_file="$fixtures/${STUB_LOGIN_BODY:-login-ok.json}"
    else
      status=401
      body='{"statusCode":401,"message":"Invalid credentials","error":"Unauthorized"}'
    fi
    ;;
  "$secrets_url")
    authorized=0
    expected="Authorization: Bearer $(<"$fixtures/token")"
    for header in ${headers[@]+"${headers[@]}"}; do
      if [[ "$header" == @* && -f "${header#@}" && "$(<"${header#@}")" == "$expected" ]]; then
        authorized=1
      fi
    done
    if [[ "$method" != GET ]]; then
      status=405
      body='{"statusCode":405,"message":"Method not allowed"}'
    elif [[ -n "${STUB_SECRETS_STATUS:-}" ]]; then
      status="$STUB_SECRETS_STATUS"
      body='{"statusCode":'"$status"',"message":"Stub error"}'
    elif [[ $authorized -eq 1 ]]; then
      status=200
      body_file="$fixtures/${STUB_SECRETS_BODY:-secrets-ok.json}"
    else
      status=401
      body='{"statusCode":401,"message":"Token missing or invalid","error":"Unauthorized"}'
    fi
    ;;
esac

if [[ -n "$body_file" ]]; then
  cat "$body_file" >"$output"
else
  printf '%s' "$body" >"$output"
fi
printf '%s' "$status"
STUB
chmod +x "$stub_bin/curl"

# --- Logging wrappers ----------------------------------------------------------------------
# Record argv (and, for keytool, the environment) in $STUB_LOG, then run the real tool. jq is
# first on PATH; keytool is $JAVA_HOME/bin/keytool of a fake JDK and, for the PATH case, also on
# PATH. The stub curl's own jq calls are recorded too; they carry file names only.
cat >"$stub_bin/jq" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
{
  printf 'jq argv:'
  printf ' <%s>' "$@"
  printf '\n'
} >>"${STUB_LOG:?}/jq-argv.log"
exec "${STUB_REAL_JQ:?}" "$@"
STUB
cat >"$fake_jdk/bin/keytool" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
{
  printf 'keytool argv:'
  printf ' <%s>' "$@"
  printf '\n'
} >>"${STUB_LOG:?}/keytool-argv.log"
env >>"$STUB_LOG/keytool-env.log"
exec "${STUB_REAL_KEYTOOL:?}" "$@"
STUB
chmod +x "$stub_bin/jq" "$fake_jdk/bin/keytool"
ln -s "$fake_jdk/bin/keytool" "$stub_bin/keytool"

# --- Cases ---------------------------------------------------------------------------------
INFISICAL_API_URL="$API"
INFISICAL_PROJECT_ID="$PROJECT"
INFISICAL_CLIENT_ID="$(<"$fixtures/client_id")"
INFISICAL_CLIENT_SECRET="$(<"$fixtures/client_secret")"
export INFISICAL_API_URL INFISICAL_PROJECT_ID INFISICAL_CLIENT_ID INFISICAL_CLIENT_SECRET
export STUB_FIXTURES="$fixtures" STUB_API="$API" STUB_PROJECT="$PROJECT"
export STUB_REAL_JQ="$real_jq" STUB_REAL_KEYTOOL="$keytool"

printf '%s\n' "$expected_digest" >"$fixtures/expected-digest"
outputs_log="$work/outputs.log"
argv_log="$work/argv.log"
env_log="$work/env.log"
: >"$outputs_log"
: >"$argv_log"
: >"$env_log"

cases=0
failures=0
problems=()

# verdict <name> <expectation>: prints ok or FAIL with the collected $problems.
verdict() {
  if [[ ${#problems[@]} -eq 0 ]]; then
    echo "ok    $1 (expected $2)"
  else
    echo "FAIL  $1 (expected $2)"
    printf '        %s\n' "${problems[@]}"
    failures=$((failures + 1))
  fi
}

# check_material <signing dir> <log dir>: the four files, mode 0600, exact content, nothing
# else; then the digest script, with the logging keytool, prints exactly the expected digest line.
check_material() {
  local dir="$1" log_dir="$2" file count rc=0
  for file in release.p12 storepass keypass alias; do
    [[ -f "$dir/$file" ]] || problems+=("$file was not written")
  done
  count="$(find "$dir" -mindepth 1 | wc -l | tr -d ' ')"
  [[ "$count" == 4 ]] || problems+=("the signing directory holds $count entries, expected 4")
  [[ -z "$(find "$dir" -mindepth 1 -type f ! -perm 0600)" ]] ||
    problems+=("a written file does not have mode 0600")
  cmp -s "$dir/release.p12" "$keystore" || problems+=("release.p12 differs from the keystore")
  cmp -s "$dir/storepass" "$fixtures/storepass" || problems+=("storepass differs")
  cmp -s "$dir/keypass" "$fixtures/storepass" || problems+=("keypass differs")
  cmp -s "$dir/alias" "$fixtures/alias" || problems+=("alias differs")

  # The digest step of a workflow has no client credentials in its environment.
  (
    unset INFISICAL_CLIENT_ID INFISICAL_CLIENT_SECRET
    DREMLA_SIGNING_DIR="$dir" STUB_LOG="$log_dir" JAVA_HOME="$fake_jdk" bash "$digester"
  ) >"$work/digest.out" 2>"$work/digest.err" || rc=$?
  cat "$work/digest.out" "$work/digest.err" >>"$outputs_log"
  [[ $rc -eq 0 ]] || problems+=("signing-cert-digest.sh exit $rc: $(<"$work/digest.err")")
  cmp -s "$work/digest.out" "$fixtures/expected-digest" ||
    problems+=("signing-cert-digest.sh printed '$(<"$work/digest.out")', expected $expected_digest")
}

# run_case <name> <expect> <message> [NAME=value...]
#   pass        exit 0, the PASS line, check_material (on DREMLA_SIGNING_DIR when it is passed)
#   fail        exit 1, <message> in the output, an empty signing directory
#   fail-early  as fail, and curl was never called
# NAME=value pairs are exported for this run only: stub scenario and overrides. keytool comes
# from the fake JDK unless JAVA_HOME= is passed, then from PATH (the same logging wrapper).
run_case() {
  local name="$1" expect="$2" message="$3"
  shift 3
  cases=$((cases + 1))
  local case_dir="$work/case-$cases"
  mkdir -p "$case_dir/log"
  mkdir -m 700 "$case_dir/sign"
  local sign_dir="$case_dir/sign" pair
  for pair in "$@"; do
    if [[ "$pair" == DREMLA_SIGNING_DIR=* ]]; then
      sign_dir="${pair#DREMLA_SIGNING_DIR=}"
    fi
  done
  local out rc=0
  out="$(
    export DREMLA_SIGNING_DIR="$case_dir/sign" STUB_LOG="$case_dir/log" JAVA_HOME="$fake_jdk"
    if [[ $# -gt 0 ]]; then
      # shellcheck disable=SC2163 # exports the NAME=value pairs themselves
      export "$@"
    fi
    PATH="$stub_bin:$PATH" bash "$resolver" 2>&1
  )" || rc=$?
  printf '%s\n' "$out" >>"$outputs_log"

  problems=()
  if [[ "$expect" == pass ]]; then
    [[ $rc -eq 0 ]] || problems+=("exit $rc, expected 0")
    [[ "$out" == *"resolve-signing: PASS"* ]] || problems+=("no PASS line")
    check_material "$sign_dir" "$case_dir/log"
  else
    [[ $rc -eq 1 ]] || problems+=("exit $rc, expected 1")
    [[ "$out" == *"$message"* ]] || problems+=("missing message: $message")
    [[ -z "$(find "$case_dir/sign" -mindepth 1 -print -quit)" ]] ||
      problems+=("the signing directory is not empty after the failure")
    if [[ "$expect" == fail-early && -e "$case_dir/log/curl-argv.log" ]]; then
      problems+=("curl was called")
    fi
  fi
  local log
  for log in curl-argv jq-argv keytool-argv; do
    if [[ -f "$case_dir/log/$log.log" ]]; then
      cat "$case_dir/log/$log.log" >>"$argv_log"
    fi
  done
  for log in curl-env keytool-env; do
    if [[ -f "$case_dir/log/$log.log" ]]; then
      cat "$case_dir/log/$log.log" >>"$env_log"
    fi
  done
  if [[ ${#problems[@]} -ne 0 ]]; then
    problems+=("output:" "$out")
  fi
  verdict "$name" "$expect"
}

# make_setgid_dir <path>: a new directory, mode 2700, in the caller's own group, so the caller may
# set the setgid bit whatever group the parent directory has.
make_setgid_dir() {
  mkdir -m 700 "$1"
  chgrp "$(id -g)" "$1"
  chmod g+s "$1"
  [[ -n "$(find "$1" -maxdepth 0 -perm -2000)" ]] || die "cannot set the setgid bit on a directory"
}
# Directories under a setgid parent inherit its setgid bit: mode 2700, and 2750 with group access.
make_setgid_dir "$work/setgid-parent"
mkdir "$work/setgid-parent/sign" "$work/setgid-parent/group-access"
chmod g+rx "$work/setgid-parent/group-access"
for name in sign group-access; do
  [[ -n "$(find "$work/setgid-parent/$name" -maxdepth 0 -perm -2000)" ]] ||
    die "a directory made under a setgid parent did not inherit the setgid bit"
done

# Success.
run_case "success" pass ""
run_case "line-wrapped base64" pass "" STUB_SECRETS_BODY=secrets-wrapped-b64.json
run_case "API URL with a trailing slash" pass "" INFISICAL_API_URL="$API/"
run_case "keytool from PATH (JAVA_HOME not set)" pass "" JAVA_HOME=
# Special bits are not permissions: the inherited setgid bit does not fail the mode check.
run_case "signing directory with an inherited setgid bit (2700)" pass "" \
  DREMLA_SIGNING_DIR="$work/setgid-parent/sign"

# Login failures.
run_case "login 401 (wrong client secret)" fail "Infisical login failed: HTTP 401" \
  INFISICAL_CLIENT_SECRET="wrong-$(random_hex)"
run_case "login 500" fail "Infisical login failed: HTTP 500" STUB_LOGIN_STATUS=500
run_case "login without an access token" fail "the response has no access token" \
  STUB_LOGIN_BODY=login-no-token.json
run_case "network failure" fail "Infisical login failed: curl exit 7" STUB_CURL_EXIT=7

# Secrets request failures.
run_case "secrets 403" fail "Infisical secrets request failed: HTTP 403" STUB_SECRETS_STATUS=403
run_case "secrets 404" fail "Infisical secrets request failed: HTTP 404" STUB_SECRETS_STATUS=404
run_case "response without a secrets array" fail "unexpected secrets response" \
  STUB_SECRETS_BODY=secrets-no-array.json

# Secret content failures.
run_case "missing key" fail "RELEASE_KEY_PASSWORD is missing" \
  STUB_SECRETS_BODY=secrets-missing-keypass.json
run_case "empty key" fail "RELEASE_KEYSTORE_PASSWORD is empty" \
  STUB_SECRETS_BODY=secrets-empty-storepass.json
run_case "duplicate key" fail "RELEASE_KEY_ALIAS appears 2 times" \
  STUB_SECRETS_BODY=secrets-duplicate-alias.json
run_case "hidden value" fail "RELEASE_KEYSTORE_PASSWORD value is hidden" \
  STUB_SECRETS_BODY=secrets-hidden-storepass.json
run_case "password with a line break" fail "RELEASE_KEYSTORE_PASSWORD contains a line break" \
  STUB_SECRETS_BODY=secrets-multiline-storepass.json
run_case "wrong alias" fail "RELEASE_KEY_ALIAS is not 'dremla-release'" \
  STUB_SECRETS_BODY=secrets-wrong-alias.json
run_case "key password differs" fail \
  "RELEASE_KEY_PASSWORD differs from RELEASE_KEYSTORE_PASSWORD" \
  STUB_SECRETS_BODY=secrets-different-keypass.json
run_case "invalid base64" fail "RELEASE_KEYSTORE_B64 is not valid base64" \
  STUB_SECRETS_BODY=secrets-bad-b64.json
run_case "not a keystore" fail "the keystore does not open" \
  STUB_SECRETS_BODY=secrets-not-a-keystore.json
run_case "wrong keystore password" fail "keystore password was incorrect" \
  STUB_SECRETS_BODY=secrets-wrong-storepass.json
run_case "private key does not decrypt" fail \
  "the private key of alias dremla-release does not decrypt with RELEASE_KEY_PASSWORD" \
  STUB_SECRETS_BODY=secrets-bad-key.json

# Configuration failures: nothing is requested.
run_case "http API URL" fail-early "must be an https:// URL" \
  INFISICAL_API_URL="http://secrets.example.invalid/api"
run_case "API URL without /api" fail-early "ending in /api" \
  INFISICAL_API_URL="https://secrets.example.invalid"
run_case "project ID with a query character" fail-early "INFISICAL_PROJECT_ID may contain only" \
  INFISICAL_PROJECT_ID="x&environment=dev"
run_case "client secret not set" fail-early "INFISICAL_CLIENT_SECRET is not set" \
  INFISICAL_CLIENT_SECRET=
mkdir -m 700 "$work/nonempty"
printf 'keep' >"$work/nonempty/leftover"
run_case "non-empty signing directory" fail-early "DREMLA_SIGNING_DIR is not empty" \
  DREMLA_SIGNING_DIR="$work/nonempty"
mkdir -m 755 "$work/mode-755"
run_case "signing directory with mode 0755" fail-early "must have mode 0700" \
  DREMLA_SIGNING_DIR="$work/mode-755"
mkdir -m 700 "$work/link-target"
ln -s "$work/link-target" "$work/link"
run_case "signing directory is a symbolic link" fail-early "must not be a symbolic link" \
  DREMLA_SIGNING_DIR="$work/link"
run_case "signing directory missing" fail-early "does not exist" \
  DREMLA_SIGNING_DIR="$work/missing"
run_case "relative signing directory" fail-early "must be an absolute path" \
  DREMLA_SIGNING_DIR="relative/sign"
run_case "setgid signing directory with group access (2750)" fail-early "must have mode 0700" \
  DREMLA_SIGNING_DIR="$work/setgid-parent/group-access"

# Refused directories keep their content and receive nothing.
cases=$((cases + 1))
problems=()
[[ "$(<"$work/nonempty/leftover")" == keep ]] ||
  problems+=("the leftover file in the non-empty directory changed")
[[ "$(find "$work/nonempty" -mindepth 1 | wc -l | tr -d ' ')" == 1 ]] ||
  problems+=("the non-empty directory received files")
[[ -z "$(find "$work/link-target" "$work/mode-755" "$work/setgid-parent/group-access" \
  -mindepth 1 -print -quit)" ]] ||
  problems+=("a refused directory received files")
verdict "refused directories are left as they were" "untouched"

# --- Signing directory scripts -------------------------------------------------------------
# run_dir_script <script> [NAME=value...]: runs the script with DREMLA_SIGNING_DIR, RUNNER_TEMP
# and GITHUB_ENV unset unless passed; sets $out (stdout and stderr together) and $rc.
run_dir_script() {
  local script="$1"
  shift
  rc=0
  out="$(env -u DREMLA_SIGNING_DIR -u RUNNER_TEMP -u GITHUB_ENV "$@" "$BASH" "$script" 2>&1)" ||
    rc=$?
  printf '%s\n' "$out" >>"$outputs_log"
}
# is_0700 <path>: a directory, not a symbolic link, mode exactly 0700 (no special bits).
is_0700() {
  [[ -d "$1" && ! -L "$1" && -n "$(find "$1" -maxdepth 0 -perm 0700)" ]]
}
# is_mktemp_child <path> <parent>: exactly <parent>/sign.<mktemp suffix>.
is_mktemp_child() {
  [[ "$1" == "$2"/sign.* && "${1#"$2"/sign.}" =~ ^[A-Za-z0-9]{6,}$ ]]
}
# gone <path>: nothing at the path, not even a dangling link.
gone() {
  [[ ! -e "$1" && ! -L "$1" ]]
}

# Tool stubs, each in its own directory put first on PATH by one case: a shred that logs its
# argv and runs the real one, a shred that fails, an rm that removes nothing, and a PATH with
# find, chmod and rm only (no shred).
for name in shred-log shred-fail rm-noop no-shred; do
  mkdir "$work/bin-$name"
done
cat >"$work/bin-shred-log/shred" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
{
  printf 'shred argv:'
  printf ' <%s>' "$@"
  printf '\n'
} >>"${STUB_LOG:?}/shred-argv.log"
exec "${STUB_REAL_SHRED:?}" "$@"
STUB
cat >"$work/bin-shred-fail/shred" <<'STUB'
#!/usr/bin/env bash
echo "shred: stub failure" >&2
exit 1
STUB
cat >"$work/bin-rm-noop/rm" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
chmod +x "$work/bin-shred-log/shred" "$work/bin-shred-fail/shred" "$work/bin-rm-noop/rm"
for tool in find chmod rm; do
  ln -s "$(command -v "$tool")" "$work/bin-no-shred/$tool"
done

# Files outside the signing directory that a symbolic link inside it points to.
mkdir "$work/victim-dir"
printf 'victim' >"$work/victim-dir/file"
printf 'victim' >"$work/victim-file"

# fill_signing_dir <dir>: what a job can leave behind: signing material, hidden intermediates,
# a hidden nested file, a read-only file, a directory without permissions and symbolic links
# that point outside the directory.
fill_signing_dir() {
  local dir="$1"
  printf 'p12' >"$dir/release.p12"
  printf 'pass' >"$dir/storepass"
  printf 'header' >"$dir/.auth-header"
  printf 'login' >"$dir/.login.json"
  mkdir -p "$dir/sub/.nested"
  printf 'nested' >"$dir/sub/.nested/.hidden"
  printf 'read-only' >"$dir/read-only"
  chmod 0400 "$dir/read-only"
  mkdir "$dir/locked"
  printf 'locked' >"$dir/locked/file"
  chmod 0000 "$dir/locked"
  ln -s "$work/victim-file" "$dir/link-to-file"
  ln -s "$work/victim-dir" "$dir/link-to-dir"
}
readonly FILLED_FILES=(release.p12 storepass .auth-header .login.json sub/.nested/.hidden read-only
  locked/file)
# victims_intact: the link targets outside the signing directory are unchanged.
victims_intact() {
  [[ "$(<"$work/victim-file")" == victim && "$(<"$work/victim-dir/file")" == victim ]] ||
    problems+=("a file outside the signing directory changed")
}

rt="$work/runner-temp"
mkdir -m 755 "$rt"

# create: a 0700 directory under RUNNER_TEMP, appended to GITHUB_ENV, path not printed.
cases=$((cases + 1))
problems=()
printf 'EARLIER=1\n' >"$work/github-env"
run_dir_script "$dir_create" RUNNER_TEMP="$rt" GITHUB_ENV="$work/github-env"
[[ $rc -eq 0 ]] || problems+=("exit $rc, expected 0")
[[ "$out" == "signing directory: created"* ]] || problems+=("no 'created' line")
[[ "$out" != *"$rt"* ]] || problems+=("the path was printed although GITHUB_ENV is set")
env_lines=()
while IFS= read -r line; do
  env_lines+=("$line")
done <"$work/github-env"
created=""
if [[ ${#env_lines[@]} -eq 2 && "${env_lines[0]}" == EARLIER=1 &&
  "${env_lines[1]}" == DREMLA_SIGNING_DIR=* ]]; then
  created="${env_lines[1]#DREMLA_SIGNING_DIR=}"
else
  problems+=("GITHUB_ENV does not hold the earlier line and then one DREMLA_SIGNING_DIR line")
fi
is_mktemp_child "$created" "$rt" || problems+=("the directory is not \$RUNNER_TEMP/sign.XXXXXX")
is_0700 "$created" || problems+=("the directory does not have mode 0700")
[[ -O "$created" ]] || problems+=("the directory is not owned by the caller")
[[ -d "$created" && -z "$(find "$created" -mindepth 1 -print -quit)" ]] ||
  problems+=("the directory is not empty")
verdict "create: 0700 directory under RUNNER_TEMP, recorded in GITHUB_ENV" "created"

# create without GITHUB_ENV prints the variable; a trailing slash on RUNNER_TEMP is dropped.
cases=$((cases + 1))
problems=()
run_dir_script "$dir_create" RUNNER_TEMP="$rt/"
[[ $rc -eq 0 ]] || problems+=("exit $rc, expected 0")
created="${out#DREMLA_SIGNING_DIR=}"
[[ "$out" == DREMLA_SIGNING_DIR=* && "$out" != *$'\n'* ]] ||
  problems+=("stdout is not one DREMLA_SIGNING_DIR=<path> line")
is_mktemp_child "$created" "$rt" || problems+=("the directory is not \$RUNNER_TEMP/sign.XXXXXX")
is_0700 "$created" || problems+=("the directory does not have mode 0700")
verdict "create without GITHUB_ENV: prints DREMLA_SIGNING_DIR=<path>" "printed"

# create under a setgid RUNNER_TEMP clears the inherited setgid bit; then the whole job flow:
# resolve into that directory, digest, remove.
cases=$((cases + 1))
problems=()
make_setgid_dir "$work/runner-temp-setgid"
run_dir_script "$dir_create" RUNNER_TEMP="$work/runner-temp-setgid"
[[ $rc -eq 0 ]] || problems+=("exit $rc, expected 0")
setgid_created="${out#DREMLA_SIGNING_DIR=}"
is_mktemp_child "$setgid_created" "$work/runner-temp-setgid" ||
  problems+=("the directory is not \$RUNNER_TEMP/sign.XXXXXX")
is_0700 "$setgid_created" || problems+=("the directory is not exactly 0700 (setgid kept?)")
verdict "create under a setgid RUNNER_TEMP: setgid cleared, mode 0700" "created"
run_case "resolve into a directory from signing-dir-create.sh (setgid RUNNER_TEMP)" pass "" \
  DREMLA_SIGNING_DIR="$setgid_created"
cases=$((cases + 1))
problems=()
run_dir_script "$dir_remove" DREMLA_SIGNING_DIR="$setgid_created" \
  RUNNER_TEMP="$work/runner-temp-setgid"
[[ $rc -eq 0 ]] || problems+=("exit $rc, expected 0")
[[ "$out" == *"signing directory: removed"* ]] || problems+=("no 'removed' line")
gone "$setgid_created" || problems+=("the signing directory is still present")
verdict "remove after a successful resolve" "removed"

# create fails closed and leaves no directory behind.
cases=$((cases + 1))
problems=()
mkdir "$work/runner-temp-fail" "$work/github-env-is-a-directory"
# expect_create_failure <message> [NAME=value...]
expect_create_failure() {
  local message="$1"
  shift
  run_dir_script "$dir_create" "$@"
  [[ $rc -eq 1 ]] || problems+=("$message: exit $rc, expected 1")
  [[ "$out" == *"$message"* ]] || problems+=("missing message: $message")
}
expect_create_failure "RUNNER_TEMP is not set"
expect_create_failure "RUNNER_TEMP must be an absolute path" RUNNER_TEMP="relative/temp"
expect_create_failure "RUNNER_TEMP does not exist" RUNNER_TEMP="$work/missing-temp"
expect_create_failure "RUNNER_TEMP contains a control character" \
  RUNNER_TEMP="$work/runner-temp-fail"$'\n'"DREMLA_SIGNING_DIR=/"
expect_create_failure "DREMLA_SIGNING_DIR is already set" RUNNER_TEMP="$work/runner-temp-fail" \
  DREMLA_SIGNING_DIR="$work/runner-temp-fail/sign.earlier"
expect_create_failure "cannot append to GITHUB_ENV" RUNNER_TEMP="$work/runner-temp-fail" \
  GITHUB_ENV="$work/github-env-is-a-directory"
[[ -z "$(find "$work/runner-temp-fail" -mindepth 1 -print -quit)" ]] ||
  problems+=("a failed create left a directory behind")
verdict "create fails closed" "fail, nothing left"

# remove: a filled directory goes, hidden, read-only and locked entries included; every regular
# file goes through shred; link targets outside are untouched.
cases=$((cases + 1))
problems=()
mkdir "$work/log-shred"
run_dir_script "$dir_create" RUNNER_TEMP="$rt"
filled="${out#DREMLA_SIGNING_DIR=}"
fill_signing_dir "$filled"
run_dir_script "$dir_remove" DREMLA_SIGNING_DIR="$filled" RUNNER_TEMP="$rt" \
  PATH="$work/bin-shred-log:$PATH" STUB_LOG="$work/log-shred" STUB_REAL_SHRED="$real_shred"
[[ $rc -eq 0 ]] || problems+=("exit $rc, expected 0")
[[ "$out" == "signing directory: removed" ]] || problems+=("output is not just the 'removed' line")
gone "$filled" || problems+=("the signing directory is still present")
shred_log="$work/log-shred/shred-argv.log"
if [[ -f "$shred_log" ]]; then
  shred_calls="$(<"$shred_log")"
  [[ "$shred_calls" == *"<--force> <--remove> <--iterations=1> <--> "* ]] ||
    problems+=("shred did not get --force --remove --iterations=1 --")
  for file in "${FILLED_FILES[@]}"; do
    [[ "$shred_calls" == *"<$filled/$file>"* ]] || problems+=("$file did not go through shred")
  done
  [[ "$shred_calls" != *"<$work/victim"* && "$shred_calls" != *"<$filled/link-to-"* ]] ||
    problems+=("shred got a symbolic link or a file outside the signing directory")
else
  problems+=("shred was not called")
fi
victims_intact
verdict "remove: filled directory with hidden, read-only and locked entries" "removed"

# remove: a failing shred is reported and the directory still goes.
cases=$((cases + 1))
problems=()
run_dir_script "$dir_create" RUNNER_TEMP="$rt"
filled="${out#DREMLA_SIGNING_DIR=}"
fill_signing_dir "$filled"
run_dir_script "$dir_remove" DREMLA_SIGNING_DIR="$filled" RUNNER_TEMP="$rt" \
  PATH="$work/bin-shred-fail:$PATH"
[[ $rc -eq 0 ]] || problems+=("exit $rc, expected 0")
[[ "$out" == *"shred failed on some files"* ]] || problems+=("the shred failure is not reported")
[[ "$out" == *"signing directory: removed"* ]] || problems+=("no 'removed' line")
gone "$filled" || problems+=("the signing directory is still present")
victims_intact
verdict "remove: failing shred, directory still removed" "removed"

# remove without shred on PATH.
cases=$((cases + 1))
problems=()
run_dir_script "$dir_create" RUNNER_TEMP="$rt"
filled="${out#DREMLA_SIGNING_DIR=}"
fill_signing_dir "$filled"
run_dir_script "$dir_remove" DREMLA_SIGNING_DIR="$filled" RUNNER_TEMP="$rt" \
  PATH="$work/bin-no-shred"
[[ $rc -eq 0 ]] || problems+=("exit $rc, expected 0")
[[ "$out" == *"shred is not on PATH"* ]] || problems+=("the missing shred is not reported")
[[ "$out" == *"signing directory: removed"* ]] || problems+=("no 'removed' line")
gone "$filled" || problems+=("the signing directory is still present")
victims_intact
verdict "remove: no shred on PATH, directory still removed" "removed"

# remove refuses, and leaves alone, anything but a directory of its own at
# $RUNNER_TEMP/sign.<suffix>.
cases=$((cases + 1))
problems=()
mkdir -p "$work/elsewhere/sign.abc123" "$rt/other.abc123"
printf 'keep' >"$work/elsewhere/sign.abc123/keep"
printf 'keep' >"$rt/other.abc123/keep"
ln -s "$work/elsewhere/sign.abc123" "$rt/sign.link01"
printf 'keep' >"$rt/sign.file01"
# expect_refusal <message> [NAME=value...]
expect_refusal() {
  local message="$1"
  shift
  run_dir_script "$dir_remove" "$@"
  [[ $rc -eq 1 ]] || problems+=("$message: exit $rc, expected 1")
  [[ "$out" == *"$message"* ]] || problems+=("missing message: $message")
}
expect_refusal "it is not \$RUNNER_TEMP/sign." RUNNER_TEMP="$rt" \
  DREMLA_SIGNING_DIR="$work/elsewhere/sign.abc123"
expect_refusal "it is not \$RUNNER_TEMP/sign." RUNNER_TEMP="$rt" \
  DREMLA_SIGNING_DIR="$rt/sign.abc123/../../elsewhere/sign.abc123"
expect_refusal "it is not \$RUNNER_TEMP/sign." RUNNER_TEMP="$rt" \
  DREMLA_SIGNING_DIR="$rt/other.abc123"
expect_refusal "it is not \$RUNNER_TEMP/sign." RUNNER_TEMP="$rt" DREMLA_SIGNING_DIR="$rt/sign."
expect_refusal "it is a symbolic link" RUNNER_TEMP="$rt" DREMLA_SIGNING_DIR="$rt/sign.link01"
expect_refusal "it is not a directory" RUNNER_TEMP="$rt" DREMLA_SIGNING_DIR="$rt/sign.file01"
expect_refusal "RUNNER_TEMP is not an absolute path" DREMLA_SIGNING_DIR="$rt/sign.abc123"
expect_refusal "RUNNER_TEMP is not an absolute path" RUNNER_TEMP="relative/temp" \
  DREMLA_SIGNING_DIR="relative/temp/sign.abc123"
[[ "$(<"$work/elsewhere/sign.abc123/keep")" == keep && "$(<"$rt/other.abc123/keep")" == keep &&
  -L "$rt/sign.link01" && "$(<"$rt/sign.file01")" == keep ]] ||
  problems+=("a refused path was changed")
verdict "remove refuses foreign paths, symbolic links and files" "fail, untouched"

# remove with nothing to do exits 0.
cases=$((cases + 1))
problems=()
run_dir_script "$dir_remove"
[[ $rc -eq 0 && "$out" == "signing directory: never created" ]] ||
  problems+=("unset: exit $rc, output '$out'")
run_dir_script "$dir_remove" DREMLA_SIGNING_DIR= RUNNER_TEMP="$rt"
[[ $rc -eq 0 && "$out" == "signing directory: never created" ]] ||
  problems+=("empty: exit $rc, output '$out'")
run_dir_script "$dir_remove" DREMLA_SIGNING_DIR="$rt/sign.gone99" RUNNER_TEMP="$rt"
[[ $rc -eq 0 && "$out" == "signing directory: already gone" ]] ||
  problems+=("already gone: exit $rc, output '$out'")
verdict "remove: never created or already gone" "exit 0"

# remove fails when the directory survives rm.
cases=$((cases + 1))
problems=()
run_dir_script "$dir_create" RUNNER_TEMP="$rt"
filled="${out#DREMLA_SIGNING_DIR=}"
fill_signing_dir "$filled"
run_dir_script "$dir_remove" DREMLA_SIGNING_DIR="$filled" RUNNER_TEMP="$rt" \
  PATH="$work/bin-rm-noop:$PATH"
[[ $rc -eq 1 ]] || problems+=("exit $rc, expected 1")
[[ "$out" == *"the signing directory is still present"* ]] || problems+=("missing message")
[[ "$out" != *"signing directory: removed"* ]] || problems+=("it printed 'removed'")
[[ -d "$filled" ]] || problems+=("the no-op rm stub did not keep the directory")
rm -rf "$filled"
verdict "remove: directory that survives rm" "fail"

# Arguments are refused.
cases=$((cases + 1))
problems=()
rc=0
bash "$resolver" extra >/dev/null 2>&1 || rc=$?
[[ $rc -eq 2 ]] || problems+=("resolve-signing.sh with an argument: exit $rc, expected 2")
rc=0
bash "$digester" extra >/dev/null 2>&1 || rc=$?
[[ $rc -eq 2 ]] || problems+=("signing-cert-digest.sh with an argument: exit $rc, expected 2")
for script in "$dir_create" "$dir_remove"; do
  rc=0
  env -u DREMLA_SIGNING_DIR RUNNER_TEMP="$work/runner-temp-fail" bash "$script" extra \
    >/dev/null 2>&1 || rc=$?
  [[ $rc -eq 2 ]] || problems+=("$(basename "$script") with an argument: exit $rc, expected 2")
done
verdict "arguments are refused" "exit 2"

# The digest script fails without signing material and prints nothing on stdout.
cases=$((cases + 1))
problems=()
mkdir -m 700 "$work/empty-sign"
rc=0
DREMLA_SIGNING_DIR="$work/empty-sign" bash "$digester" >"$work/digest.out" 2>"$work/digest.err" ||
  rc=$?
[[ $rc -eq 1 ]] || problems+=("exit $rc, expected 1")
[[ ! -s "$work/digest.out" ]] || problems+=("stdout is not empty")
[[ "$(<"$work/digest.err")" == *"has no release.p12"* ]] || problems+=("missing message")
verdict "digest without signing material" "fail"

# No secret value in the recorded argv of curl, jq and keytool, the recorded environment of curl
# and keytool, or any output. The comparison runs in this shell, so the values never reach
# another process's argv. The wrappers must have recorded the calls the scripts make.
cases=$((cases + 1))
problems=()
# recorded <glob>: some line of the argv log matches the whole glob.
recorded() {
  local line
  while IFS= read -r line; do
    # shellcheck disable=SC2053 # $1 is a glob on purpose
    [[ "$line" == $1 ]] && return 0
  done <"$argv_log"
  return 1
}
for call in "curl argv: *<$API/v1/auth/universal-auth/login>" \
  "curl argv: *<$API/v3/secrets/raw[?]*>" "jq argv: <-cn> *" "keytool argv: *<-list> *" \
  "keytool argv: *<-certreq> *" "keytool argv: *<-exportcert> *"; do
  recorded "$call" || problems+=("no recorded call matches '$call'")
done
b64="$(<"$fixtures/b64")"
secret_labels=("client ID" "client secret" "access token" "keystore password" "keystore base64"
  "keystore base64 (first 48 characters)" "keystore base64 (48 characters from the middle)")
secret_values=("$(<"$fixtures/client_id")" "$(<"$fixtures/client_secret")"
  "$(<"$fixtures/token")" "$(<"$fixtures/storepass")" "$b64" "${b64:0:48}"
  "${b64:$((${#b64} / 2)):48}")
[[ -s "$argv_log" && -s "$env_log" && -s "$outputs_log" ]] || problems+=("a log is empty")
for log in "$argv_log" "$env_log" "$outputs_log"; do
  content="$(<"$log")"
  for i in "${!secret_values[@]}"; do
    if [[ "$content" == *"${secret_values[$i]}"* ]]; then
      problems+=("${secret_labels[$i]} appears in $(basename "$log")")
    fi
  done
done
verdict "no secret in argv, curl or keytool environment, or output" "none"

if [[ $failures -ne 0 ]]; then
  echo "test-signing: FAIL ($failures of $cases case(s))" >&2
  exit 1
fi
echo "test-signing: PASS ($cases cases)"
