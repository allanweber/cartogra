#!/bin/sh
# Installs both charts on a local k3d cluster, seeds Acme, checks NetworkPolicy, guest mode, the ownership flow,
# team save, the team-member access check, drift accept and alerting. Writes every check to REPORT (build/local-test-report.html).
#
#   sh infra/helm/local-test.sh              fresh cluster: deletes CLUSTER first, tests bootstrap and seed from nothing
#   sh infra/helm/local-test.sh --reuse      keeps the cluster and its data, rolls out the new images
#   sh infra/helm/local-test.sh --no-build   skips docker build, uses the images already built (combines with --reuse)
#   sh infra/helm/local-test.sh --down       deletes the cluster and its data, leaves the docker images
#   sh infra/helm/local-test.sh --e2e        also runs the Playwright suite against the cluster, one report line per spec file
#
# Not covered: GHCR push, deploy.yml, sealed secrets, TLS, S3 upload, GitHub mirror scan, alert email delivery, sizing on the 8GB VM.
set -eu

CLUSTER="${CLUSTER:-cartogra-local}"
PORT="${PORT:-8088}"
URL="http://localhost:$PORT"
ADMIN_PASSWORD=k3d-Admin-pw1
DB_PASSWORD=k3d-test-pw
SERVICES="gateway registry topology contract ingestion frontend"

REUSE=false
BUILD=true
DOWN=false
E2E=false
for arg in "$@"; do
  case "$arg" in
    --reuse) REUSE=true ;;
    --e2e) E2E=true ;;
    --no-build) BUILD=false ;;
    --down) DOWN=true ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

cd "$(dirname "$0")/../.."

# One run at a time on this machine: every worktree shares CLUSTER, PORT and the cartogra-<svc>:k3d image tags,
# and a fresh run deletes CLUSTER first. The lock lives in the git dir that all worktrees share and is released
# when this shell exits. A caller that already holds it (.claude/skills/task-eval/k3d-run.sh) sets CARTOGRA_K3D_LOCKED=1.
if [ "${CARTOGRA_K3D_LOCKED:-}" != 1 ]; then
  command -v flock >/dev/null || { echo "flock is required" >&2; exit 2; }
  LOCK="$(git rev-parse --path-format=absolute --git-common-dir)/cartogra-k3d.lock"
  exec 9>"$LOCK"
  if ! flock -n 9; then
    echo "waiting for the k3d lock, held by: $(cat "$LOCK.owner" 2>/dev/null || echo unknown)"
    flock 9
  fi
  printf '%s %s %s\n' "$(git branch --show-current)" "$(pwd)" "$(date -Is)" >"$LOCK.owner"
  export CARTOGRA_K3D_LOCKED=1
fi

REPORT="${REPORT:-build/local-test-report.html}"
CHECK_LOG="$REPORT.log"
PASSED=0
CURRENT_STEP=
CLEANUP=:

step() {
  CURRENT_STEP=$1
  printf '\n==> %s\n' "$1"
  printf 'S\t%s\t%s\n' "$(date +%s)" "$1" >>"$CHECK_LOG"
}
fail() {
  printf 'FAILED: %s\n' "$1" >&2
  printf 'F\t%s\t%s\n' "$(date +%s)" "$1" >>"$CHECK_LOG"
  exit 1
}
pass() {
  PASSED=$((PASSED + 1))
  printf 'ok: %s\n' "$1"
  printf 'P\t%s\t%s\n' "$(date +%s)" "$1" >>"$CHECK_LOG"
}

# Cleanups run last-in first-out on exit, before the report gets its result.
on_exit() { CLEANUP="$1; $CLEANUP"; }

finish() {
  rc=$?
  set +e
  eval "$CLEANUP"
  awk -v rc="$rc" -v passed="$PASSED" -v started="$STARTED" -v finished="$(date +%s)" \
    -v start_text="$STARTED_TEXT" -v args="$ARGS" -v cluster="$CLUSTER" -v commit="$COMMIT" \
    -f infra/helm/local-test-report.awk "$CHECK_LOG" >"$REPORT"
  rm -f "$CHECK_LOG"
  printf '\nreport: %s\n' "$REPORT"
}

cluster_exists() { k3d cluster get "$CLUSTER" >/dev/null 2>&1; }

# Polls a predicate every 2s, then reports it as passed or fails the run.
wait_for() {
  what=$1
  seconds=$2
  shift 2
  elapsed=0
  until "$@"; do
    elapsed=$((elapsed + 2))
    [ "$elapsed" -lt "$seconds" ] || fail "timed out after ${seconds}s: $what"
    sleep 2
  done
  pass "$what"
}

# Passes only on a definite no (1), so a failed request (2) never reads as "absent".
absent() {
  rc=0
  "$@" || rc=$?
  [ "$rc" -eq 1 ]
}

if [ "$DOWN" = true ]; then
  if cluster_exists; then
    k3d cluster delete "$CLUSTER"
  else
    echo "no cluster named $CLUSTER"
  fi
  exit 0
fi

command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
STARTED=$(date +%s)
STARTED_TEXT=$(date +'%Y-%m-%d %H:%M')
ARGS="$*"
COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)$(git diff --quiet HEAD 2>/dev/null || echo ' + local changes')"
mkdir -p "$(dirname "$REPORT")"
: >"$CHECK_LOG"
trap finish EXIT

step "Cluster $CLUSTER"
if [ "$REUSE" = false ] && cluster_exists; then
  k3d cluster delete "$CLUSTER"
fi
if ! cluster_exists; then
  # Leaves ~/.kube/config and the current context alone.
  k3d cluster create "$CLUSTER" --agents 0 -p "$PORT:80@loadbalancer" --wait \
    --kubeconfig-update-default=false
fi
KUBECONFIG=$(k3d kubeconfig write "$CLUSTER")
export KUBECONFIG
kubectl get nodes

if [ "$BUILD" = true ]; then
  step "Build images"
  for s in $SERVICES; do
    echo "$s"
    docker build -q -f "infra/docker/$s/Dockerfile" -t "cartogra-$s:k3d" .
  done
  echo acme
  docker build -q -t cartogra-acme:k3d seed/acme-fintech
fi

step "Import images"
images=cartogra-acme:k3d
for s in $SERVICES; do images="$images cartogra-$s:k3d"; done
# shellcheck disable=SC2086
k3d image import -c "$CLUSTER" $images

step "Namespaces, RBAC and secrets"
kubectl apply -f infra/k8s/cluster-setup.yaml
key=$(head -c32 /dev/urandom | base64)
# Per secret, so a reused cluster picks up a service added since it was created. A secret from before the
# RLS roles (no CARTOGRA_APP_PASSWORD / FLYWAY_USERNAME key) is created again.
internal_token_key=k3d-internal-token-key-at-least-32-bytes
for s in gateway registry topology contract ingestion postgres; do
  key_check=FLYWAY_USERNAME
  db_user=cartogra_app db_pw=k3d-app-pw
  if [ "$s" = postgres ]; then key_check=CARTOGRA_APP_PASSWORD db_user=cartogra db_pw="$DB_PASSWORD"; fi
  if kubectl -n cartogra get secret "$s-secrets" -o "jsonpath={.data.$key_check}" 2>/dev/null | grep -q .; then
    # A cluster created before INTERNAL_TOKEN_KEY existed gets the key added.
    kubectl -n cartogra get secret "$s-secrets" -o jsonpath='{.data.INTERNAL_TOKEN_KEY}' | grep -q . \
      || kubectl -n cartogra patch secret "$s-secrets" --type merge \
        -p "{\"stringData\":{\"INTERNAL_TOKEN_KEY\":\"$internal_token_key\"}}"
    continue
  fi
  kubectl -n cartogra delete secret "$s-secrets" --ignore-not-found >/dev/null
  kubectl -n cartogra create secret generic "$s-secrets" \
    --from-literal=DB_USERNAME="$db_user" \
    --from-literal=DB_PASSWORD="$db_pw" \
    --from-literal=FLYWAY_USERNAME=cartogra_owner \
    --from-literal=FLYWAY_PASSWORD=k3d-owner-pw \
    --from-literal=CARTOGRA_OWNER_PASSWORD=k3d-owner-pw \
    --from-literal=CARTOGRA_APP_PASSWORD=k3d-app-pw \
    --from-literal=JWT_SECRET=k3d-test-secret-at-least-32-bytes-long \
    --from-literal=INTERNAL_TOKEN_KEY="$internal_token_key" \
    --from-literal=RESEND_API_KEY=re_placeholder \
    --from-literal=GOOGLE_CLIENT_SECRET=x \
    --from-literal=GITHUB_CLIENT_SECRET=x \
    --from-literal=CLUSTER_CREDENTIALS_KEY="$key" \
    --from-literal=BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PASSWORD"
done
kubectl -n acme-system get secret acme-bootstrap >/dev/null 2>&1 \
  || kubectl -n acme-system create secret generic acme-bootstrap --from-literal=ACME_PASSWORD="$ADMIN_PASSWORD"

# A reused cluster keeps its Postgres volume, and the init script runs only on an empty volume.
if kubectl -n cartogra get pod postgres-0 >/dev/null 2>&1; then
  step "Create the database roles on the existing volume"
  kubectl -n cartogra exec -i postgres-0 -- sh -c 'psql -v ON_ERROR_STOP=1 -U "$(cat /etc/cartogra/secrets/DB_USERNAME)" -d cartogra \
    -v owner_password=k3d-owner-pw -v app_password=k3d-app-pw -f -' \
    < infra/helm/cartogra/charts/postgres/files/bootstrap-roles.sql
fi

step "Install cartogra (gateway creates the tenant and admin)"
helm upgrade --install cartogra infra/helm/cartogra -n cartogra \
  -f infra/helm/cartogra/values-production.yaml \
  --set global.imageRegistry= \
  --set global.imageTag=k3d \
  --set global.host=localhost \
  --set ingress.clusterIssuer= \
  --set backup.enabled=false \
  --set alerting.email.to=alerts@k3d.invalid \
  --set gateway.env.RESEND_TEST_MODE=true \
  --set topology.env.TOPOLOGY_DRIFT_SWEEP_INTERVAL=PT15S \
  --wait --timeout 10m

if [ "$REUSE" = true ]; then
  step "Roll out the new images (the k3d tag is reused, so Helm sees no change)"
  kubectl -n cartogra rollout restart deploy
  for s in $SERVICES; do
    kubectl -n cartogra rollout status "deploy/$s" --timeout=10m
  done
fi

step "Install acme (bootstrap Job registers the cluster and seeds)"
if ! helm upgrade --install acme infra/helm/acme -n acme-system \
  --set image.registry= \
  --set image.tag=k3d \
  --wait --timeout 20m; then
  kubectl -n acme-system logs job/acme-bootstrap --tail=50 || true
  fail "acme install"
fi

step "Check pods"
kubectl -n cartogra get pods
running=$(kubectl -n acme-fintech get pods --no-headers | grep -c Running || true)
[ "$running" -eq 21 ] || fail "expected 21 Acme pods Running, got $running"
pass "21 Acme pods Running"

step "Check NetworkPolicy"
# One pod: the open gateway must answer (proves the pod runs), the backends must not.
out=$(kubectl -n cartogra run np-check --rm -i --restart=Never --quiet --image=curlimages/curl:8.10.1 -- sh -c '
  curl -fsm 5 -o /dev/null http://gateway:8080/actuator/health/live && echo gateway-open
  for target in registry:8081 topology:8082 contract:8083 ingestion:8085; do
    curl -sm 3 -o /dev/null "http://$target/" && echo "$target-open"
  done
  true' 2>&1) || true
echo "$out" | grep -q gateway-open || fail "probe pod could not reach the gateway: $out"
leaked=$(echo "$out" | grep -- '-open' | grep -v gateway-open || true)
[ -z "$leaked" ] || fail "backends reachable from a non-gateway pod: $leaked"
pass "backends reachable only from allowed pods"

step "Check guest mode"
tries=0
until curl -fs "$URL/api/auth/features" >/dev/null; do
  tries=$((tries + 1))
  [ "$tries" -lt 30 ] || fail "$URL/api/auth/features not reachable"
  sleep 2
done
features=$(curl -fs "$URL/api/auth/features")
echo "$features" | grep -q '"guestEnabled":true' || fail "guest mode off: $features"
echo "$features" | grep -q '"signupEnabled":false' || fail "signup on: $features"
pass "guest on, signup off"

jar=$(mktemp)
on_exit 'rm -f "$jar"'
curl -fs -o /dev/null -c "$jar" -X POST "$URL/api/auth/guest" || fail "guest login"
pass "guest login"

code=$(curl -s -o /dev/null -w '%{http_code}' -b "$jar" "$URL/api/v1/registry/services")
[ "$code" = 200 ] || fail "guest read returned $code, expected 200"
pass "guest read 200"

code=$(curl -s -o /dev/null -w '%{http_code}' -b "$jar" -X POST "$URL/api/v1/registry/services")
[ "$code" = 403 ] || fail "guest write returned $code, expected 403"
pass "guest write 403"

code=$(curl -s -o /dev/null -w '%{http_code}' -b "$jar" "$URL/api/auth/users")
[ "$code" = 403 ] || fail "guest user directory returned $code, expected 403"
pass "guest user directory 403"

audit=$(curl -fs -b "$jar" "$URL/api/v1/registry/audit-events?limit=200") || fail "guest audit read"
echo "$audit" | grep -q '"items":\[{' || fail "audit feed is empty, redaction not exercised"
if echo "$audit" | grep -q '"actorId":"'; then fail "audit feed exposes actorId to a guest"; fi
if echo "$audit" | grep -q '@acme-fintech'; then fail "audit feed exposes an email to a guest"; fi
pass "audit feed has no actor ids or emails"

step "Check ownership flow"
CHECK_NAMESPACE=acme-fintech
CHECK_SERVICE=ownership-check
ACME_ADMIN_EMAIL=admin@acme-fintech.invalid
SERVICES_API="$URL/api/v1/registry/services"
# A cluster re-sync can take this long if the watch misses the new Service.
DISCOVERY_TIMEOUT=360
# The watch removes it in seconds; a missed DELETE waits for the next re-sync.
REMOVAL_TIMEOUT=360
RISK_TIMEOUT=60
REGISTRY_TIMEOUT=10

token=$(curl -fs -X POST -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ACME_ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" \
  "$URL/api/auth/login" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p') || true
[ -n "$token" ] || fail "admin login"
pass "admin login"

admin() { curl -fsS -H "Authorization: Bearer $token" "$@"; }

# Splits a page into its flat JSON objects, then reads the id of the one with this name.
check_service_id() {
  body=$(admin "$SERVICES_API?source=kubernetes&limit=200") || return 2
  echo "$body" | grep -o '{[^{}]*}' | grep "\"name\":\"$CHECK_SERVICE\"" \
    | sed -n 's/.*"id":"\([^"]*\)".*/\1/p'
}

remove_check_service() {
  kubectl -n "$CHECK_NAMESPACE" delete service "$CHECK_SERVICE" --ignore-not-found >/dev/null 2>&1 || true
  leftover=$(check_service_id) || leftover=""
  [ -z "$leftover" ] || admin -X DELETE "$SERVICES_API/$leftover" >/dev/null || true
}

service_discovered() {
  service_id=$(check_service_id) || return 2
  [ -n "$service_id" ]
}

owner_of_service() {
  body=$(admin "$SERVICES_API/$service_id") || return 2
  case "$body" in *'"teamId":"'*) ;; *) echo none; return 0 ;; esac
  echo "$body" | sed -n 's/.*"teamId":"\([^"]*\)".*/\1/p'
}

# Returns 2 on a failed request or a response with no items, so neither reads as "absent".
page_has_id() {
  body=$(admin "$1") || return 2
  case "$body" in *'"items":'*) ;; *) return 2 ;; esac
  echo "$body" | grep -q "\"id\":\"$2\""
}

in_orphan_list() { page_has_id "$SERVICES_API/orphaned?limit=200" "$service_id"; }

has_orphan_risk() { page_has_id "$URL/api/v1/topology/risks?limit=1000" "orphan:$service_id"; }

not_in_cluster() {
  body=$(admin "$SERVICES_API/$service_id") || return 2
  case "$body" in *'"k8sCluster":"'*) return 1 ;; *) return 0 ;; esac
}

on_exit remove_check_service
# An earlier run killed by a signal skips the EXIT trap.
remove_check_service

team_id=$(admin "$URL/api/v1/registry/teams?limit=1" | grep -o '"id":"[^"]*"' | head -n 1 | cut -d'"' -f4) || true
[ -n "$team_id" ] || fail "no Acme team to assign"

kubectl -n "$CHECK_NAMESPACE" create service clusterip "$CHECK_SERVICE" --tcp=8080:8080 >/dev/null
wait_for "new cluster Service discovered" "$DISCOVERY_TIMEOUT" service_discovered
owner=$(owner_of_service) || fail "read service"
[ "$owner" = none ] || fail "discovered service already owned by $owner"
wait_for "unowned service in orphan list" "$REGISTRY_TIMEOUT" in_orphan_list
wait_for "unowned service in risks" "$RISK_TIMEOUT" has_orphan_risk

admin -X PATCH -H 'Content-Type: application/json' -d "{\"teamId\":\"$team_id\"}" \
  "$SERVICES_API/$service_id/owner" >/dev/null || fail "assign team"
owner=$(owner_of_service) || fail "read service"
[ "$owner" = "$team_id" ] || fail "after reload owner is $owner, expected $team_id"
pass "assigned team saved after reload"
wait_for "owned service left orphan list" "$REGISTRY_TIMEOUT" absent in_orphan_list
wait_for "owned service left risks" "$RISK_TIMEOUT" absent has_orphan_risk

admin -X DELETE "$SERVICES_API/$service_id/owner" >/dev/null || fail "remove team"
owner=$(owner_of_service) || fail "read service"
[ "$owner" = none ] || fail "after removal owner is $owner"
pass "team removal saved after reload"
wait_for "unowned service back in orphan list" "$REGISTRY_TIMEOUT" in_orphan_list
wait_for "unowned service back in risks" "$RISK_TIMEOUT" has_orphan_risk

# A Service deleted from the cluster keeps its catalog row and owner, but loses its cluster.
admin -X PATCH -H 'Content-Type: application/json' -d "{\"teamId\":\"$team_id\"}" \
  "$SERVICES_API/$service_id/owner" >/dev/null || fail "assign team before removal"
kubectl -n "$CHECK_NAMESPACE" delete service "$CHECK_SERVICE" >/dev/null
wait_for "removed Service has no cluster" "$REMOVAL_TIMEOUT" not_in_cluster
owner=$(owner_of_service) || fail "read service after removal"
[ "$owner" = "$team_id" ] || fail "after removal from the cluster owner is $owner, expected $team_id"
pass "removed Service keeps its row and owner"

remove_check_service
wait_for "check service removed from catalog" "$REGISTRY_TIMEOUT" absent in_orphan_list

step "Check 5.3 breaking-change engine"
CONTRACTS_API="$URL/api/v1/contracts"
compat_service=$(admin -X POST -H 'Content-Type: application/json' \
  -d "{\"name\":\"compat-check-$(date +%s)\"}" "$SERVICES_API" | jq -r '.data.id // empty') || true
[ -n "$compat_service" ] || fail "register throwaway service"
on_exit "admin -X DELETE '$SERVICES_API/$compat_service' >/dev/null 2>&1 || true"

compat_spec() {
  printf 'openapi: 3.0.3\ninfo: {title: compat-check, version: %s}\npaths:\n  /entries:\n    get:\n      responses:\n        "200": {description: ok}\n%s' "$1" "$2"
}
COMPAT_REMOVED_PATH='  /entries/{id}:
    get:
      parameters:
        - {name: id, in: path, required: true, schema: {type: string}}
      responses:
        "200": {description: ok}
'
compat_id=$(jq -n --arg s "$compat_service" --arg spec "$(compat_spec 1.0.0 "$COMPAT_REMOVED_PATH")" \
  '{serviceId: $s, spec: $spec}' | admin -X POST -H 'Content-Type: application/json' -d @- "$CONTRACTS_API" \
  | jq -r '.data.id // empty') || true
[ -n "$compat_id" ] || fail "create contract v1"
v2_breaking=$(jq -n --arg spec "$(compat_spec 2.0.0 '')" '{spec: $spec}' \
  | admin -X POST -H 'Content-Type: application/json' -d @- "$CONTRACTS_API/$compat_id/versions" \
  | jq -r '.data.breaking') || true
[ "$v2_breaking" = true ] || fail "v2 with a removed path stored breaking=$v2_breaking"
pass "v2 with a removed path stored as breaking"

admin "$CONTRACTS_API/$compat_id/versions/2/diff" \
  | jq -e '.data.compatibility.isBreaking == true
      and any(.data.compatibility.changes[]; .rule == "path-removed" and .path == "/entries/{id}")' >/dev/null \
  || fail "diff compatibility has no path-removed"
pass "diff compatibility isBreaking with path-removed"

admin "$CONTRACTS_API?status=breaking&limit=100" | jq -e --arg id "$compat_id" 'any(.data.items[]; .id == $id)' >/dev/null \
  || fail "status=breaking does not list the contract"
admin "$CONTRACTS_API?status=compatible&limit=100" | jq -e --arg id "$compat_id" 'all(.data.items[]; .id != $id)' >/dev/null \
  || fail "status=compatible lists the breaking contract"
pass "status filter lists the contract only under breaking"

bad_status=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "$CONTRACTS_API?status=nope")
[ "$bad_status" = 400 ] || fail "status=nope returned $bad_status"
pass "status=nope 400"

# Reads v2 by its number: a --reuse run finds the v3 that the seed step below publishes.
ledger_contract=$(admin "$CONTRACTS_API?q=ledger-service&limit=100" \
  | jq -r '.data.items[] | select(.serviceName == "ledger-service") | .id') || true
[ -n "$ledger_contract" ] || fail "seeded ledger-service contract not found"
ledger_breaking=$(admin "$CONTRACTS_API/$ledger_contract/versions/2/diff" | jq -r '.data.to.breaking') || true
[ "$ledger_breaking" = false ] || fail "seeded ledger-service v2 breaking=$ledger_breaking"
pass "seeded ledger-service v2 is compatible"

# The seed CLI runs on the host against the k3d gateway, as the acme admin that the bootstrap Job uses.
acme() {
  (cd seed/acme-fintech && ACME_API_URL="$URL" ACME_EMAIL="$ACME_ADMIN_EMAIL" ACME_PASSWORD="$ADMIN_PASSWORD" \
    ACME_TENANT_ID=a0000000-0000-4000-8000-00000000ac3e node src/cli.ts "$@")
}
acme load --breaking || fail "acme load --breaking"
pass "acme load --breaking"
acme verify --breaking --no-spans || fail "acme verify --breaking"
pass "acme verify --breaking"

step "Check team save, team-member access check and drift accept"
TEAMS_API="$URL/api/v1/registry/teams"
TOPOLOGY_API="$URL/api/v1/topology"
INVITE_TIMEOUT=30
SWEEP_TIMEOUT=90
run_id=$(date +%s)
member_email="local-test-$run_id@k3d.invalid"
member_password=k3d-Member-pw1

admin -X POST -H 'Content-Type: application/json' -d "{\"email\":\"$member_email\",\"role\":\"MEMBER\"}" \
  "$URL/api/auth/admin/invite" >/dev/null || fail "invite member"

# RESEND_TEST_MODE logs the invite token instead of mailing it.
invite_logged() {
  invite_token=$(kubectl -n cartogra logs deploy/gateway --since=10m \
    | grep "Invite for $member_email" | tail -n 1 | sed -n 's/.*token: \([A-Za-z0-9_-]*\).*/\1/p')
  [ -n "$invite_token" ]
}
wait_for "invite token logged" "$INVITE_TIMEOUT" invite_logged

member_token=$(curl -fsS -X POST -H 'Content-Type: application/json' \
  -d "{\"token\":\"$invite_token\",\"password\":\"$member_password\"}" \
  "$URL/api/auth/accept-invite" | jq -r '.data.accessToken // empty') || true
[ -n "$member_token" ] || fail "accept invite"
member_id=$(admin "$URL/api/auth/admin/users" | jq -r --arg e "$member_email" '.data[] | select(.email == $e) | .id')
[ -n "$member_id" ] || fail "invited member not listed"
on_exit "admin -X POST '$URL/api/auth/admin/users/$member_id/disable' >/dev/null"
pass "MEMBER user created through invite"

member() { curl -fsS -H "Authorization: Bearer $member_token" "$@"; }

# A pair both declared and observed, so removing the declaration raises an UNDECLARED drift.
pairs() { admin "$TOPOLOGY_API/graph?type=$1" | jq -r '.data.edges[] | "\(.source) \(.target)"' | sort -u >"$2"; }
pairs DECLARED "$REPORT.declared" || fail "read declared graph"
pairs OBSERVED "$REPORT.observed" || fail "read observed graph"
pair=$(comm -12 "$REPORT.declared" "$REPORT.observed" | head -n 1)
rm -f "$REPORT.declared" "$REPORT.observed"
[ -n "$pair" ] || fail "no Acme pair is both declared and observed"
source_id=${pair% *}
target_id=${pair#* }
original_team=$(admin "$SERVICES_API/$source_id" | jq -r '.data.teamId // empty')

team_id_new=$(admin -X POST -H 'Content-Type: application/json' -d "{\"name\":\"local-test-$run_id\"}" "$TEAMS_API" \
  | jq -r '.data.id // empty') || true
[ -n "$team_id_new" ] || fail "create team"
on_exit "admin -X DELETE '$TEAMS_API/$team_id_new' >/dev/null"
if [ -n "$original_team" ]; then
  on_exit "admin -X PATCH -H 'Content-Type: application/json' -d '{\"teamId\":\"$original_team\"}' '$SERVICES_API/$source_id/owner' >/dev/null"
else
  on_exit "admin -X DELETE '$SERVICES_API/$source_id/owner' >/dev/null"
fi

admin -X PUT -H 'Content-Type: application/json' \
  -d "{\"name\":\"local-test-$run_id\",\"serviceIds\":[\"$source_id\"],\"memberIds\":[\"$member_id\"]}" \
  "$TEAMS_API/$team_id_new" >/dev/null || fail "save team with one PUT"
owner=$(admin "$SERVICES_API/$source_id" | jq -r '.data.teamId')
[ "$owner" = "$team_id_new" ] || fail "after team save the service owner is $owner, expected $team_id_new"
admin "$TEAMS_API/$team_id_new/members" | jq -e --arg u "$member_id" '.data | map(.userId) == [$u]' >/dev/null \
  || fail "after team save the members are not exactly the invited user"
pass "team saved with one PUT: service and member stored"

code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $token" \
  -H 'Content-Type: application/json' -d "{\"name\":\"local-test-$run_id\"}" "$TEAMS_API/$team_id_new")
[ "$code" = 400 ] || fail "team save without lists returned $code, expected 400"
pass "team save without lists 400"

declared=$(admin "$TOPOLOGY_API/services/$source_id/dependencies" \
  | jq -r --arg t "$target_id" '.data.downstream[] | select(.serviceId == $t) | "\(.id) \(.protocol)"')
[ -n "$declared" ] || fail "declared dependency $source_id -> $target_id not listed"
# Puts the original declarations back on exit; one already re-declared by the drift accept answers 409.
redeclare_file="$REPORT.redeclare"
echo "$declared" | while read -r _ protocol; do
  printf '{"sourceServiceId":"%s","targetServiceId":"%s","protocol":"%s"}\n' "$source_id" "$target_id" "$protocol"
done >"$redeclare_file"
on_exit "while read -r b; do admin -X POST -H 'Content-Type: application/json' -d \"\$b\" '$TOPOLOGY_API/dependencies' >/dev/null 2>&1; done <'$redeclare_file'; rm -f '$redeclare_file'"
for dep_id in $(echo "$declared" | cut -d' ' -f1); do
  admin -X DELETE "$TOPOLOGY_API/dependencies/$dep_id" >/dev/null || fail "remove declared dependency"
done

undeclared_drift() {
  drift_id=$(admin "$TOPOLOGY_API/drifts?limit=200" | jq -r --arg s "$source_id" --arg t "$target_id" \
    '.data.items[] | select(.type == "UNDECLARED" and .sourceServiceId == $s and .targetServiceId == $t) | .id') || return 2
  [ -n "$drift_id" ]
}
wait_for "UNDECLARED drift raised (a dismissed drift on this pair would block it)" "$SWEEP_TIMEOUT" undeclared_drift

member -X POST -H 'Content-Type: application/json' -d '{"action":"ACCEPT_OBSERVED"}' \
  "$TOPOLOGY_API/drifts/$drift_id/resolve" >/dev/null || fail "team member could not accept the drift"
pass "team member accepted the drift (registry access check)"

admin "$TOPOLOGY_API/services/$source_id/dependencies" \
  | jq -e --arg t "$target_id" '[.data.downstream[] | select(.serviceId == $t)] | length > 0' >/dev/null \
  || fail "accepted drift did not declare $source_id -> $target_id"
pass "accepted drift declared the observed pair"

step "Check alerting (the gateway exports its metrics once a minute)"
query='http://localhost:9090/api/v1/query?query=cartogra_guest_mints_total%7Boutcome%3D%22issued%22%7D'
tries=0
until kubectl -n cartogra exec deploy/alerting -c prometheus -- wget -qO- "$query" | grep -q '"outcome":"issued"'; do
  tries=$((tries + 1))
  [ "$tries" -lt 30 ] || fail "guest mint counter never reached Prometheus"
  sleep 5
done
pass "guest mint counter reached Prometheus"

step "Check capacity metrics (kubelet scrape, restart detection)"
prom() { kubectl -n cartogra exec deploy/alerting -c prometheus -- wget -qO- "http://localhost:9090/api/v1/query?query=$1"; }
tries=0
until prom 'machine_memory_bytes' | grep -q '"value"'; do
  tries=$((tries + 1))
  [ "$tries" -lt 30 ] || fail "kubelet metrics never reached Prometheus (ClusterRole cartogra-node-metrics?)"
  sleep 5
done
pass "kubelet metrics reached Prometheus"
kubectl -n cartogra delete pod oom-check --ignore-not-found --wait >/dev/null
kubectl -n cartogra apply -f - >/dev/null <<'YAML'
apiVersion: v1
kind: Pod
metadata:
  name: oom-check
spec:
  containers:
    - name: oom
      image: busybox:1.36
      command: [sh, -c, "sleep 45; head -c 128m /dev/zero | tail"]
      resources:
        requests: { cpu: 10m, memory: 16Mi }
        limits: { cpu: 50m, memory: 16Mi }
YAML
tries=0
until prom 'ALERTS%7Balertname%3D%22ContainerRestarted%22%2Cpod%3D%22oom-check%22%7D' | grep -q '"oom-check"'; do
  tries=$((tries + 1))
  [ "$tries" -lt 72 ] || fail "ContainerRestarted never fired for the OOM-killed oom-check pod"
  sleep 5
done
kubectl -n cartogra delete pod oom-check --wait=false >/dev/null
pass "ContainerRestarted fired for an OOM-killed pod"

if [ "$E2E" = true ]; then
  step "E2E Playwright"
  # The suite signs up its own tenant, and the production values turn sign-up off. Guest mode was checked
  # above with sign-up off, so turn it on only now, for the suite.
  kubectl -n cartogra set env deploy/gateway SIGNUP_ENABLED=true >/dev/null
  kubectl -n cartogra rollout status deploy/gateway --timeout=5m >/dev/null || fail "gateway did not roll out with sign-up on"
  # The old pod keeps getting requests for a few seconds while it stops.
  tries=0
  until curl -fs "$URL/api/auth/features" | grep -q '"signupEnabled":true'; do
    tries=$((tries + 1))
    [ "$tries" -lt 30 ] || fail "sign-up still off for the e2e suite: $(curl -s "$URL/api/auth/features")"
    sleep 2
  done
  pass "sign-up on for the e2e suite"
  # The suite reads verification tokens straight from Postgres: point it at the cluster's database,
  # never at the persistent dev stack on :5436 / :8080.
  kubectl -n cartogra port-forward svc/postgres 5437:5432 >/dev/null 2>&1 &
  pf=$!
  on_exit "kill $pf 2>/dev/null"
  # demo-flow posts OTLP spans; 4319, so a compose collector on :4318 is never hit.
  kubectl -n cartogra port-forward svc/collector 4319:4318 >/dev/null 2>&1 &
  pf_otlp=$!
  on_exit "kill $pf_otlp 2>/dev/null"
  sleep 3
  results=$(mktemp)
  on_exit "rm -f '$results'"
  e2e_rc=0
  # guest-demo writes activity into the guest tenant, which is Acme on this cluster (values-production.yaml).
  (cd frontend && E2E_BASE_URL="$URL" E2E_API_BASE_URL="$URL" \
    E2E_DB_URL="postgresql://cartogra:$DB_PASSWORD@localhost:5437/cartogra" \
    E2E_OTLP_TRACES_URL=http://localhost:4319/v1/traces \
    E2E_GUEST_TENANT_ID=a0000000-0000-4000-8000-00000000ac3e \
    PLAYWRIGHT_JSON_OUTPUT_NAME="$results" PLAYWRIGHT_HTML_OPEN=never \
    pnpm exec playwright test --reporter=list,html,json) || e2e_rc=$?
  [ -s "$results" ] || fail "Playwright wrote no results (exit $e2e_rc)"
  e2e_failed=0
  for line in $(jq -r '.suites[] | "\(.file)=\([.. | objects | select(has("ok")) | .ok] | all)"' "$results"); do
    if [ "${line##*=}" = true ]; then
      pass "e2e ${line%=*}"
    else
      printf 'F\t%s\t%s\n' "$(date +%s)" "e2e ${line%=*}" >>"$CHECK_LOG"
      e2e_failed=$((e2e_failed + 1))
    fi
  done
  [ "$e2e_failed" -gt 0 ] && fail "e2e: $e2e_failed spec file(s) failed, see frontend/playwright-report/index.html"
  [ "$e2e_rc" -eq 0 ] || fail "e2e: Playwright exited $e2e_rc before or after the specs (global setup or teardown), see the log"
fi

printf '\nAll checks passed. Open %s/demo\nkubectl: export KUBECONFIG=%s\n' "$URL" "$KUBECONFIG"
