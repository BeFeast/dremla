#!/usr/bin/env bash
# Outcome verification for Dremla. No arguments. Environment:
#   DREMLA_OUTCOME_SCOPE       repo: host checks only (CI). full (default): host checks plus the
#                              read-only device-identity stage (fails without delivery config).
#   DREMLA_GRADLE_MAX_WORKERS  --max-workers for every Gradle call (default 2).
#
# Host checks: clean build of debug and unsigned release APKs, unit tests with golden scenes,
# lint, APK inspection, the hygiene check, the forbidden-API check and the brand-kit port check,
# each check with its self-test, and the self-test of the release signing scripts (throwaway
# keystore, stub curl, no network; needs JAVA_HOME or keytool on PATH, jq and shred). The
# offline collection-service and fake-device delivery self-tests run in both scopes. After the
# Gradle stage it prints the unit-test counts per class. Every stage runs even when an earlier one
# failed; the summary lists all of them and the exit status is non-zero if any check failed. The
# script does not lower its own priority; callers wrap it (CI uses nice -n 19).
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
# Explicit version hook: no shell splitting, eval or arbitrary Gradle arguments.
if [[ -n "${DREMLA_VERSION_CODE:-}${DREMLA_VERSION_NAME:-}" ]]; then
  DREMLA_SOURCE_SHA=0000000000000000000000000000000000000000 \
    RELEASE_SIGNING_CERT_SHA256=0000000000000000000000000000000000000000000000000000000000000000 \
    python3 -I scripts/release-tools.py inputs
  gradle+=("-Pdremla.versionCode=$DREMLA_VERSION_CODE" "-Pdremla.versionName=$DREMLA_VERSION_NAME")
fi

readonly RELEASE_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
readonly DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
readonly UNIT_TEST_RESULTS="app/build/test-results/testDebugUnitTest"

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

# Per-class counts from the JUnit XML that testDebugUnitTest writes, so the log shows what ran.
# Only the suite name and its counts are printed; the XML also carries the host name and the test
# output. A report, not a stage: a failing test already fails the Gradle stage.
unit_test_summary() {
  echo
  echo "==== unit tests ($UNIT_TEST_RESULTS)"
  local results=("$UNIT_TEST_RESULTS"/TEST-*.xml)
  if [[ ! -f "${results[0]}" ]]; then
    echo "no JUnit XML results (the unit tests did not run)"
    return 0
  fi
  # POSIX awk: the first <testsuite ...> start tag of each file holds the counts as attributes.
  LC_ALL=C awk '
    function attr(tag, key,   i, rest) {
      i = index(tag, " " key "=\"")
      if (i == 0) return ""
      rest = substr(tag, i + length(key) + 3)
      return substr(rest, 1, index(rest, "\"") - 1)
    }
    BEGIN { n = tt = tf = te = tk = ts = 0; fmt = "%-56s %6s %9s %7s %8s %9s\n"; printf fmt, "class", "tests", "failures", "errors", "skipped", "time" }
    !(FILENAME in done) && /<testsuite / {
      done[FILENAME] = 1
      t = attr($0, "tests") + 0; f = attr($0, "failures") + 0; e = attr($0, "errors") + 0
      k = attr($0, "skipped") + 0; s = attr($0, "time") + 0
      printf fmt, attr($0, "name"), t, f, e, k, sprintf("%.3fs", s)
      n++; tt += t; tf += f; te += e; tk += k; ts += s
    }
    END {
      for (i = 1; i < ARGC; i++) if (!(ARGV[i] in done)) { printf "%s: no <testsuite> element\n", ARGV[i]; bad++ }
      printf fmt, "total (classes: " n ")", tt, tf, te, tk, sprintf("%.3fs", ts)
      if (bad) exit 1
    }
  ' "${results[@]}" || echo "unit tests: could not read every result file"
}

echo "verify-outcome: scope=$scope gradle-max-workers=$workers"

# One Gradle call, so the unit tests run once: verifyRoborazziDebug depends on testDebugUnitTest
# and switches it to verify mode, where a golden mismatch fails its scene test. --continue: a
# failing task (a unit test, a golden scene, lintVitalRelease) does not stop the others, so
# lintDebug still writes its report for the failure-evidence artifact; any failed task still
# makes the call exit non-zero.
run_stage "gradle-build-test-golden-lint" \
  "${gradle[@]}" --continue clean assembleDebug assembleRelease verifyRoborazziDebug lintDebug writeRuntimeDependencies
unit_test_summary
run_stage "inspect-release-apk" \
  env DREMLA_APK="$RELEASE_APK" DREMLA_APK_REQUIRE_SIGNED=0 scripts/inspect-apk.sh
run_stage "inspect-debug-apk" \
  env DREMLA_APK="$DEBUG_APK" DREMLA_APK_REQUIRE_SIGNED=1 scripts/inspect-apk.sh
run_stage "hygiene-self-test" scripts/test-hygiene.sh
run_stage "public-hygiene" scripts/check-public-hygiene.sh
run_stage "forbidden-apis-self-test" scripts/test-forbidden-apis.sh
run_stage "forbidden-apis" scripts/check-forbidden-apis.sh
run_stage "kit-port-self-test" scripts/test-kit-port.sh
run_stage "kit-port" scripts/check-kit-port.sh
run_stage "privacy-self-test" scripts/test-privacy.sh
run_stage "privacy" scripts/check-privacy.sh
run_stage "signing-self-test" scripts/test-signing.sh
run_stage "collection-service-self-test" scripts/test-collection-service.sh
run_stage "device-self-test" scripts/test-device.sh
run_stage "release-lock-self-test" scripts/test-release-lock.sh
run_stage "release-lock" scripts/check-release-lock.sh

if [[ "$scope" == "full" ]]; then
  run_stage "device-identity" python3 -I scripts/device-tools.py verify
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
