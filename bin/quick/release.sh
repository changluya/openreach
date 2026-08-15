#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

VERSION="${1:-}"
NAMESPACE="${2:-codercl}"
REPOSITORY="${3:-openreach}"

if [[ -z "$VERSION" ]]; then
  VERSION="$(awk '
    /<artifactId>openreach<\/artifactId>/ { found=1; next }
    found && /<version>/ {
      line=$0
      sub(/.*<version>/, "", line)
      sub(/<\/version>.*/, "", line)
      gsub(/[[:space:]]/, "", line)
      print line
      exit
    }
  ' "$PROJECT_ROOT/pom.xml")"
fi

if [[ -z "$VERSION" ]]; then
  echo "ERROR: version is empty; pass it explicitly: $0 1.0.2" >&2
  exit 1
fi

exec "$SCRIPT_DIR/docker-publish.sh" "$VERSION" "$NAMESPACE" "$REPOSITORY"
