#!/usr/bin/env bash
# Registers, verifies and logs in a throwaway tenant on the gateway of a local compose stack.
# Prints KEY=value lines (COOKIE_JAR, COOKIE, TENANT_ID) — append to $GITHUB_ENV or eval them.
set -euo pipefail

GATEWAY_URL=${GATEWAY_URL:-http://localhost:8080}
PG_CONTAINER=${PG_CONTAINER:-cartogra-postgres}
COOKIE_JAR=${COOKIE_JAR:-$(mktemp)}

run_id=$(date +%s%N)
org_name="Perf Tenant ${run_id}"
email="perf-${run_id}@cartogra.test"
password='Cartogra-perf-1!'

psql_query() {
  docker exec "$PG_CONTAINER" psql -U cartogra -d cartogra -tAc "$1"
}

post_json() {
  curl -sSf -X POST "$GATEWAY_URL$1" -H 'Content-Type: application/json' -d "$2" "${@:3}" >/dev/null
}

post_json /api/auth/register "{\"orgName\":\"${org_name}\",\"email\":\"${email}\",\"password\":\"${password}\"}"

token=""
for _ in $(seq 1 20); do
  token=$(psql_query "SELECT email_verification_token FROM registry.users WHERE email = '${email}'")
  [ -n "$token" ] && break
  sleep 0.25
done
if [ -z "$token" ]; then
  echo "verification token for ${email} never appeared" >&2
  exit 1
fi

post_json /api/auth/verify "{\"email\":\"${email}\",\"token\":\"${token}\"}"
post_json /api/auth/login "{\"email\":\"${email}\",\"password\":\"${password}\"}" -c "$COOKIE_JAR"

cookie=$(awk 'BEGIN { ORS = "" } !/^#/ || /^#HttpOnly_/ { if (NF >= 7) { printf "%s%s=%s", sep, $6, $7; sep = "; " } }' "$COOKIE_JAR")
tenant_id=$(psql_query "SELECT tenant_id FROM registry.tenants WHERE name = '${org_name}'")

echo "COOKIE_JAR=${COOKIE_JAR}"
echo "COOKIE=${cookie}"
echo "TENANT_ID=${tenant_id}"
