#!/bin/sh
# Installs both charts on a local k3d cluster, seeds Acme and checks guest mode.
#
#   sh infra/helm/local-test.sh              fresh cluster: deletes CLUSTER first, tests bootstrap and seed from nothing
#   sh infra/helm/local-test.sh --reuse      keeps the cluster and its data, rolls out the new images
#   sh infra/helm/local-test.sh --no-build   skips docker build, uses the images already built (combines with --reuse)
#   sh infra/helm/local-test.sh --down       deletes the cluster and its data, leaves the docker images
#
# Not covered: GHCR push, deploy.yml, sealed secrets, TLS, S3 upload, GitHub mirror scan, sizing on the 8GB VM.
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

step "Namespaces and secrets"
kubectl create namespace cartogra --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace acme-system --dry-run=client -o yaml | kubectl apply -f -
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

printf '\nAll checks passed. Open %s/demo\nkubectl: export KUBECONFIG=%s\n' "$URL" "$KUBECONFIG"
