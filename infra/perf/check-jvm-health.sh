#!/usr/bin/env bash
# Fails when a JVM service container was OOM-killed, restarted or is not running, or when the old-gen
# live data left after its last major GC exceeds MAX_LIVE_DATA_PCT of the old-gen max (leak signal).
# To get a real reading it first forces a full GC by requesting a live heap dump (discarded); that
# endpoint is only exposed by the test overlay docker-compose.gc-probe.yml. Without it, a service
# that never ran a major GC has no live-data reading and passes: its old gen never filled.
#
# Usage: check-jvm-health.sh [<service>=<actuator base url> ...]
# With no arguments it checks every JVM service (ALL_SERVICES below) and first fails if
# docker-compose.yml builds a service missing from that list, so CI never silently skips one.
# Containers are looked up as ${CONTAINER_PREFIX}<service>. COOKIE_JAR (from login.sh) is sent
# with every metrics call — the gateway's actuator metrics need an authenticated session.
set -uo pipefail

MAX_LIVE_DATA_PCT=${MAX_LIVE_DATA_PCT:-60}
CONTAINER_PREFIX=${CONTAINER_PREFIX:-cartogra-}
COOKIE_JAR=${COOKIE_JAR:-}

ALL_SERVICES=(
  gateway=http://localhost:8080/actuator
  registry=http://localhost:8081/api/v1/registry/actuator
  topology=http://localhost:8082/api/v1/topology/actuator
  ingestion=http://localhost:8085/api/v1/ingestion/actuator
)
COMPOSE_FILE_PATH=${COMPOSE_FILE_PATH:-$(dirname "$0")/../docker-compose/docker-compose.yml}

if [ $# -eq 0 ]; then
  built=$(docker compose -f "$COMPOSE_FILE_PATH" config --format json | jq -r '.services | to_entries[] | select(.value.build) | .key' | sort)
  known=$(printf '%s\n' "${ALL_SERVICES[@]%%=*}" | sort)
  if [ "$built" != "$known" ]; then
    echo "ALL_SERVICES is out of step with ${COMPOSE_FILE_PATH}:" >&2
    diff <(echo "$known") <(echo "$built") >&2
    exit 2
  fi
  set -- "${ALL_SERVICES[@]}"
fi

curl_auth=()
[ -n "$COOKIE_JAR" ] && curl_auth=(-b "$COOKIE_JAR")

metric() {
  curl -sSf "${curl_auth[@]}" "$1/metrics/$2" | jq -r '.measurements[] | select(.statistic == "VALUE") | .value'
}

mib() {
  awk -v b="$1" 'BEGIN { printf "%.0f", b / 1048576 }'
}

failures=()
rows=()

for arg in "$@"; do
  service=${arg%%=*}
  base=${arg#*=}
  container="${CONTAINER_PREFIX}${service}"

  state=$(docker inspect -f '{{.State.Status}} {{.State.OOMKilled}} {{.RestartCount}}' "$container" 2>/dev/null)
  if [ -z "$state" ]; then
    failures+=("${service}: container ${container} not found")
    rows+=("| ${service} | missing | - | - | - | - |")
    continue
  fi
  read -r status oom restarts <<<"$state"
  [ "$oom" = "true" ] && failures+=("${service}: OOM-killed")
  [ "$restarts" != "0" ] && failures+=("${service}: restarted ${restarts} time(s)")
  [ "$status" != "running" ] && failures+=("${service}: container is ${status}")

  mem=$(docker stats --no-stream --format '{{.MemUsage}}' "$container" 2>/dev/null || echo "-")

  if [ "$status" != "running" ]; then
    rows+=("| ${service} | ${status} | ${oom} | ${restarts} | ${mem} | - |")
    continue
  fi

  gc_forced=$(curl -s "${curl_auth[@]}" -o /dev/null -w '%{http_code}' "$base/heapdump")

  live=$(metric "$base" jvm.gc.live.data.size)
  max=$(metric "$base" jvm.gc.max.data.size)
  if [ -z "$live" ] || [ -z "$max" ]; then
    failures+=("${service}: could not read GC metrics from ${base}/metrics")
    live_cell="unreadable"
  elif awk -v l="$live" 'BEGIN { exit !(l == 0) }'; then
    live_cell="no major GC yet (heapdump HTTP ${gc_forced})"
  else
    pct=$(awk -v l="$live" -v m="$max" 'BEGIN { printf "%.0f", 100 * l / m }')
    live_cell="$(mib "$live") / $(mib "$max") MiB (${pct}%)"
    if [ "$pct" -gt "$MAX_LIVE_DATA_PCT" ]; then
      failures+=("${service}: live data ${pct}% of old gen exceeds ${MAX_LIVE_DATA_PCT}%")
    fi
  fi
  rows+=("| ${service} | ${status} | ${oom} | ${restarts} | ${mem} | ${live_cell} |")
done

report=$(
  echo "### JVM memory health (live-data ceiling ${MAX_LIVE_DATA_PCT}%)"
  echo
  echo "| Service | Status | OOM-killed | Restarts | Container memory | Old-gen live data after GC |"
  echo "|---|---|---|---|---|---|"
  printf '%s\n' "${rows[@]}"
)
echo "$report"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "$report" >>"$GITHUB_STEP_SUMMARY"

if [ ${#failures[@]} -gt 0 ]; then
  echo
  printf 'FAIL %s\n' "${failures[@]}" >&2
  exit 1
fi
echo
echo "All JVM services healthy."
