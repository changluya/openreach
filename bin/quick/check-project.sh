#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

mvn clean test

if [[ "${RUN_SMOKE:-false}" == "true" ]]; then
  echo "RUN_SMOKE=true: make sure the Spring Boot service is already running."
  "$(dirname "$0")/smoke-test.sh"
fi
