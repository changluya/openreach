#!/usr/bin/env bash
set -euo pipefail

LOG_DIR="${OPENREACH_LOG_DIR:-./logs}"
MODE="${1:-help}"

usage() {
  cat <<'TXT'
OpenReach log helper

Usage:
  ./bin/quick/logs.sh app
  ./bin/quick/logs.sh api
  ./bin/quick/logs.sh upstream
  ./bin/quick/logs.sh errors
  ./bin/quick/logs.sh trace <traceId>

Environment:
  OPENREACH_LOG_DIR=/data/openreach/logs

Examples:
  OPENREACH_LOG_DIR=/data/openreach/logs ./bin/quick/logs.sh upstream
  OPENREACH_LOG_DIR=/data/openreach/logs ./bin/quick/logs.sh trace req-20260815T175351289-8f31a4c2
TXT
}

require_file() {
  local file="$1"
  if [[ ! -f "$file" ]]; then
    echo "Log file not found: $file" >&2
    echo "Current log directory: $LOG_DIR" >&2
    exit 2
  fi
}

case "$MODE" in
  app)
    FILE="$LOG_DIR/openreach.log"
    require_file "$FILE"
    tail -n 200 -f "$FILE"
    ;;
  api)
    FILE="$LOG_DIR/openreach-api.log"
    require_file "$FILE"
    tail -n 200 -f "$FILE"
    ;;
  upstream)
    FILE="$LOG_DIR/openreach-upstream.log"
    require_file "$FILE"
    tail -n 200 -f "$FILE"
    ;;
  errors)
    FILE="$LOG_DIR/openreach-upstream.log"
    require_file "$FILE"
    grep -E 'search_fail|read_fail|provider_fail|http_io_fail|http_interrupted|HTTP_403|HTTP_429|BOT_CHALLENGE|TIMEOUT' "$FILE" | tail -n 300
    ;;
  trace)
    TRACE_ID="${2:-}"
    if [[ -z "$TRACE_ID" ]]; then
      echo "trace mode requires a traceId" >&2
      usage >&2
      exit 2
    fi
    shopt -s nullglob
    files=("$LOG_DIR"/openreach*.log)
    if (( ${#files[@]} == 0 )); then
      echo "No openreach*.log files found under: $LOG_DIR" >&2
      exit 2
    fi
    grep -H -- "$TRACE_ID" "${files[@]}" || true
    ;;
  help|-h|--help)
    usage
    ;;
  *)
    echo "Unknown mode: $MODE" >&2
    usage >&2
    exit 2
    ;;
esac
