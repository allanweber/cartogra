# Cartogra Helm chart

Umbrella chart: `gateway`, `registry`, `topology`, `ingestion`, `frontend`, `collector`, plus single-replica `postgres`, `kafka` and `valkey` StatefulSets, and optional `alerting` (Prometheus + Alertmanager, on in the demo). `charts/common` is a library chart with the shared Deployment/Service/ConfigMap/PDB/HPA template.

Subcharts are rendered through the umbrella only. They call `common.*` templates without declaring the library as a dependency, which works because Helm shares named templates across a release.

## Values

| File | Use |
|---|---|
| `values.yaml` | Production shape: 3 replicas, PDB `minAvailable: 2`, HPA 3–6 (infra.md). |
| `values-demo.yaml` | Public demo on single-node k3s ([ADR-0030](../../../docs/adr/0030-demo-on-single-node-k3s.md)): 1 replica, no PDB/HPA, sized for 8GB. Guest mode on (Acme tenant), signup off. |

Required: `global.host` (ingress, OAuth redirects, CORS). CI sets `global.imageTag` to the commit SHA; `latest` is rejected. With `alerting.enabled`: `alerting.email.to` and `alerting.email.from`.

## Secrets

The chart creates no Secrets. Each one is mounted read-only at `/etc/cartogra/secrets`, and Spring reads the files through `spring.config.import=configtree:`. Create them from SealedSecrets:

| Secret | Keys |
|---|---|
| `gateway-secrets` | `DB_USERNAME` `DB_PASSWORD` `JWT_SECRET` `RESEND_API_KEY` `GOOGLE_CLIENT_SECRET` `GITHUB_CLIENT_SECRET`, plus `BOOTSTRAP_ADMIN_PASSWORD` when `BOOTSTRAP_TENANT_ID` is set (demo) |
| `registry-secrets`, `topology-secrets` | `DB_USERNAME` `DB_PASSWORD` |
| `ingestion-secrets` | `DB_USERNAME` `DB_PASSWORD` `CLUSTER_CREDENTIALS_KEY` |
| `postgres-secrets` | `DB_USERNAME` `DB_PASSWORD` (same values the services use) |

## Exposure

The ingress routes `/api` to the gateway and everything else to the frontend. The collector's OTLP ports stay ClusterIP-only: `cartogra.tenant.id` is unauthenticated until roadmap 5.9. Its metrics port (8889) is ClusterIP-only too.

`registry`, `topology` and `ingestion` trust the gateway's `X-Tenant-Id` / `X-User-*` headers, so each gets a `NetworkPolicy` (`networkPolicy.allowFrom` in its values) that admits only the listed `app` labels on its app port: `registry` ← gateway, ingestion, topology; `topology` and `ingestion` ← gateway. The gateway and frontend stay open for the ingress controller. A CNI that does not enforce NetworkPolicy ignores these silently; k3s enforces them.

## Alerting

`charts/alerting` runs Prometheus and Alertmanager in one pod with no Service or ingress. Prometheus scrapes `cartogra_*` metrics from the collector and evaluates `files/rules.yml`; Alertmanager emails through Resend SMTP using `RESEND_API_KEY` from `gateway-secrets`. Data is an `emptyDir`, so replacing the pod only resets rule windows and silences. Nothing alerts if this pod itself dies; the external uptime check doesn't cover it either.

It also scrapes the kubelet's cAdvisor endpoint (`nodeMetrics`) for the `capacity` rules: node memory > 85% and CPU > 80% (resize signals), a container > 90% of its memory limit, any container restart (OOM kills included), and `NodeMetricsMissing` when the scrape fails. That needs ClusterRole `cartogra-node-metrics` bound to ServiceAccount `alerting`, created by `infra/k8s/cluster-setup.yaml` (admin-applied, so CI can't grant it). On a cluster without it, set `alerting.nodeMetrics.enabled=false`.

```bash
kubectl -n cartogra port-forward deploy/alerting 9090 9093   # Prometheus and Alertmanager UIs
```

## Deviations from infra.md

- Third-party images run as their own non-root uid: Postgres 70, Valkey 999, collector 10001. Kafka and our images run as 1000.
- Prometheus and Alertmanager run as 1000 rather than their images' 65534, with `emptyDir` data.
- Kafka's config directory is an `emptyDir`, seeded by an init container, because the image's start script writes `server.properties` there and the root filesystem is read-only.

## Checks

```bash
helm lint infra/helm/cartogra --strict -f infra/helm/cartogra/values-demo.yaml --set global.imageTag=dev --set alerting.email.to=dev@example.invalid
helm template cartogra infra/helm/cartogra -f infra/helm/cartogra/values-demo.yaml --set global.imageTag=dev --set alerting.email.to=dev@example.invalid
```

CI runs both for the prod and demo values (`.github/workflows/helm.yml`).

## Test on a local cluster

See [`infra/helm/README.md`](../README.md).
