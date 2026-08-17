#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

if ! command -v mvn >/dev/null 2>&1; then
  echo "错误：未找到 mvn 命令，请先安装 Maven 3.9+。" >&2
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
    ''|*[!0-9]*) echo "错误：REQUESTS_PER_LEVEL / WARMUP_REQUESTS / PROVIDER_DELAY_MS 必须是非负整数。" >&2; exit 2 ;;
  esac
done

if [[ -z "$CONCURRENCY_LEVELS" ]]; then
  echo "错误：CONCURRENCY_LEVELS 不能为空。" >&2
  exit 2
fi

mkdir -p target/qps

echo "==> OpenReach HTTP QPS 应用链路压测"
echo "    每档请求数       : ${REQUESTS_PER_LEVEL}"
echo "    预热请求数       : ${WARMUP_REQUESTS}"
echo "    并发档位         : ${CONCURRENCY_LEVELS}"
echo "    Provider模拟延迟: ${PROVIDER_DELAY_MS}ms"
echo "    API日志级别      : ${QPS_API_LOG_LEVEL}"
echo "    Upstream日志级别 : ${QPS_UPSTREAM_LOG_LEVEL}"
echo "    峰值QPS验收线    : ${MIN_PEAK_QPS} (0 = 仅生成报告，不作为失败门槛)"
echo "    压测日志目录     : ${QPS_LOG_PATH}"
echo "    说明             : 本测试使用内存 benchmark Provider，不访问真实公网搜索渠道"
echo

mvn -B -ntp \
  -Dtest=io.github.changlu.openreach.performance.OpenReachApiQpsBenchmarkTest \
  -Dopenreach.qps.enabled=true \
  -Dopenreach.qps.requestsPerLevel="${REQUESTS_PER_LEVEL}" \
  -Dopenreach.qps.warmupRequests="${WARMUP_REQUESTS}" \
  -Dopenreach.qps.concurrencyLevels="${CONCURRENCY_LEVELS}" \
  -Dopenreach.qps.providerDelayMs="${PROVIDER_DELAY_MS}" \
  -Dopenreach.qps.minPeakQps="${MIN_PEAK_QPS}" \
  -DOPENREACH_LOG_PATH="${QPS_LOG_PATH}" \
  -Dlogging.level.OPENREACH.API="${QPS_API_LOG_LEVEL}" \
  -Dlogging.level.OPENREACH.UPSTREAM="${QPS_UPSTREAM_LOG_LEVEL}" \
  test

echo
echo "==> QPS 压测完成，报告已生成"
echo "    中文分析报告 : target/qps/openreach-qps-report.md"
echo "    原始指标 CSV : target/qps/openreach-qps-report.csv"
echo "    压测日志目录 : $QPS_LOG_PATH"
echo
echo "==> 建议优先查看中文 Markdown 报告中的：核心结论 / 吞吐趋势 / 拐点信号 / 验收结果"
