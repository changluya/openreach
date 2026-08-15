#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SKILL_DIR="$PROJECT_ROOT/skills/openreach"
OUTPUT="$PROJECT_ROOT/src/main/resources/static/downloads/openreach-skill.zip"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/openreach-skill.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

if ! command -v zip >/dev/null 2>&1; then
  echo "ERROR: zip command not found." >&2
  exit 1
fi

mkdir -p "$TMP_DIR/openreach" "$(dirname "$OUTPUT")"
cp -R "$SKILL_DIR"/. "$TMP_DIR/openreach/"
find "$TMP_DIR/openreach" -type d -name '__pycache__' -prune -exec rm -rf {} +
find "$TMP_DIR/openreach" -type f \( -name '*.pyc' -o -name '.DS_Store' -o -name 'config.json' \) -delete
rm -f "$OUTPUT"
(
  cd "$TMP_DIR"
  zip -q -r "$OUTPUT" openreach
)
echo "==> Skill ZIP refreshed: $OUTPUT"
