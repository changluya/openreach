#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-${OPENREACH_BASE_URL:-}}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-5}"

if [[ -z "$BASE_URL" ]]; then
  echo "ERROR: set BASE_URL or OPENREACH_BASE_URL to the address reachable from the caller environment." >&2
  echo "Example: BASE_URL=http://openreach:8080 ./bin/quick/connectivity-test.sh" >&2
  exit 2
fi

BASE_URL="${BASE_URL%/}"
HOST_INFO="$(python3 - "$BASE_URL" <<'PY'
import sys
from urllib.parse import urlsplit
u=urlsplit(sys.argv[1])
if u.scheme not in ('http','https') or not u.hostname:
    raise SystemExit('ERROR: BASE_URL must be an absolute http:// or https:// URL')
port=u.port or (443 if u.scheme == 'https' else 80)
print(f'{u.hostname}\t{port}\t{u.scheme}')
PY
)"
IFS=$'\t' read -r HOST PORT SCHEME <<< "$HOST_INFO"

echo "==> OpenReach connectivity test"
echo "    base url : $BASE_URL"
echo "    target   : $HOST:$PORT ($SCHEME)"
echo "    caller   : $([[ -f /.dockerenv ]] && echo container || echo host/unknown)"

if [[ "$HOST" == "localhost" || "$HOST" == "127.0.0.1" || "$HOST" == "::1" ]]; then
  if [[ -f /.dockerenv ]]; then
    echo "WARN: BASE_URL uses localhost inside a container. localhost points to THIS caller container, not the OpenReach container." >&2
    echo "      If both containers share a Docker network, use the OpenReach service/container DNS name, e.g. http://openreach:8080." >&2
  else
    echo "NOTE: localhost only works when OpenReach is published on this same host." >&2
  fi
fi

python3 - "$HOST" "$PORT" "$TIMEOUT_SECONDS" <<'PY'
import socket, sys
host, port, timeout = sys.argv[1], int(sys.argv[2]), float(sys.argv[3])
try:
    infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
except OSError as e:
    print(f'FAIL DNS: {host}: {e}', file=sys.stderr)
    raise SystemExit(10)
print('PASS DNS:', ', '.join(dict.fromkeys(i[4][0] for i in infos)))
last=None
for family, socktype, proto, _, addr in infos:
    s=socket.socket(family, socktype, proto)
    s.settimeout(timeout)
    try:
        s.connect(addr)
        print(f'PASS TCP: connected to {addr[0]}:{addr[1]}')
        raise SystemExit(0)
    except OSError as e:
        last=e
    finally:
        s.close()
print(f'FAIL TCP: cannot connect to {host}:{port}: {last}', file=sys.stderr)
raise SystemExit(11)
PY

echo "==> HTTP homepage probe"
HTTP_STATUS="$(curl -sS --connect-timeout "$TIMEOUT_SECONDS" --max-time "$TIMEOUT_SECONDS" -o /dev/null -w '%{http_code}' "$BASE_URL/" || true)"
if [[ "$HTTP_STATUS" != "200" ]]; then
  echo "FAIL HTTP: GET $BASE_URL/ returned status=${HTTP_STATUS:-connect_error}" >&2
  exit 12
fi
echo "PASS HTTP: GET / -> 200"

echo "==> API reachability probe (no upstream provider call)"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
API_STATUS="$(curl -sS --connect-timeout "$TIMEOUT_SECONDS" --max-time "$TIMEOUT_SECONDS" \
  -o "$TMP" -w '%{http_code}' -X POST "$BASE_URL/api/web/search" \
  -H 'Content-Type: application/json' --data '{}' || true)"
if [[ "$API_STATUS" == "400" ]] && grep -q 'VALIDATION_ERROR' "$TMP"; then
  echo "PASS API: POST /api/web/search {} -> 400 VALIDATION_ERROR (OpenReach reached; no upstream search executed)"
else
  echo "FAIL API: status=${API_STATUS:-connect_error} body=$(head -c 500 "$TMP" | tr '\n' ' ')" >&2
  exit 13
fi

echo "RESULT: OpenReach service connectivity is OK from this caller environment."
