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
  echo "ERROR: version is empty; pass it explicitly: $0 0.1.4" >&2
  exit 1
fi

VERSION="${VERSION#v}"
POM_VERSION="$(awk '
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

if [[ -z "$POM_VERSION" ]]; then
  echo "ERROR: unable to resolve OpenReach version from pom.xml" >&2
  exit 1
fi
if [[ "$VERSION" != "$POM_VERSION" ]]; then
  echo "ERROR: release version ($VERSION) does not match pom.xml version ($POM_VERSION)." >&2
  echo "       Update pom.xml first or publish version $POM_VERSION." >&2
  exit 2
fi

echo "==> OpenReach release"
echo "    version    : $VERSION"
echo "    image      : ${NAMESPACE}/${REPOSITORY}:${VERSION}"
if [[ -n "${OPENREACH_BUILD_PROXY:-}" ]]; then
  echo "    build proxy: ${OPENREACH_BUILD_PROXY}"
fi

exec "$SCRIPT_DIR/docker-publish.sh" "$VERSION" "$NAMESPACE" "$REPOSITORY"
