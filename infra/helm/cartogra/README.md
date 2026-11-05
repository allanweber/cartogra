# Cartogra Helm chart

Umbrella chart: `gateway`, `registry`, `topology`, `ingestion`, `frontend`, `collector`, plus single-replica `postgres`, `kafka` and `valkey` StatefulSets. `charts/common` is a library chart with the shared Deployment/Service/ConfigMap/PDB/HPA template.

Subcharts are rendered through the umbrella only. They call `common.*` templates without declaring the library as a dependency, which works because Helm shares named templates across a release.

## Values

| File | Use |
|---|---|
| `values.yaml` | Production shape: 3 replicas, PDB `minAvailable: 2`, HPA 3–6 (infra.md). |
| `values-demo.yaml` | Public demo on single-node k3s ([ADR-0030](../../../docs/adr/0030-demo-on-single-node-k3s.md)): 1 replica, no PDB/HPA, sized for 8GB. Guest mode on (Acme tenant), signup off. |

Required: `global.host` (ingress, OAuth redirects, CORS). CI sets `global.imageTag` to the commit SHA; `latest` is rejected.

## Secrets

The chart creates no Secrets. Each one is mounted read-only at `/etc/cartogra/secrets`, and Spring reads the files through `spring.config.import=configtree:`. Create them from SealedSecrets:

| Secret | Keys |
|---|---|
| `gateway-secrets` | `DB_USERNAME` `DB_PASSWORD` `JWT_SECRET` `RESEND_API_KEY` `GOOGLE_CLIENT_SECRET` `GITHUB_CLIENT_SECRET`, plus `BOOTSTRAP_ADMIN_PASSWORD` when `BOOTSTRAP_TENANT_ID` is set (demo) |
| `registry-secrets`, `topology-secrets` | `DB_USERNAME` `DB_PASSWORD` |
| `ingestion-secrets` | `DB_USERNAME` `DB_PASSWORD` `CLUSTER_CREDENTIALS_KEY` |
| `postgres-secrets` | `DB_USERNAME` `DB_PASSWORD` (same values the services use) |

## Exposure

The ingress routes `/api` to the gateway and everything else to the frontend. The collector's OTLP ports stay ClusterIP-only: `cartogra.tenant.id` is unauthenticated until roadmap 5.9.

## Deviations from infra.md

- Third-party images run as their own non-root uid: Postgres 70, Valkey 999, collector 10001. Kafka and our images run as 1000.
- Kafka's config directory is an `emptyDir`, seeded by an init container, because the image's start script writes `server.properties` there and the root filesystem is read-only.

## Checks

```bash
helm lint infra/helm/cartogra --strict -f infra/helm/cartogra/values-demo.yaml --set global.imageTag=dev
helm template cartogra infra/helm/cartogra -f infra/helm/cartogra/values-demo.yaml --set global.imageTag=dev
```

CI runs both for the prod and demo values (`.github/workflows/helm.yml`).

## Test on a local cluster

See [`infra/helm/README.md`](../README.md).
