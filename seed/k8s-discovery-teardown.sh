#!/usr/bin/env bash
# K8s Discovery Teardown
#
# Removes all Trading-Journal test services and the `prod` namespace
# created by seed/k8s-discovery-manifests.yaml.
#
# Usage:
#   bash seed/k8s-discovery-teardown.sh
#
# To skip the namespace deletion (keep the namespace, remove only workloads):
#   bash seed/k8s-discovery-teardown.sh --keep-namespace

set -euo pipefail

KEEP_NAMESPACE=false
for arg in "$@"; do
  [[ "$arg" == "--keep-namespace" ]] && KEEP_NAMESPACE=true
done

NAMESPACE="prod"

SERVICES=(
  trade-service
  portfolio-service
  market-data-service
  notification-service
  analytics-service
  user-profile-service
  report-generator
  risk-engine
)

echo "==> Deleting Deployments and Services in namespace '${NAMESPACE}'..."
for svc in "${SERVICES[@]}"; do
  kubectl delete deployment "${svc}" -n "${NAMESPACE}" --ignore-not-found
  kubectl delete service    "${svc}" -n "${NAMESPACE}" --ignore-not-found
  echo "    removed: ${svc}"
done

if [[ "${KEEP_NAMESPACE}" == "true" ]]; then
  echo "==> Namespace '${NAMESPACE}' kept (--keep-namespace)."
else
  echo "==> Deleting namespace '${NAMESPACE}'..."
  kubectl delete namespace "${NAMESPACE}" --ignore-not-found
  echo "    namespace deleted."
fi

echo "==> Cleaning up test namespaces (no-tenant, late-adopter)..."
kubectl delete namespace no-tenant late-adopter --ignore-not-found

echo "==> Teardown complete."
