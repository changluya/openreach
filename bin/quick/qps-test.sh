#!/usr/bin/env bash
set -euo pipefail

# Real HTTP endpoint load test. Unlike qps-unit-test.sh this script calls the
# currently running OpenReach service and therefore includes real provider
# latency/rate-limit/anti-bot behavior.
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
TOTAL_REQUESTS="${TOTAL_REQUESTS:-50}"
CONCURRENCY="${CONCURRENCY:-5}"
REQUEST_TIMEOUT_SECONDS="${REQUEST_TIMEOUT_SECONDS:-20}"
PAYLOAD="${PAYLOAD:-{\"query\":\"OpenReach\",\"limit\":1,\"region\":\"CN\",\"provider\":\"auto\"}}"
REPORT_DIR="${REPORT_DIR:-./target/qps}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 is required for qps-test.sh" >&2
  exit 1
fi

for value in "$TOTAL_REQUESTS" "$CONCURRENCY" "$REQUEST_TIMEOUT_SECONDS"; do
  case "$value" in
    ''|*[!0-9]*) echo "ERROR: TOTAL_REQUESTS/CONCURRENCY/REQUEST_TIMEOUT_SECONDS must be integers" >&2; exit 2 ;;
  esac
done
if (( TOTAL_REQUESTS < 1 || CONCURRENCY < 1 || REQUEST_TIMEOUT_SECONDS < 1 )); then
  echo "ERROR: TOTAL_REQUESTS/CONCURRENCY/REQUEST_TIMEOUT_SECONDS must be > 0" >&2
  exit 2
fi

mkdir -p "$REPORT_DIR"
export BASE_URL TOTAL_REQUESTS CONCURRENCY REQUEST_TIMEOUT_SECONDS PAYLOAD REPORT_DIR

python3 <<'PY_INNER'
import concurrent.futures
import http.client
import json
import math
import os
import statistics
import threading
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

base_url = os.environ["BASE_URL"].rstrip("/")
total = int(os.environ["TOTAL_REQUESTS"])
concurrency = int(os.environ["CONCURRENCY"])
timeout = int(os.environ["REQUEST_TIMEOUT_SECONDS"])
payload_text = os.environ["PAYLOAD"]
report_dir = Path(os.environ["REPORT_DIR"])

try:
    payload_obj = json.loads(payload_text)
except json.JSONDecodeError as exc:
    raise SystemExit(f"ERROR: PAYLOAD is not valid JSON: {exc}")

payload = json.dumps(payload_obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
parts = urlsplit(base_url)
if parts.scheme not in {"http", "https"} or not parts.hostname:
    raise SystemExit("ERROR: BASE_URL must be an absolute http:// or https:// URL")

port = parts.port or (443 if parts.scheme == "https" else 80)
base_path = parts.path.rstrip("/")
endpoint_path = f"{base_path}/api/web/search" or "/api/web/search"
default_port = 443 if parts.scheme == "https" else 80
display_authority = parts.hostname if port == default_port else f"{parts.hostname}:{port}"
display_url = f"{parts.scheme}://{display_authority}{endpoint_path}"
thread_local = threading.local()


def new_connection():
    cls = http.client.HTTPSConnection if parts.scheme == "https" else http.client.HTTPConnection
    return cls(parts.hostname, port=port, timeout=timeout)


def connection():
    conn = getattr(thread_local, "conn", None)
    if conn is None:
        conn = new_connection()
        thread_local.conn = conn
    return conn


def reset_connection():
    conn = getattr(thread_local, "conn", None)
    if conn is not None:
        try:
            conn.close()
        except Exception:
            pass
    thread_local.conn = new_connection()
    return thread_local.conn


def once(_):
    headers = {
        "Content-Type": "application/json",
        "User-Agent": "OpenReach-QPS-Test/1.0",
        "Connection": "keep-alive",
    }
    started = time.perf_counter()
    last_error = None
    for attempt in range(2):
        conn = connection() if attempt == 0 else reset_connection()
        try:
            conn.request("POST", endpoint_path, body=payload, headers=headers)
            resp = conn.getresponse()
            body = resp.read()  # fully consume so keep-alive can reuse the connection
            elapsed = (time.perf_counter() - started) * 1000
            trace_id = resp.getheader("X-OpenReach-Trace-Id", "")
            return resp.status, elapsed, trace_id, body[:200].decode("utf-8", "replace")
        except Exception as exc:
            last_error = exc
            try:
                conn.close()
            except Exception:
                pass
            thread_local.conn = None
    elapsed = (time.perf_counter() - started) * 1000
    return 0, elapsed, "", f"{type(last_error).__name__}: {last_error}"


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = max(0, min(len(ordered) - 1, math.ceil(p * len(ordered)) - 1))
    return ordered[idx]

print(f"==> Real OpenReach HTTP load test: {display_url}")
print(f"    total={total}, concurrency={concurrency}, timeout={timeout}s")
print("    client=python http.client keep-alive per worker")
print("    NOTE: this invokes real upstream providers; start conservatively to avoid provider throttling.\n")

started = time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
    results = list(pool.map(once, range(total)))
duration = time.perf_counter() - started

statuses = Counter(status for status, *_ in results)
latencies = [latency for _, latency, _, _ in results]
success = sum(count for status, count in statuses.items() if 200 <= status < 300)
failed = total - success
throughput_qps = total / duration if duration > 0 else 0.0
success_qps = success / duration if duration > 0 else 0.0
avg = statistics.fmean(latencies) if latencies else 0.0
p50 = percentile(latencies, .50)
p95 = percentile(latencies, .95)
p99 = percentile(latencies, .99)
max_ms = max(latencies, default=0.0)

summary = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "url": display_url,
    "totalRequests": total,
    "concurrency": concurrency,
    "success": success,
    "failed": failed,
    "successRate": success / total if total else 0.0,
    "durationSeconds": duration,
    "throughputQps": throughput_qps,
    "successfulQps": success_qps,
    "latencyMs": {"avg": avg, "p50": p50, "p95": p95, "p99": p99, "max": max_ms},
    "statuses": dict(sorted(statuses.items())),
}

print(json.dumps(summary, ensure_ascii=False, indent=2))

report_dir.mkdir(parents=True, exist_ok=True)
(report_dir / "openreach-real-qps-report.json").write_text(
    json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)

failures = [
    {"status": status, "latencyMs": latency, "traceId": trace_id, "response": body}
    for status, latency, trace_id, body in results
    if not 200 <= status < 300
]
if failures:
    (report_dir / "openreach-real-qps-failures.json").write_text(
        json.dumps(failures[:100], ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"\nFailure samples: {report_dir / 'openreach-real-qps-failures.json'}")
print(f"Report: {report_dir / 'openreach-real-qps-report.json'}")
PY_INNER
