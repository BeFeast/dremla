#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 0 ]] || { echo 'usage: scripts/test-privacy.sh (no arguments)' >&2; exit 2; }
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/scripts" "$work/docs" "$work/app/build/reports/privacy" "$work/app/src/main"
cp "$root/scripts/check-privacy.sh" "$work/scripts/"
printf '%s\n' 'androidx.core:core' > "$work/docs/runtime-dependencies.txt"
cp "$work/docs/runtime-dependencies.txt" "$work/app/build/reports/privacy/runtime.txt"
check() {
  local expected="$1" rc=0
  bash "$work/scripts/check-privacy.sh" > "$work/result" 2>&1 || rc=$?
  [[ "$rc" == "$expected" ]] || { cat "$work/result"; exit 1; }
}
check 0
printf '%s\n' 'val url = "https://html.me.uk/t/dremla/collection/"' > "$work/app/src/main/Test.kt"
check 0
for endpoint in 'https://example.org/data' 'http://html.me.uk/file' 'https://html.me.uk.example.org/file' 'wss://example.org/events'; do
  printf 'val url = "%s"\n' "$endpoint" > "$work/app/src/main/Test.kt"
  check 1
done
rm "$work/app/src/main/Test.kt"
for variant in debug release demo; do
  mkdir -p "$work/app/src/$variant/java"
  printf '%s\n' 'val url = "https://example.org/data"' > "$work/app/src/$variant/java/Test.kt"
  check 1
  rm "$work/app/src/$variant/java/Test.kt"
done
for variant in test androidTest testFixtures testDebug androidTestDemo; do
  mkdir -p "$work/app/src/$variant"
  printf '%s\n' 'val url = "https://example.org/data"' > "$work/app/src/$variant/Test.kt"
done
check 0
printf '%s\n' 'com.example:unknown' >> "$work/app/build/reports/privacy/runtime.txt"
check 1
printf '%s\n' 'com.example:telemetry' > "$work/docs/runtime-dependencies.txt"
cp "$work/docs/runtime-dependencies.txt" "$work/app/build/reports/privacy/runtime.txt"
check 1
rm "$work/app/build/reports/privacy/runtime.txt"
check 1
# Execute the production dependency-report task against isolated local dependencies.
# No Android plugin or SDK is needed; the wrapper and pinned Gradle still run on the builder.
mkdir -p "$work/dependencies/libs"
printf '%s\n' 'rootProject.name = "privacy-fixture"' > "$work/dependencies/settings.gradle.kts"
python3 - "$work/dependencies/libs" <<'PYZIP'
import sys, zipfile
from pathlib import Path
for name in ('fixture.jar', 'fixture.aar'):
    with zipfile.ZipFile(Path(sys.argv[1]) / name, 'w'):
        pass
PYZIP
for dependency in 'files("libs/fixture.jar")' 'files("libs/fixture.aar")' 'fileTree("libs") { include("*.jar") }'; do
  {
    printf '%s\n' 'configurations.create("debugRuntimeClasspath")' 'configurations.create("releaseRuntimeClasspath")'
    printf 'dependencies { add("debugRuntimeClasspath", %s) }\n' "$dependency"
    sed -n '/^tasks.register("writeRuntimeDependencies")/,$p' "$root/app/build.gradle.kts"
  } > "$work/dependencies/build.gradle.kts"
  rc=0
  "$root/gradlew" --no-daemon "--max-workers=${DREMLA_GRADLE_MAX_WORKERS:-2}" --offline -p "$work/dependencies" writeRuntimeDependencies > "$work/dependency-result" 2>&1 || rc=$?
  if [[ "$rc" == 0 ]] || ! grep -q 'Unreviewed local runtime file dependency' "$work/dependency-result"; then
    cat "$work/dependency-result"
    exit 1
  fi
done
echo 'test-privacy: PASS (16 cases)'
