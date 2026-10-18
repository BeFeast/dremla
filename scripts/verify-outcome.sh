#!/usr/bin/env bash
# Outcome verification for Dremla. No arguments. Environment:
#   DREMLA_OUTCOME_SCOPE       repo: host checks only (CI). full (default): host checks plus the
#                              device-identity stage.
#   DREMLA_GRADLE_MAX_WORKERS  --max-workers for every Gradle call (default 2).
#
# Host checks: clean build of debug and unsigned release APKs, unit tests with golden scenes,
# lint, APK inspection, the hygiene check and the forbidden-API check, each check with its
# self-test. Every stage runs even when an earlier one failed; the summary lists all of them and
# the exit status is non-zero if any check failed. The script does not lower its own priority;
# callers wrap it (CI uses nice -n 19).
set -euo pipefail

if [[ $# -ne 0 ]]; then
  echo "usage: scripts/verify-outcome.sh (no arguments; see DREMLA_OUTCOME_SCOPE)" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

scope="${DREMLA_OUTCOME_SCOPE:-full}"
case "$scope" in
  repo | full) ;;
  *)
    echo "verify-outcome: unknown DREMLA_OUTCOME_SCOPE '$scope' (expected 'repo' or 'full')" >&2
    exit 2
    ;;
esac

workers="${DREMLA_GRADLE_MAX_WORKERS:-2}"
if [[ ! "$workers" =~ ^[1-9][0-9]*$ ]]; then
  echo "verify-outcome: DREMLA_GRADLE_MAX_WORKERS must be a positive integer, got '$workers'" >&2
  exit 2
fi
gradle=(./gradlew --no-daemon "--max-workers=$workers")

readonly RELEASE_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
readonly DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"

stage_names=()
stage_results=()
stage_seconds=()
failed=0

# run_stage <name> <command...>
run_stage() {
  local name="$1"
  shift
  echo
  echo "==== stage: $name"
  echo "\$ $*"
  local start=$SECONDS rc=0
  "$@" || rc=$?
  local elapsed=$((SECONDS - start)) result="PASS"
  if [[ $rc -ne 0 ]]; then
    result="FAIL (exit $rc)"
    failed=1
  fi
  stage_names+=("$name")
  stage_results+=("$result")
  stage_seconds+=("$elapsed")
  echo "==== stage: $name -> $result in ${elapsed}s"
}

# Device identity (installed package, version, signing certificate) is wired up by the install
# issue. Until then the stage reports itself as not configured and does not fail.
device_identity_stage() {
  echo
  echo "==== stage: device-identity"
  echo "device identity: not configured (enabled by the install issue)"
  stage_names+=("device-identity")
  stage_results+=("NOT CONFIGURED")
  stage_seconds+=("0")
}

echo "verify-outcome: scope=$scope gradle-max-workers=$workers"

# One Gradle call, so the unit tests run once: verifyRoborazziDebug depends on testDebugUnitTest
# and switches it to verify mode, where a golden mismatch fails its scene test. --continue: a
# failing task (a unit test, a golden scene, lintVitalRelease) does not stop the others, so
# lintDebug still writes its report for the failure-evidence artifact; any failed task still
# makes the call exit non-zero.
run_stage "gradle-build-test-golden-lint" \
  "${gradle[@]}" --continue clean assembleDebug assembleRelease verifyRoborazziDebug lintDebug
run_stage "inspect-release-apk" \
  env DREMLA_APK="$RELEASE_APK" DREMLA_APK_REQUIRE_SIGNED=0 scripts/inspect-apk.sh
run_stage "inspect-debug-apk" \
  env DREMLA_APK="$DEBUG_APK" DREMLA_APK_REQUIRE_SIGNED=1 scripts/inspect-apk.sh
run_stage "hygiene-self-test" scripts/test-hygiene.sh
run_stage "public-hygiene" scripts/check-public-hygiene.sh
run_stage "forbidden-apis-self-test" scripts/test-forbidden-apis.sh
run_stage "forbidden-apis" scripts/check-forbidden-apis.sh

if [[ "$scope" == "full" ]]; then
  device_identity_stage
fi

echo
echo "==== summary (scope: $scope, gradle max workers: $workers)"
printf '%-30s %-16s %8s\n' "stage" "result" "time"
for i in "${!stage_names[@]}"; do
  printf '%-30s %-16s %7ss\n' "${stage_names[$i]}" "${stage_results[$i]}" "${stage_seconds[$i]}"
done

if [[ $failed -ne 0 ]]; then
  echo "verify-outcome: FAIL"
  exit 1
fi
echo "verify-outcome: PASS"
