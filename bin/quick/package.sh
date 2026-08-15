#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

"$SCRIPT_DIR/build-skill-zip.sh"

HOST_PROXY="${OPENREACH_BUILD_PROXY:-}"

if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: mvn command not found. Install Maven 3.9+ first." >&2
  exit 1
fi

MAVEN_CMD=(mvn -B -ntp clean package)
TMP_SETTINGS=""

cleanup() {
  if [[ -n "$TMP_SETTINGS" && -f "$TMP_SETTINGS" ]]; then
    rm -f "$TMP_SETTINGS"
  fi
}
trap cleanup EXIT

if [[ -n "$HOST_PROXY" ]]; then
  case "$HOST_PROXY" in
    http://*|https://*) ;;
    *)
      echo "ERROR: OPENREACH_BUILD_PROXY must start with http:// or https://" >&2
      exit 2
      ;;
  esac

  protocol="${HOST_PROXY%%://*}"
  authority="${HOST_PROXY#*://}"
  authority="${authority%%/*}"

  case "$authority" in
    *@*)
      echo "ERROR: authenticated proxy URLs are not supported by this quick script." >&2
      exit 2
      ;;
  esac

  case "$authority" in
    *:*) host="${authority%:*}"; port="${authority##*:}" ;;
    *) host="$authority"; [[ "$protocol" == "https" ]] && port="443" || port="80" ;;
  esac

  case "$port" in
    ''|*[!0-9]*)
      echo "ERROR: invalid proxy port: $port" >&2
      exit 2
      ;;
  esac

  TMP_SETTINGS="$(mktemp "${TMPDIR:-/tmp}/openreach-maven-settings.XXXXXX")"
  cat > "$TMP_SETTINGS" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <proxies>
    <proxy>
      <id>openreach-host-build-proxy</id>
      <active>true</active>
      <protocol>${protocol}</protocol>
      <host>${host}</host>
      <port>${port}</port>
      <nonProxyHosts>localhost|127.*|[::1]</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
XML
  MAVEN_CMD=(mvn -s "$TMP_SETTINGS" -B -ntp clean package)
  echo "==> Maven package with proxy: $HOST_PROXY"
else
  echo "==> Maven package"
fi

"${MAVEN_CMD[@]}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 command not found; OpenReach Skill tests are part of the release gate." >&2
  exit 1
fi

echo "==> OpenReach Skill Python tests"
python3 -m unittest discover -s skills/openreach/tests -p 'test_*.py' -v

JAR="$(find target -maxdepth 1 -type f -name 'openreach-*.jar' ! -name '*.jar.original' -print | head -n 1)"
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "ERROR: executable JAR was not produced under target/openreach-*.jar" >&2
  exit 1
fi

COUNT="$(find target -maxdepth 1 -type f -name 'openreach-*.jar' ! -name '*.jar.original' | wc -l | tr -d ' ')"
if [[ "$COUNT" != "1" ]]; then
  echo "ERROR: expected exactly one executable OpenReach JAR, found $COUNT" >&2
  find target -maxdepth 1 -type f -name 'openreach-*.jar' -print >&2 || true
  exit 1
fi

echo "==> Package ready: $JAR"
