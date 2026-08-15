#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

"$SCRIPT_DIR/build-skill-zip.sh"

if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: mvn command not found. Install Maven 3.9+ first." >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 command not found; OpenReach Skill tests are part of the release gate." >&2
  exit 1
fi

echo '==> Java unit tests'
mvn -B -ntp clean test

echo '==> OpenReach Skill Python tests'
python3 -m unittest discover -s skills/openreach/tests -p 'test_*.py' -v

if [[ "${RUN_SMOKE:-false}" == "true" ]]; then
  echo "RUN_SMOKE=true: make sure the Spring Boot service is already running."
  "$SCRIPT_DIR/smoke-test.sh"
fi
