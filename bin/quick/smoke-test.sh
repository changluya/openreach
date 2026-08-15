#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"

echo '== web search:auto =='
curl -fsS -X POST "$BASE_URL/api/web/search" \
  -H 'Content-Type: application/json' \
  -d '{"query":"杭州 AI Agent 开源框架","limit":5,"region":"CN","provider":"auto"}'
printf '\n\n'

echo '== image search:auto =='
curl -fsS -X POST "$BASE_URL/api/web/image-search" \
  -H 'Content-Type: application/json' \
  -d '{"query":"杭州西湖夜景","limit":8,"region":"CN","provider":"auto"}'
printf '\n\n'

echo '== read =='
curl -fsS -X POST "$BASE_URL/api/web/read" \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://spring.io/projects/spring-boot/","maxChars":20000}'
printf '\n'
