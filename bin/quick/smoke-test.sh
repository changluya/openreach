#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"

post() {
  local path="$1"
  local body="$2"
  curl -fsS -X POST "$BASE_URL$path" \
    -H 'Content-Type: application/json' \
    -d "$body"
  printf '\n\n'
}

echo '== public website =='
curl -fsSI "$BASE_URL/" | head -n 1
printf '\n\n'

echo '== web search:auto CN =='
post '/api/web/search' '{"query":"杭州 AI Agent 开源框架","limit":5,"region":"CN","provider":"auto"}'

echo '== web search:auto GLOBAL =='
post '/api/web/search' '{"query":"latest Java AI Agent frameworks","limit":5,"region":"US","provider":"auto","timeRange":"month"}'

echo '== image search:auto CN =='
post '/api/web/image-search' '{"query":"杭州西湖夜景","limit":8,"region":"CN","provider":"auto"}'

echo '== image search:auto GLOBAL =='
post '/api/web/image-search' '{"query":"Golden Gate Bridge","limit":8,"region":"US","provider":"auto"}'

echo '== read =='
post '/api/web/read' '{"url":"https://spring.io/projects/spring-boot/","maxChars":20000}'

echo '== security allowlist =='
status="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/api/web/health")"
[[ "$status" == "404" ]] || { echo "ERROR: legacy health endpoint must be hidden, got $status" >&2; exit 1; }
status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/web/search" -H 'Content-Type: multipart/form-data' --data-binary 'x')"
[[ "$status" == "415" ]] || { echo "ERROR: multipart must be rejected, got $status" >&2; exit 1; }
echo 'security allowlist OK'
