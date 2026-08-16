#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

IMAGE="${1:-openreach:local}"
HOST_PROXY="${OPENREACH_BUILD_PROXY:-}"
NO_PROXY_VALUE="${OPENREACH_NO_PROXY:-localhost,127.0.0.1,::1}"

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker command not found." >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker daemon is not available. Start Docker Desktop/Engine first." >&2
  exit 1
fi

if [[ "${OPENREACH_SKIP_PACKAGE:-false}" != "true" ]]; then
  "$SCRIPT_DIR/package.sh"
fi

container_proxy() {
  local proxy="$1"
  if [[ -z "$proxy" ]]; then return 0; fi
  if [[ "$(uname -s)" == "Darwin" ]]; then
    proxy="${proxy/127.0.0.1/host.docker.internal}"
    proxy="${proxy/localhost/host.docker.internal}"
  elif [[ "$proxy" == *"127.0.0.1"* || "$proxy" == *"localhost"* ]]; then
    echo "ERROR: On Linux, BuildKit cannot reach a host proxy through 127.0.0.1/localhost." >&2
    echo "       Use a host IP/hostname reachable from Docker instead." >&2
    exit 1
  fi
  printf '%s' "$proxy"
}

run_with_host_proxy() {
  if [[ -n "$HOST_PROXY" ]]; then
    HTTP_PROXY="$HOST_PROXY" HTTPS_PROXY="$HOST_PROXY" \
    http_proxy="$HOST_PROXY" https_proxy="$HOST_PROXY" \
    NO_PROXY="$NO_PROXY_VALUE" no_proxy="$NO_PROXY_VALUE" \
      "$@"
  else
    "$@"
  fi
}

BUILDER_PROXY="$(container_proxy "$HOST_PROXY")"
if [[ -n "$BUILDER_PROXY" ]]; then
  BUILDER_NAME="${BUILDER_NAME:-openreach-builder-proxy}"
  if docker buildx inspect "$BUILDER_NAME" >/dev/null 2>&1; then
    CURRENT="$(docker buildx inspect "$BUILDER_NAME" 2>/dev/null || true)"
    if [[ "$CURRENT" != *"env.HTTP_PROXY=\"$BUILDER_PROXY\""* && "$CURRENT" != *"env.HTTP_PROXY=$BUILDER_PROXY"* ]]; then
      docker buildx rm "$BUILDER_NAME" >/dev/null 2>&1 || true
    fi
  fi
  if ! docker buildx inspect "$BUILDER_NAME" >/dev/null 2>&1; then
    echo "==> Creating BuildKit builder with proxy: $BUILDER_PROXY"
    docker buildx create \
      --name "$BUILDER_NAME" \
      --driver docker-container \
      --driver-opt "env.HTTP_PROXY=$BUILDER_PROXY" \
      --driver-opt "env.HTTPS_PROXY=$BUILDER_PROXY" \
      --use >/dev/null
  else
    docker buildx use "$BUILDER_NAME" >/dev/null
  fi
  run_with_host_proxy docker buildx inspect "$BUILDER_NAME" --bootstrap >/dev/null
  echo "==> Docker package-only build: $IMAGE"
  run_with_host_proxy docker buildx build \
    --builder "$BUILDER_NAME" \
    --load \
    -t "$IMAGE" \
    .
else
  echo "==> Docker package-only build: $IMAGE"
  docker build -t "$IMAGE" .
fi

echo
echo "Build completed: $IMAGE"
echo "Run: docker run -d --name openreach -p 8080:8080 -e OPENREACH_MONITOR_USERNAME=openreach -e OPENREACH_MONITOR_PASSWORD=openreach $IMAGE"
