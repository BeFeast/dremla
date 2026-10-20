#!/usr/bin/env bash
# Docker-only packaging gate. Run on a build host under nice -n 19; no arguments.
set -euo pipefail
[[ $# -eq 0 ]] || { echo 'usage: scripts/test-collection-image.sh (no arguments)' >&2; exit 2; }
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
command -v docker >/dev/null
command -v python3 >/dev/null
command -v setsid >/dev/null
revision="$(git rev-parse HEAD)"
scratch="$(mktemp -d)"
prefix="${DREMLA_IMAGE_TEST_PREFIX:-dremla-image-test-$(basename "$scratch" | tr '[:upper:]' '[:lower:]')-$$}"
[[ "$prefix" =~ ^dremla-image-test-[a-z0-9.-]+$ ]] || { rm -rf "$scratch"; exit 2; }
image="${DREMLA_IMAGE_UNDER_TEST:-$prefix:local}"
owns_image=0
child=
builder="$prefix-builder"
builder_image=moby/buildkit:buildx-stable-1
builder_image_present=0
docker image inspect "$builder_image" >/dev/null 2>&1 && builder_image_present=1
stage=build
cleanup() {
  local rc=$? failed=0 mode kind
  trap - EXIT
  for mode in smoke init bare; do
    for kind in service setup; do
      if docker container inspect "$prefix-$mode-$kind" >/dev/null 2>&1; then
        docker rm -f "$prefix-$mode-$kind" >/dev/null || failed=1
      fi
    done
    for kind in state published; do
      if docker volume inspect "$prefix-$mode-$kind" >/dev/null 2>&1; then
        docker volume rm "$prefix-$mode-$kind" >/dev/null || failed=1
      fi
    done
  done
  if [[ $owns_image == 1 ]] && docker image inspect "$image" >/dev/null 2>&1; then
    docker image rm "$image" >/dev/null || failed=1
  fi
  # A dedicated builder owns its cache, container and volume; never prune shared caches.
  if docker buildx inspect "$builder" >/dev/null 2>&1; then
    docker buildx rm "$builder" >/dev/null || failed=1
  fi
  if [[ $owns_image == 1 && $builder_image_present == 0 ]] && docker image inspect "$builder_image" >/dev/null 2>&1; then
    docker image rm "$builder_image" >/dev/null || failed=1
  fi
  if [[ $rc != 0 ]]; then
    echo "FAIL $stage" >&2
    [[ ! -f "$scratch/output" ]] || tail -n 60 "$scratch/output" >&2
  fi
  rm -rf "$scratch" || failed=1
  if [[ $failed != 0 ]]; then echo 'FAIL cleanup' >&2; rc=1; else echo 'PASS cleanup'; fi
  exit "$rc"
}
trap cleanup EXIT
terminate() {
  if [[ -n "$child" ]]; then
    # Stop the host driver and its Docker clients before removing their resources.
    kill -TERM -- "-$child" 2>/dev/null || true
    wait "$child" 2>/dev/null || true
  fi
  exit "$1"
}
trap 'terminate 130' INT
trap 'terminate 143' TERM
run_child() {
  setsid "$@" &
  child=$!
  local rc=0
  wait "$child" || rc=$?
  child=
  return "$rc"
}

if [[ -z "${DREMLA_IMAGE_UNDER_TEST:-}" ]]; then
  owns_image=1
  run_child docker buildx create --name "$builder" --driver docker-container \
    --driver-opt cpu-shares=2 >"$scratch/output" 2>&1
  run_child docker build --builder "$builder" --load --build-arg "DREMLA_REVISION=$revision" \
    -t "$image" collection-service >>"$scratch/output" 2>&1
  echo "PASS build ($(docker image inspect --format '{{.Size}}' "$image") bytes)"
  stage='revision-validation'
  for bad in missing invalid short; do
    args=()
    case "$bad" in
      invalid) args+=(--build-arg DREMLA_REVISION=zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz) ;;
      short) args+=(--build-arg DREMLA_REVISION=abc123) ;;
    esac
    if run_child docker build --builder "$builder" "${args[@]}" collection-service >"$scratch/output" 2>&1; then
      echo "revision validation accepted $bad" >&2
      exit 1
    fi
    # Require failure at the validation instruction, not a transport/pull error.
    grep -q 'test.*DREMLA_REVISION.*did not complete successfully' "$scratch/output"
  done
  echo 'PASS revision validation (missing, non-hex, short)'
else
  echo "PASS supplied image (build skipped; caller retains ownership)"
fi
image_id="$(docker image inspect --format '{{.Id}}' "$image")"
stage='container-checks'
: >"$scratch/output"
run_child python3 -I collection-service/tests/image.py "$image_id" "$revision" "$prefix" "$scratch"
test "$(docker image inspect --format '{{.Id}}' "$image")" = "$image_id"
echo "PASS image identity unchanged: $image_id"
stage=complete
