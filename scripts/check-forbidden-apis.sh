#!/usr/bin/env bash
# Forbidden APIs in app code. No arguments; scripts/test-forbidden-apis.sh is its self-test.
#
# The APK carries no native libraries, so app/build.gradle.kts drops libandroidx.graphics.path.so
# (androidx.graphics:graphics-path, a Compose dependency). androidx.graphics.path.PathIteratorImpl
# loads that library in its static initializer on every API level, and the conic conversion is
# native code too, so any Compose path iteration throws UnsatisfiedLinkError at runtime on every
# device, the API 34 target included: Path.iterator() (and the PathSegment values it yields),
# PathEasing, PathHitTester, Path.toSvg(), Path.divide(). App code must not use them.
#
# This is a plain token grep over the Kotlin and Java sources in app/src/main, not a type check:
# `.divide(` and `toSvg(` are flagged on any receiver. It cannot see `for (segment in path)`
# without a PathSegment reference, nor path iteration inside a library.
set -euo pipefail

if [[ $# -ne 0 ]]; then
  echo "usage: scripts/check-forbidden-apis.sh (no arguments)" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

readonly SOURCES="app/src/main"
readonly RE_FORBIDDEN='(^|[^[:alnum:]_])Path(Easing|HitTester|Iterator|Segment)([^[:alnum:]_]|$)|androidx\.graphics\.path|(^|[^[:alnum:]_])toSvg[[:space:]]*\(|\.divide[[:space:]]*\('

if [[ ! -d "$SOURCES" ]]; then
  echo "check-forbidden-apis: missing $SOURCES" >&2
  exit 2
fi

rc=0
hits="$(grep -r -n -E --include='*.kt' --include='*.java' -e "$RE_FORBIDDEN" "$SOURCES")" || rc=$?
if [[ $rc -gt 1 ]]; then
  echo "check-forbidden-apis: grep failed (exit $rc)" >&2
  exit 2
fi

if [[ $rc -eq 0 ]]; then
  echo "FAIL  Compose path iteration in $SOURCES:"
  printf '%s\n' "$hits" | sed 's/^/        /'
  echo "check-forbidden-apis: FAIL. PathEasing, PathHitTester, PathIterator/PathSegment" \
    "(Path.iterator()), Path.toSvg() and Path.divide() need libandroidx.graphics.path.so," \
    "which the APK does not ship; they crash on every device, the API 34 box included." \
    "See docs/ci.md (Native libraries)." >&2
  exit 1
fi
echo "ok    no Compose path iteration in $SOURCES"
echo "check-forbidden-apis: PASS"
