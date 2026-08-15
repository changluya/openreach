#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

IMAGE="${1:-openreach:verify}"
PORT="${OPENREACH_VERIFY_PORT:-18080}"
CONTAINER_NAME="${OPENREACH_VERIFY_CONTAINER:-openreach-local-verify}"

cleanup() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker command not found." >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: curl command not found." >&2
  exit 1
fi

echo "==> 1/3 Build local OpenReach image"
"$SCRIPT_DIR/docker-build.sh" "$IMAGE"

echo "==> 2/3 Start local container"
cleanup
docker run -d \
  --name "$CONTAINER_NAME" \
  -p "127.0.0.1:${PORT}:8080" \
  "$IMAGE" >/dev/null

echo "==> 3/3 Verify Spring Boot HTTP endpoint"
# Send an intentionally invalid request. A 400 response proves that the image
# started, Spring MVC is serving requests, JSON parsing works and Bean
# Validation is active, without depending on any public search provider.
for attempt in $(seq 1 45); do
  status="$(curl -sS -o /dev/null -w '%{http_code}' \
    --connect-timeout 1 --max-time 2 \
    -X POST "http://127.0.0.1:${PORT}/api/web/search" \
    -H 'Content-Type: application/json' \
    -d '{}' 2>/dev/null || true)"

  if [[ "$status" == "400" ]]; then
    echo "PASS: local image built and container started successfully (HTTP 400 validation response)."
    echo "Image: $IMAGE"
    docker image inspect "$IMAGE" --format 'Platform: {{.Os}}/{{.Architecture}} | Size: {{.Size}} bytes' || true
    exit 0
  fi

  if ! docker ps --filter "name=^/${CONTAINER_NAME}$" --format '{{.Names}}' | grep -qx "$CONTAINER_NAME"; then
    echo "ERROR: container exited before becoming ready." >&2
    docker logs "$CONTAINER_NAME" >&2 || true
    exit 1
  fi

  sleep 1
done

echo "ERROR: OpenReach did not become ready within 45 seconds." >&2
docker logs "$CONTAINER_NAME" >&2 || true
exit 1
