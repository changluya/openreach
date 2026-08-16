#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

VERSION="${1:-}"
NAMESPACE="${2:-codercl}"
REPOSITORY="${3:-openreach}"
HOST_PROXY="${OPENREACH_BUILD_PROXY:-}"
NO_PROXY_VALUE="${OPENREACH_NO_PROXY:-localhost,127.0.0.1,::1}"

if [[ -z "$VERSION" ]]; then
  echo "Usage: $0 <version> [docker-namespace] [repository]" >&2
  exit 1
fi
VERSION="${VERSION#v}"
IMAGE="${NAMESPACE}/${REPOSITORY}"

for cmd in docker mvn; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: $cmd command not found." >&2
    exit 1
  fi
done
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker daemon is not available." >&2
  exit 1
fi
if ! docker buildx version >/dev/null 2>&1; then
  echo "ERROR: Docker Buildx is unavailable." >&2
  exit 1
fi

# Compile + test once on the host. Docker only packages the resulting JAR.
"$SCRIPT_DIR/package.sh"

container_proxy() {
  local proxy="$1"
  if [[ -z "$proxy" ]]; then return 0; fi
  if [[ "$(uname -s)" == "Darwin" ]]; then
    proxy="${proxy/127.0.0.1/host.docker.internal}"
    proxy="${proxy/localhost/host.docker.internal}"
  elif [[ "$proxy" == *"127.0.0.1"* || "$proxy" == *"localhost"* ]]; then
    echo "ERROR: On Linux, BuildKit cannot reach host proxy through 127.0.0.1/localhost." >&2
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
else
  BUILDER_NAME="${BUILDER_NAME:-openreach-builder}"
fi

if docker buildx inspect "$BUILDER_NAME" >/dev/null 2>&1; then
  if [[ -n "$BUILDER_PROXY" ]]; then
    CURRENT="$(docker buildx inspect "$BUILDER_NAME" 2>/dev/null || true)"
    if [[ "$CURRENT" != *"env.HTTP_PROXY=\"$BUILDER_PROXY\""* && "$CURRENT" != *"env.HTTP_PROXY=$BUILDER_PROXY"* ]]; then
      echo "==> Proxy changed; recreating builder: $BUILDER_NAME"
      docker buildx rm "$BUILDER_NAME" >/dev/null 2>&1 || true
    fi
  fi
fi

if ! docker buildx inspect "$BUILDER_NAME" >/dev/null 2>&1; then
  CREATE_ARGS=(--name "$BUILDER_NAME" --driver docker-container --use)
  if [[ -n "$BUILDER_PROXY" ]]; then
    echo "==> Creating BuildKit builder with proxy"
    echo "    host input    : $HOST_PROXY"
    echo "    builder proxy : $BUILDER_PROXY"
    CREATE_ARGS+=(
      --driver-opt "env.HTTP_PROXY=$BUILDER_PROXY"
      --driver-opt "env.HTTPS_PROXY=$BUILDER_PROXY"
    )
  fi
  docker buildx create "${CREATE_ARGS[@]}" >/dev/null
else
  docker buildx use "$BUILDER_NAME" >/dev/null
fi

run_with_host_proxy docker buildx inspect "$BUILDER_NAME" --bootstrap >/dev/null

echo "==> Publishing package-only multi-arch image"
echo "    ${IMAGE}:${VERSION}"
echo "    ${IMAGE}:latest"
echo "    platforms: linux/amd64,linux/arm64"
echo "    JAR: $(find target -maxdepth 1 -type f -name 'openreach-*.jar' ! -name '*.jar.original' -print | head -n 1)"
if [[ -n "$BUILDER_PROXY" ]]; then
  echo "    proxy: enabled"
  echo "    host/client: $HOST_PROXY"
  echo "    buildkit   : $BUILDER_PROXY"
fi

run_with_host_proxy docker buildx build \
  --builder "$BUILDER_NAME" \
  --platform linux/amd64,linux/arm64 \
  -t "${IMAGE}:${VERSION}" \
  -t "${IMAGE}:latest" \
  --push \
  .

echo "==> Verifying remote manifest"
MANIFEST="$(run_with_host_proxy docker buildx imagetools inspect "${IMAGE}:${VERSION}")"
printf '%s\n' "$MANIFEST"
if [[ "$MANIFEST" != *"linux/amd64"* || "$MANIFEST" != *"linux/arm64"* ]]; then
  echo "ERROR: remote manifest does not contain both linux/amd64 and linux/arm64." >&2
  exit 1
fi

echo
echo "Publish completed."
echo "Run: docker run -d --name openreach --restart unless-stopped -p 8080:8080 -e OPENREACH_MONITOR_USERNAME=openreach -e OPENREACH_MONITOR_PASSWORD=openreach ${IMAGE}:latest"
