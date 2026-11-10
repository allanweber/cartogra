#!/bin/sh
# Installs both charts on a local k3d cluster, seeds Acme, checks NetworkPolicy, guest mode, the ownership flow and alerting.
#
#   sh infra/helm/local-test.sh              fresh cluster: deletes CLUSTER first, tests bootstrap and seed from nothing
#   sh infra/helm/local-test.sh --reuse      keeps the cluster and its data, rolls out the new images
#   sh infra/helm/local-test.sh --no-build   skips docker build, uses the images already built (combines with --reuse)
#   sh infra/helm/local-test.sh --down       deletes the cluster and its data, leaves the docker images
#
# Not covered: GHCR push, deploy.yml, sealed secrets, TLS, S3 upload, GitHub mirror scan, alert email delivery, sizing on the 8GB VM.
set -eu

CLUSTER="${CLUSTER:-cartogra-local}"
PORT="${PORT:-8088}"
URL="http://localhost:$PORT"
ADMIN_PASSWORD=k3d-Admin-pw1
SERVICES="gateway registry topology ingestion frontend"

REUSE=false
BUILD=true
DOWN=false
for arg in "$@"; do
  case "$arg" in
    --reuse) REUSE=true ;;
    --no-build) BUILD=false ;;
    --down) DOWN=true ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

cd "$(dirname "$0")/../.."

step() { printf '\n==> %s\n' "$1"; }
fail() { printf 'FAILED: %s\n' "$1" >&2; exit 1; }
pass() { printf 'ok: %s\n' "$1"; }

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
if ! kubectl -n cartogra get secret gateway-secrets >/dev/null 2>&1; then
  key=$(head -c32 /dev/urandom | base64)
  for s in gateway registry topology ingestion postgres; do
    kubectl -n cartogra create secret generic "$s-secrets" \
      --from-literal=DB_USERNAME=cartogra \
      --from-literal=DB_PASSWORD=k3d-test-pw \
      --from-literal=JWT_SECRET=k3d-test-secret-at-least-32-bytes-long \
      --from-literal=RESEND_API_KEY=re_placeholder \
      --from-literal=GOOGLE_CLIENT_SECRET=x \
      --from-literal=GITHUB_CLIENT_SECRET=x \
      --from-literal=CLUSTER_CREDENTIALS_KEY="$key" \
      --from-literal=BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PASSWORD"
  done
  kubectl -n acme-system create secret generic acme-bootstrap \
    --from-literal=ACME_PASSWORD="$ADMIN_PASSWORD"
fi

step "Install cartogra (gateway creates the tenant and admin)"
helm upgrade --install cartogra infra/helm/cartogra -n cartogra \
  -f infra/helm/cartogra/values-demo.yaml \
  --set global.imageRegistry= \
  --set global.imageTag=k3d \
  --set global.host=localhost \
  --set ingress.clusterIssuer= \
  --set backup.enabled=false \
  --set alerting.email.to=alerts@k3d.invalid \
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
  for target in registry:8081 topology:8082 ingestion:8085; do
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
trap 'rm -f "$jar"' EXIT
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

trap 'rm -f "$jar"; remove_check_service' EXIT
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

remove_check_service
wait_for "check service removed from catalog" "$REGISTRY_TIMEOUT" absent in_orphan_list

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

printf '\nAll checks passed. Open %s/demo\nkubectl: export KUBECONFIG=%s\n' "$URL" "$KUBECONFIG"
