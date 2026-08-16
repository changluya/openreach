#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: mvn command not found. Install Maven 3.9+ first." >&2
  exit 1
fi

REQUESTS_PER_LEVEL="${REQUESTS_PER_LEVEL:-500}"
WARMUP_REQUESTS="${WARMUP_REQUESTS:-50}"
CONCURRENCY_LEVELS="${CONCURRENCY_LEVELS:-1,4,8,16,32}"
PROVIDER_DELAY_MS="${PROVIDER_DELAY_MS:-0}"
QPS_API_LOG_LEVEL="${QPS_API_LOG_LEVEL:-INFO}"
QPS_UPSTREAM_LOG_LEVEL="${QPS_UPSTREAM_LOG_LEVEL:-INFO}"
MIN_PEAK_QPS="${MIN_PEAK_QPS:-0}"
QPS_LOG_PATH="${QPS_LOG_PATH:-target/qps/logs}"

for value in "$REQUESTS_PER_LEVEL" "$WARMUP_REQUESTS" "$PROVIDER_DELAY_MS"; do
  case "$value" in
    ''|*[!0-9]*) echo "ERROR: requests/warmup/delay values must be non-negative integers" >&2; exit 2 ;;
  esac
done

if [[ -z "$CONCURRENCY_LEVELS" ]]; then
  echo "ERROR: CONCURRENCY_LEVELS cannot be empty" >&2
  exit 2
fi

mkdir -p target/qps

echo "==> OpenReach HTTP QPS benchmark"
echo "    requests/level : $REQUESTS_PER_LEVEL"
echo "    warmup         : $WARMUP_REQUESTS"
echo "    concurrency    : $CONCURRENCY_LEVELS"
echo "    provider delay : ${PROVIDER_DELAY_MS}ms"
echo "    api log level  : $QPS_API_LOG_LEVEL"
echo "    upstream log   : $QPS_UPSTREAM_LOG_LEVEL"
echo "    min peak qps   : $MIN_PEAK_QPS (0 = report only)"
echo "    benchmark logs : $QPS_LOG_PATH"
echo

mvn -B -ntp \
  -Dtest=io.github.changlu.openreach.performance.OpenReachApiQpsBenchmarkTest \
  -Dopenreach.qps.enabled=true \
  -Dopenreach.qps.requestsPerLevel="$REQUESTS_PER_LEVEL" \
  -Dopenreach.qps.warmupRequests="$WARMUP_REQUESTS" \
  -Dopenreach.qps.concurrencyLevels="$CONCURRENCY_LEVELS" \
  -Dopenreach.qps.providerDelayMs="$PROVIDER_DELAY_MS" \
  -Dopenreach.qps.minPeakQps="$MIN_PEAK_QPS" \
  -DOPENREACH_LOG_PATH="$QPS_LOG_PATH" \
  -Dlogging.level.OPENREACH.API="$QPS_API_LOG_LEVEL" \
  -Dlogging.level.OPENREACH.UPSTREAM="$QPS_UPSTREAM_LOG_LEVEL" \
  test

echo
echo "==> QPS reports"
echo "    target/qps/openreach-qps-report.md"
echo "    target/qps/openreach-qps-report.csv"
