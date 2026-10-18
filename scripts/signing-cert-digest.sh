#!/usr/bin/env bash
# Prints the SHA-256 of the DER-encoded signing certificate in DREMLA_SIGNING_DIR as one line of
# 64 lowercase hex digits and nothing else on stdout. This is the value apksigner prints as
# "certificate SHA-256 digest" and the value of RELEASE_SIGNING_CERT_SHA256. The certificate is
# public; no secret is printed.
#
# No arguments. Environment:
#   DREMLA_SIGNING_DIR  written by scripts/resolve-signing.sh (release.p12, storepass, alias)
#   JAVA_HOME           keytool comes from $JAVA_HOME/bin when set, else from PATH
set -euo pipefail
set +x
umask 077

if [[ $# -ne 0 ]]; then
  echo "usage: scripts/signing-cert-digest.sh (no arguments; set DREMLA_SIGNING_DIR)" >&2
  exit 2
fi

die() {
  echo "signing-cert-digest: FAIL: $*" >&2
  exit 1
}

dir="${DREMLA_SIGNING_DIR:-}"
[[ -n "$dir" ]] || die "DREMLA_SIGNING_DIR is not set"
[[ -d "$dir" ]] || die "DREMLA_SIGNING_DIR does not exist or is not a directory"
for file in release.p12 storepass alias; do
  [[ -s "$dir/$file" ]] || die "DREMLA_SIGNING_DIR has no $file (run scripts/resolve-signing.sh)"
done
key_alias="$(<"$dir/alias")"
[[ "$key_alias" =~ ^[A-Za-z0-9._-]+$ ]] || die "the alias file is not a plain key alias"

command -v sha256sum >/dev/null 2>&1 || die "sha256sum is not on PATH"
if [[ -n "${JAVA_HOME:-}" ]]; then
  keytool="$JAVA_HOME/bin/keytool"
  [[ -x "$keytool" ]] || die "JAVA_HOME is set but has no bin/keytool"
else
  keytool="$(command -v keytool)" ||
    die "keytool not found: set JAVA_HOME to a JDK 17 or put keytool on PATH"
fi
# English messages under any locale, so the "keytool error:" line is recognised.
keytool_cmd=("$keytool" -J-Duser.language=en)

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cert="$work/cert.der"

# keytool prints its errors on stdout; only its "keytool error:" line is shown.
rc=0
output="$("${keytool_cmd[@]}" -exportcert -keystore "$dir/release.p12" -storetype PKCS12 \
  -storepass:file "$dir/storepass" -alias "$key_alias" -file "$cert" </dev/null 2>&1)" || rc=$?
if [[ $rc -ne 0 || ! -s "$cert" ]]; then
  reason="$(grep -m 1 '^keytool error:' <<<"$output" || true)"
  die "cannot export the certificate of alias $key_alias: ${reason:-keytool exit $rc}"
fi

digest="$(sha256sum "$cert")"
digest="${digest%% *}"
[[ "$digest" =~ ^[0-9a-f]{64}$ ]] || die "sha256sum printed no digest"
echo "$digest"
