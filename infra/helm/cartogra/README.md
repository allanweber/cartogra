# Cartogra Helm chart

Umbrella chart: `gateway`, `registry`, `topology`, `contract`, `ingestion`, `frontend`, `collector`, plus single-replica `postgres`, `kafka` and `valkey` StatefulSets, and optional `alerting` (Prometheus + Alertmanager, on in production). `charts/common` is a library chart with the shared Deployment/Service/ConfigMap/PDB/HPA template.

Subcharts are rendered through the umbrella only. They call `common.*` templates without declaring the library as a dependency, which works because Helm shares named templates across a release.

## Values

| File | Use |
|---|---|
| `values.yaml` | Production shape: 3 replicas, PDB `minAvailable: 2`, HPA 3–6 (infra.md). |
| `values-production.yaml` | Production (cartogra.dev) on single-node k3s ([ADR-0032](../../../docs/adr/0032-production-on-single-node-k3s.md)): 1 replica, no PDB/HPA, sized for 8GB. Guest mode on (Acme tenant), signup off. |

Required: `global.host` (ingress, OAuth redirects, CORS). CI sets `global.imageTag` to the commit SHA; `latest` is rejected. With `alerting.enabled`: `alerting.email.to` and `alerting.email.from`.

## Secrets

The chart creates no Secrets. Each one is mounted read-only at `/etc/cartogra/secrets`, and Spring reads the files through `spring.config.import=configtree:`. Create them from SealedSecrets:

| Secret | Keys |
|---|---|
| `gateway-secrets` | `DB_USERNAME` `DB_PASSWORD` `FLYWAY_USERNAME` `FLYWAY_PASSWORD` `JWT_SECRET` `INTERNAL_TOKEN_KEY` `RESEND_API_KEY` `GOOGLE_CLIENT_SECRET` `GITHUB_CLIENT_SECRET`, plus `BOOTSTRAP_ADMIN_PASSWORD` when `BOOTSTRAP_TENANT_ID` is set (production) |
| `registry-secrets`, `topology-secrets`, `contract-secrets` | `DB_USERNAME` `DB_PASSWORD` `FLYWAY_USERNAME` `FLYWAY_PASSWORD` `INTERNAL_TOKEN_KEY` |
| `ingestion-secrets` | `DB_USERNAME` `DB_PASSWORD` `FLYWAY_USERNAME` `FLYWAY_PASSWORD` `INTERNAL_TOKEN_KEY` `CLUSTER_CREDENTIALS_KEY` |
| `postgres-secrets` | `DB_USERNAME` `DB_PASSWORD` (the superuser), `CARTOGRA_OWNER_PASSWORD` `CARTOGRA_APP_PASSWORD` |

In the service secrets, `DB_USERNAME` is `cartogra_app` and `FLYWAY_USERNAME` is `cartogra_owner`, with the passwords from `postgres-secrets`.

## Database roles

Postgres RLS isolates the tenants. The `tenant_isolation` policies apply only to a role that is not a superuser and has `NOBYPASSRLS`, so the services use two roles:

| Role | Use |
|---|---|
| `cartogra_owner` | Owns the schemas and tables. Flyway connects with it. |
| `cartogra_app` | The services connect with it. DML grants only; the policies always apply. |

`charts/postgres/files/bootstrap-roles.sql` creates both roles. The postgres pod runs it at init, on an empty volume only. For an existing volume, run it one time as the superuser before the services start: it also gives the existing schemas to `cartogra_owner`. The services set `app.current_tenant_id` (or `app.rls_bypass` for system work) on each connection; see "Row-level security" in [`docs/architecture/system-overview.md`](../../../docs/architecture/system-overview.md).

Known gap: RLS does not apply to the materialized view `topology.dependency_graph_edges`. Its reads filter by `tenant_id` in the code.

`INTERNAL_TOKEN_KEY` must have the same value in the five service Secrets. It signs the token that the gateway and the services attach to each internal call. Use 32 or more random bytes (`openssl rand -base64 48`), not the value of `JWT_SECRET`.

## Exposure

The ingress routes `/api` to the gateway and everything else to the frontend. The collector's OTLP ports stay ClusterIP-only: `cartogra.tenant.id` is unauthenticated until roadmap 5.9. Its metrics port (8889) is ClusterIP-only too.

`registry`, `topology`, `contract` and `ingestion` trust only a signed `X-Internal-Token` (from the gateway, or a `SERVICE` token from another service). As a second layer, each gets a `NetworkPolicy` (`networkPolicy.allowFrom` in its values) that admits only the listed `app` labels on its app port: `registry` ← gateway, ingestion, topology; `topology`, `contract` and `ingestion` ← gateway. The gateway and frontend stay open for the ingress controller. A CNI that does not enforce NetworkPolicy ignores these silently; k3s enforces them.

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
helm lint infra/helm/cartogra --strict -f infra/helm/cartogra/values-production.yaml --set global.imageTag=dev --set alerting.email.to=dev@example.invalid
helm template cartogra infra/helm/cartogra -f infra/helm/cartogra/values-production.yaml --set global.imageTag=dev --set alerting.email.to=dev@example.invalid
```

CI runs both for the HA defaults and the production values (`.github/workflows/helm.yml`).
