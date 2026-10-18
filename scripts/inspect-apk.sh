#!/usr/bin/env bash
# Inspects a built APK: asserts applicationId, minSdk, targetSdk and the absence of native
# libraries, then prints versionCode, versionName and the signing-certificate SHA-256.
#
# No arguments. Environment:
#   DREMLA_APK                  APK to inspect; relative paths are taken from the repository root.
#                               Default: app/build/outputs/apk/release/app-release-unsigned.apk
#   DREMLA_APK_REQUIRE_SIGNED=1 fail when the APK carries no signature (release delivery).
#   ANDROID_HOME                SDK root with build-tools 34.0.0 (aapt2, apksigner).
set -euo pipefail

if [[ $# -ne 0 ]]; then
  echo "usage: scripts/inspect-apk.sh (no arguments; set DREMLA_APK to choose the APK)" >&2
  exit 2
fi

readonly EXPECTED_PACKAGE="com.befeast.sleepmusic"
readonly EXPECTED_MIN_SDK="28"
readonly EXPECTED_TARGET_SDK="34"
readonly BUILD_TOOLS_VERSION="34.0.0"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk="${DREMLA_APK:-app/build/outputs/apk/release/app-release-unsigned.apk}"
if [[ "$apk" != /* ]]; then
  apk="$repo_root/$apk"
fi

die() {
  echo "inspect-apk: FAIL: $*" >&2
  exit 1
}

if [[ -z "${ANDROID_HOME:-}" ]]; then
  die "ANDROID_HOME is not set (expected an SDK with build-tools $BUILD_TOOLS_VERSION)"
fi
build_tools="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
aapt2="$build_tools/aapt2"
apksigner="$build_tools/apksigner"
for tool in "$aapt2" "$apksigner"; do
  [[ -x "$tool" ]] || die "missing SDK tool: $tool (build-tools $BUILD_TOOLS_VERSION is required)"
done
# apksigner is a wrapper that runs `java` from PATH; prefer the configured JDK.
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v java >/dev/null 2>&1 ||
  die "java not found (apksigner needs it): set JAVA_HOME to a JDK 17 or put java on PATH"

[[ -f "$apk" ]] || die "APK not found: $apk (build it first, or set DREMLA_APK)"

echo "apk: ${apk#"$repo_root"/}"

failures=()

# --- Identity: aapt2 dump badging -----------------------------------------------------------
badging="$("$aapt2" dump badging "$apk")" || die "aapt2 dump badging failed for $apk"

package_name=""
version_code=""
version_name=""
min_sdk=""
target_sdk=""
re_name="^package: name='([^']*)'"
re_code=" versionCode='([^']*)'"
re_vname=" versionName='([^']*)'"
re_min="^sdkVersion:'([^']*)'"
re_target="^targetSdkVersion:'([^']*)'"
while IFS= read -r line; do
  if [[ "$line" =~ $re_name ]]; then
    package_name="${BASH_REMATCH[1]}"
    [[ "$line" =~ $re_code ]] && version_code="${BASH_REMATCH[1]}"
    [[ "$line" =~ $re_vname ]] && version_name="${BASH_REMATCH[1]}"
  elif [[ "$line" =~ $re_min ]]; then
    min_sdk="${BASH_REMATCH[1]}"
  elif [[ "$line" =~ $re_target ]]; then
    target_sdk="${BASH_REMATCH[1]}"
  fi
done <<<"$badging"

check() {
  local label="$1" actual="$2" expected="$3"
  if [[ "$actual" == "$expected" ]]; then
    echo "$label: $actual (ok)"
  else
    echo "$label: '${actual}' (expected '$expected')"
    failures+=("$label is '${actual}', expected '$expected'")
  fi
}

check "applicationId" "$package_name" "$EXPECTED_PACKAGE"
check "minSdk" "$min_sdk" "$EXPECTED_MIN_SDK"
check "targetSdk" "$target_sdk" "$EXPECTED_TARGET_SDK"
echo "versionCode: ${version_code:-<missing>}"
echo "versionName: ${version_name:-<missing>}"
[[ -n "$version_code" ]] || failures+=("versionCode is missing")
[[ -n "$version_name" ]] || failures+=("versionName is missing")

# --- Packaging: no native libraries ---------------------------------------------------------
if command -v unzip >/dev/null 2>&1; then
  entries="$(unzip -Z1 "$apk")" || die "cannot list APK entries with unzip"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jar" ]]; then
  entries="$("$JAVA_HOME/bin/jar" tf "$apk")" || die "cannot list APK entries with jar"
else
  die "neither unzip nor \$JAVA_HOME/bin/jar is available to list APK entries"
fi
native_libs=()
while IFS= read -r entry; do
  [[ "$entry" == lib/* ]] && native_libs+=("$entry")
done <<<"$entries"
if [[ ${#native_libs[@]} -eq 0 ]]; then
  echo "native libraries: none (ok)"
else
  echo "native libraries: ${#native_libs[@]} lib/** entries"
  printf '  %s\n' "${native_libs[@]}"
  failures+=("APK contains ${#native_libs[@]} lib/** entries")
fi

# --- Signing certificate --------------------------------------------------------------------
re_digest="^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)"
if certs="$("$apksigner" verify --print-certs "$apk" 2>&1)"; then
  digest=""
  while IFS= read -r line; do
    if [[ "$line" =~ $re_digest ]]; then
      digest="${BASH_REMATCH[1]}"
    fi
  done <<<"$certs"
  if [[ -n "$digest" ]]; then
    echo "signing certificate SHA-256: $digest"
  else
    echo "$certs" >&2
    failures+=("apksigner verified the APK but printed no signer certificate digest")
  fi
elif [[ "$certs" == *"Missing META-INF/MANIFEST.MF"* && "$certs" != *"Signature Scheme"* ]]; then
  # No APK Signing Block and no JAR signature: apksigner falls back to v1 and finds no manifest.
  echo "signing certificate: none (unsigned APK)"
  if [[ "${DREMLA_APK_REQUIRE_SIGNED:-0}" == "1" ]]; then
    failures+=("APK is unsigned but DREMLA_APK_REQUIRE_SIGNED=1")
  fi
else
  echo "$certs" >&2
  failures+=("apksigner verify failed: the APK signature does not verify")
fi

if [[ ${#failures[@]} -ne 0 ]]; then
  echo "inspect-apk: FAIL (${#failures[@]} problem(s)):" >&2
  printf '  - %s\n' "${failures[@]}" >&2
  exit 1
fi
echo "inspect-apk: PASS"
