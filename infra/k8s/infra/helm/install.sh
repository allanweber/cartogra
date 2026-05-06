#!/usr/bin/env bash
set -euo pipefail

helm repo add grafana https://grafana.github.io/helm-charts
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

kubectl apply -f infra/k8s/infra/namespace.yml

helm upgrade --install loki grafana/loki \
  --namespace infra \
  --version 6.29.0 \
  --values infra/k8s/infra/helm/loki-values.yml \
  --wait

helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace infra \
  --version 72.0.0 \
  --values infra/k8s/infra/helm/kube-prometheus-stack-values.yml \
  --set grafana.adminPassword="${GRAFANA_ADMIN_PASSWORD}" \
  --wait

helm upgrade --install tempo grafana/tempo \
  --namespace infra \
  --version 1.21.1 \
  --values infra/k8s/infra/helm/tempo-values.yml \
  --wait

kubectl apply -k infra/k8s/infra/otel-collector/
