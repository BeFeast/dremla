#!/usr/bin/env bash
# Self-test for scripts/check-forbidden-apis.sh. Builds app sources at runtime in a scratch
# directory and asserts that each forbidden API is caught and that clean code passes.
# No arguments.
set -euo pipefail

if [[ $# -ne 0 ]]; then
  echo "usage: scripts/test-forbidden-apis.sh (no arguments)" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checker="$repo_root/scripts/check-forbidden-apis.sh"
[[ -f "$checker" ]] || { echo "test-forbidden-apis: missing $checker" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
tree="$work/tree"

failures=0
cases=0

# run_case <name> <expect: pass|fail> <source path> <source content>
# A failing case must report <source path>:1.
run_case() {
  local name="$1" expect="$2" path="$3" content="$4"
  rm -rf "$tree"
  mkdir -p "$tree/scripts" "$tree/app/src/main/java/com/example" "$(dirname "$tree/$path")"
  cp "$checker" "$tree/scripts/check-forbidden-apis.sh"
  printf 'package com.example\n\nclass Clean\n' >"$tree/app/src/main/java/com/example/Clean.kt"
  printf '%s\n' "$content" >"$tree/$path"

  local out rc=0
  out="$(bash "$tree/scripts/check-forbidden-apis.sh" 2>&1)" || rc=$?
  cases=$((cases + 1))

  local verdict="ok"
  if [[ "$expect" == "fail" ]]; then
    [[ $rc -eq 1 && "$out" == *"$path:1:"* ]] || verdict="FAIL"
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

main_kt="app/src/main/java/com/example/Scene.kt"

# Must be caught.
run_case "PathEasing" fail "$main_kt" "val easing = PathEasing(curve)"
run_case "PathHitTester" fail "$main_kt" "val tester = PathHitTester(path)"
run_case "path iterator import" fail "$main_kt" "import androidx.compose.ui.graphics.PathIterator"
run_case "path segments" fail "$main_kt" "for (segment: PathSegment in path) draw(segment)"
run_case "graphics-path package" fail "$main_kt" "import androidx.graphics.path.PathIterator"
run_case "Path.toSvg()" fail "$main_kt" "val svg = path.toSvg()"
run_case "Path.divide()" fail "$main_kt" "val contours = path.divide()"
run_case "Java source" fail "app/src/main/java/com/example/Hit.java" "PathHitTester tester = null;"

# Must pass.
run_case "clean scene" pass "$main_kt" "val path = Path().apply { moveTo(0f, 0f); lineTo(1f, 1f) }"
run_case "longer identifiers" pass "$main_kt" "val myPathEasingCurve = PathIteratorsDoc; fun pathToSvgName() = 1"
run_case "test sources are not app code" pass "app/src/test/java/com/example/T.kt" "val e = PathEasing(curve)"

if [[ $failures -ne 0 ]]; then
  echo "test-forbidden-apis: FAIL ($failures of $cases case(s))" >&2
  exit 1
fi
echo "test-forbidden-apis: PASS ($cases cases)"
