# Runbook: Deployment

The only running environment is the **public demo**: one Hetzner ARM VM with single-node k3s
([ADR-0030](../adr/0030-demo-on-single-node-k3s.md)). This runbook builds it from nothing and doubles as the
rebuild procedure. Charts: `infra/helm/cartogra` (the product) and `infra/helm/acme` (Acme Fintech, the
simulated customer the demo shows).

```
push to main ─▶ images.yml: ghcr.io/<owner>/cartogra-<image>:<sha> (amd64 + arm64)
manual       ─▶ deploy.yml: helm upgrade cartogra + acme at <sha> ─▶ acme bootstrap Job
```

| Piece | Where |
|---|---|
| VM | Hetzner CAX21 (4 vCPU ARM, 8GB), Ubuntu 24.04. Resize to CAX31 only on measured memory pressure. |
| Ingress / TLS | Traefik (bundled with k3s) + cert-manager Let's Encrypt, host `cartogra.dev` |
| Secrets | Sealed Secrets; encrypted `SealedSecret`s committed under `infra/k8s/sealed/` |
| Namespaces | `cartogra` (product), `acme-system` (Acme release: reader ServiceAccount, bootstrap Job), `acme-fintech` (21 fake services, tenant-labelled) |
| Backups | `postgres-backup` CronJob, nightly `pg_dump -Fc` to Hetzner Object Storage |

## 1. Prerequisites

`hcloud`, `kubectl`, `helm` >= 3.14, `kubeseal`, `gh`, Node >= 24 + pnpm (GitHub mirror), and DNS for
`cartogra.dev`.

## 2. VM and k3s

```bash
hcloud server create --name cartogra-demo --type cax21 --image ubuntu-24.04 --location fsn1 --ssh-key <key>
hcloud firewall create --name cartogra-demo
hcloud firewall add-rule cartogra-demo --direction in --protocol tcp --port 22   --source-ips <your-ip>/32
hcloud firewall add-rule cartogra-demo --direction in --protocol tcp --port 80   --source-ips 0.0.0.0/0 --source-ips ::/0
hcloud firewall add-rule cartogra-demo --direction in --protocol tcp --port 443  --source-ips 0.0.0.0/0 --source-ips ::/0
hcloud firewall add-rule cartogra-demo --direction in --protocol tcp --port 6443 --source-ips 0.0.0.0/0 --source-ips ::/0
hcloud firewall apply-to-resource cartogra-demo --type server --server cartogra-demo
```

6443 is open to the world because GitHub-hosted runners have no fixed IPs; the API server still requires
client credentials. Close it (self-hosted runner or a tunnel) if that ever matters.

Point `cartogra.dev` (A/AAAA) at the server, then:

```bash
ssh root@<ip> 'curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--tls-san <ip> --tls-san cartogra.dev" sh -'
scp root@<ip>:/etc/rancher/k3s/k3s.yaml ~/.kube/cartogra-demo && sed -i "s/127.0.0.1/<ip>/" ~/.kube/cartogra-demo
export KUBECONFIG=~/.kube/cartogra-demo
kubectl get nodes   # Ready
```

k3s ships Traefik (ingress class `traefik`) and the `local-path` storage class the StatefulSets use.

## 3. Cluster add-ons

```bash
helm repo add jetstack https://charts.jetstack.io
helm repo add sealed-secrets https://bitnami-labs.github.io/sealed-secrets
helm upgrade --install cert-manager jetstack/cert-manager -n cert-manager --create-namespace --set crds.enabled=true
helm upgrade --install sealed-secrets sealed-secrets/sealed-secrets -n kube-system --set fullnameOverride=sealed-secrets-controller
```

ClusterIssuer (`kubectl apply -f` this):

```yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: <you>@<domain>
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
      - http01:
          ingress:
            ingressClassName: traefik
```

### Sealed Secrets key

The controller's private key decrypts every committed `SealedSecret`; keep a copy in your password manager,
never in git.

- **First build**: back it up right away:
  `kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key -o yaml > sealed-secrets-key.yaml`
- **Rebuild**: restore it *before* applying any SealedSecret, then restart the controller:
  `kubectl apply -f sealed-secrets-key.yaml && kubectl -n kube-system delete pod -l app.kubernetes.io/name=sealed-secrets`

## 4. Secrets

The charts create no Secrets; each is mounted as files. Seal them (never commit the plain YAML):

| Namespace | Secret | Keys |
|---|---|---|
| `cartogra` | `gateway-secrets` | `DB_USERNAME` `DB_PASSWORD` `JWT_SECRET` `RESEND_API_KEY` `GOOGLE_CLIENT_SECRET` `GITHUB_CLIENT_SECRET` `BOOTSTRAP_ADMIN_PASSWORD` |
| `cartogra` | `registry-secrets`, `topology-secrets` | `DB_USERNAME` `DB_PASSWORD` |
| `cartogra` | `ingestion-secrets` | `DB_USERNAME` `DB_PASSWORD` `CLUSTER_CREDENTIALS_KEY` (`openssl rand -base64 32`) |
| `cartogra` | `postgres-secrets` | `DB_USERNAME` `DB_PASSWORD` (same values) |
| `cartogra` | `backup-secrets` | `S3_ACCESS_KEY` `S3_SECRET_KEY` (Hetzner Object Storage) |
| `acme-system` | `acme-bootstrap` | `ACME_PASSWORD` (= `BOOTSTRAP_ADMIN_PASSWORD`), `ACME_GITHUB_TOKEN` (read-only, public repos) |

```bash
kubectl create namespace cartogra --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace acme-system --dry-run=client -o yaml | kubectl apply -f -
kubectl -n cartogra create secret generic registry-secrets --dry-run=client -o yaml \
  --from-literal=DB_USERNAME=cartogra --from-literal=DB_PASSWORD=<pw> \
  | kubeseal --format yaml > infra/k8s/sealed/registry-secrets.yaml
# same pattern for every row above, then:
kubectl apply -f infra/k8s/sealed/
```

Rotating a value: re-seal, commit, apply, then `kubectl -n cartogra rollout restart deploy/<service>`.
`BOOTSTRAP_ADMIN_PASSWORD` is only used when the admin is first created; rotate it in the UI as well.

## 5. Images and CI access

- `images.yml` pushes all six images on every push to `main`, tagged with the commit SHA. Make the
  `cartogra-*` packages **public** (GitHub → Packages → settings) so k3s pulls without credentials.
- GitHub environment **`demo`**: secret `KUBECONFIG` (contents of `~/.kube/cartogra-demo`), variable
  `ACME_GITHUB_ORG` (section 6). Add yourself as required reviewer.

## 6. Acme GitHub mirror

The demo scans a public GitHub copy of Acme's 21 repos (the seed keeps Azure DevOps for local runs):

```bash
gh auth login && gh auth setup-git
cd seed/acme-fintech && ACME_GITHUB_ORG=<org> pnpm acme repos --provider github
```

A fine-grained token with read-only access to that org's public repos is `ACME_GITHUB_TOKEN`.

## 7. Install / upgrade

Normally **Actions → Deploy demo → Run workflow** (optionally with a SHA). By hand, the same commands:

```bash
SHA=<commit sha whose images were pushed>
helm upgrade --install cartogra infra/helm/cartogra -n cartogra --create-namespace \
  -f infra/helm/cartogra/values-demo.yaml --set global.imageTag=$SHA --wait --timeout 15m
helm upgrade --install acme infra/helm/acme -n acme-system --create-namespace \
  --set image.tag=$SHA --set bootstrap.githubOrg=<org> --wait --timeout 20m
```

What happens on a fresh install:

1. Registry runs Flyway; the gateway's tenant bootstrap (`BOOTSTRAP_*` in `values-demo.yaml`) creates the Acme
   tenant (fixed UUID, business plan) and its verified admin once the tables exist (`tenant_bootstrap.created`
   in the gateway log).
2. The `acme` release creates the `acme-fintech` namespace (labelled `cartogra.io/tenant-id`), the 21 fake
   services, and the read-only `cartogra-reader` ServiceAccount with its token.
3. The `acme-bootstrap` hook Job logs in, registers this cluster (`https://kubernetes.default.svc` + reader
   token), connects the GitHub mirror and triggers a sync, then runs `acme load` (teams, owners, tiers, declared
   edges) once discovery has produced all 21 services. It re-runs idempotently on every upgrade.

## 8. Verify

```bash
kubectl -n cartogra get pods           # all 1/1
kubectl -n acme-fintech get pods       # 21 Running
kubectl -n acme-system get jobs        # acme-bootstrap Complete (deleted on success; check events if gone)
curl -s https://cartogra.dev/api/auth/features | jq .data   # signupEnabled false, guestEnabled true
```

In a browser, `https://cartogra.dev/demo` lands on the dashboard, the graph shows Acme with observed edges
within about a minute, and write buttons are disabled. Uptime check: `https://cartogra.dev/api/auth/features`
(actuator endpoints are not routed through the ingress).

## 9. Rollback

```bash
helm -n cartogra history cartogra
helm -n cartogra rollback cartogra <revision> --wait
```

Or re-run **Deploy demo** with an older SHA. Flyway migrations are forward-only and additive, so older pods
run against a newer schema; never roll the schema back by hand.

## 10. Backups and restore

`postgres-backup` (`values-demo.yaml` → `backup`) dumps the database nightly at 02:00 UTC to
`s3://cartogra-demo-backups/postgres/cartogra-<timestamp>.dump`. Retention is a bucket lifecycle rule (expire
after 7 days), set once in the Hetzner console. Run one now:

```bash
kubectl -n cartogra create job --from=cronjob/postgres-backup backup-manual
kubectl -n cartogra logs -f job/backup-manual -c upload
```

Restore (services scaled down):

```bash
kubectl -n cartogra scale deploy --all --replicas=0
aws s3 cp s3://cartogra-demo-backups/postgres/<file>.dump . --endpoint-url https://fsn1.your-objectstorage.com
kubectl -n cartogra cp <file>.dump postgres-0:/tmp/restore.dump
kubectl -n cartogra exec postgres-0 -- sh -c 'pg_restore --clean --if-exists -U "$(cat /etc/cartogra/secrets/DB_USERNAME)" -d cartogra /tmp/restore.dump'
kubectl -n cartogra scale deploy --all --replicas=1
```

## 11. Rebuild from scratch

Section 2 → 3 (restore the Sealed Secrets key) → `kubectl apply -f infra/k8s/sealed/` → 7 → 8. Restore data
(section 10) only if the database must survive; otherwise the bootstrap recreates Acme.

---

## 12. SCM provider credential setup

SCM provider credentials are stored in the `ingestion.scm_connections` table as JSONB (`config` column). They are **never** stored in environment variables on the ingestion service. SCM connection management lives in the **ingestion** service (gateway route `/api/v1/scm-connections`), not registry.

### GitHub — Personal Access Token

1. Create a PAT at **GitHub → Settings → Developer settings → Personal access tokens** with scopes: `repo` (read), `read:org`.
2. Insert a connection record via the Ingestion API (gateway-authenticated):

```http
POST /api/v1/scm-connections
Authorization: Bearer <token>
X-Tenant-Id: <tenantId>
Content-Type: application/json

{
  "provider": "github",
  "config": "{\"token\":\"<PAT value>\",\"org\":\"your-github-org\"}",
  "syncScheduler": true,
  "pollIntervalMinutes": 15,
  "webhookEnabled": true
}
```

**Security:** The PAT is stored encrypted-at-rest via Postgres RLS + column encryption policy. It is never logged by the ingestion service.

### Azure DevOps — Personal Access Token

1. Create a PAT at **Azure DevOps → User Settings → Personal Access Tokens** with scopes: `Code (Read)`, `Graph (Read)`.
2. Insert a connection record:

```http
POST /api/v1/scm-connections
Authorization: Bearer <token>
X-Tenant-Id: <tenantId>
Content-Type: application/json

{
  "provider": "azuredevops",
  "config": "{\"pat\":\"<PAT value>\",\"organization\":\"your-ado-org\"}"
}
```

### Triggering a manual sync (smoke test)

```bash
# Publish a sync command directly to Kafka (requires kafkacat / kcat)
kcat -b localhost:9092 -t cartogra.registry.sync.command -P << 'EOF'
{
  "event_id": "00000000-0000-0000-0000-000000000001",
  "event_type": "sync.command",
  "entity_id": "<connectionId>",
  "tenant_id": "<tenantId>",
  "timestamp": "2026-05-18T00:00:00Z",
  "version": 1,
  "correlation_id": "00000000-0000-0000-0000-000000000002",
  "payload": {
    "connectionId": "<connectionId>",
    "tenantId": "<tenantId>",
    "providerType": "github",
    "connectionConfig": {
      "token": "<PAT>",
      "org": "your-github-org"
    }
  }
}
EOF
```

Monitor the `sync_jobs` table to observe the PENDING → RUNNING → COMPLETED transition:

```sql
SELECT id, status, repositories_synced, error_message, updated_at
FROM ingestion.sync_jobs
ORDER BY created_at DESC
LIMIT 5;
```

The completed sync writes its outcome back onto the connection (`last_sync_status`, `last_sync_at`):

```sql
SELECT id, provider, last_sync_status, last_sync_at, next_sync_at
FROM ingestion.scm_connections
ORDER BY updated_at DESC
LIMIT 5;
```

## 13. Scheduled sync

When `scm_connections.sync_scheduler = true`, the ingestion `SyncScheduler` polls for due connections and publishes a `sync.command` for each.

- **Cadence:** the scheduler tick runs every `INGESTION_SYNC_POLL_INTERVAL` (ISO-8601 duration, default `PT15M`). Per-connection cadence is `poll_interval_minutes`, tracked via `next_sync_at`.
- **Multi-instance safety:** each connection is published inside a `REQUIRES_NEW` transaction guarded by a PostgreSQL transaction-scoped advisory lock (`pg_try_advisory_xact_lock`). With N ingestion replicas, only the replica that wins the lock publishes — no duplicate commands.
- **Delivery semantics:** at-least-once. `next_sync_at` is advanced outside the lock transaction, so a crash between publish and timestamp-advance results in a retry on the next tick (deduplicated downstream by the RUNNING-job guard in `SyncExecutionService`).

Inspect due connections:

```sql
SELECT id, provider, poll_interval_minutes, next_sync_at
FROM ingestion.scm_connections
WHERE sync_scheduler = true AND deleted_at IS NULL
  AND (next_sync_at IS NULL OR next_sync_at <= now());
```

To pause scheduling for a connection: `PUT /api/v1/scm-connections/{id}` with `{"syncScheduler": false}` (clears `next_sync_at`).

## 14. Webhook setup

Real-time sync is driven by SCM webhooks. The gateway exposes the receiver publicly at `/api/v1/ingestion/webhooks/{providerType}/{connectionId}` (no JWT). Per-provider authentication is verified against the secret stored in `ingestion.scm_webhooks.webhook_secret`.

**Register a webhook secret** (one row per connection in `scm_webhooks`) and set `scm_connections.webhook_enabled = true`.

### GitHub

1. Repo/org **Settings → Webhooks → Add webhook**.
2. **Payload URL:** `https://<gateway-host>/api/v1/ingestion/webhooks/github/<connectionId>`
3. **Content type:** `application/json`
4. **Secret:** the value stored in `scm_webhooks.webhook_secret`. Ingestion verifies `X-Hub-Signature-256` (HMAC-SHA256 over the raw body).
5. **Events:** at least `push`. (`ping` is accepted with `202` but does not trigger a sync.)

### Azure DevOps

1. **Project Settings → Service hooks → Create subscription → Web Hooks**.
2. **Trigger:** *Code pushed* (`git.push`).
3. **URL:** `https://<gateway-host>/api/v1/ingestion/webhooks/azuredevops/<connectionId>`
4. **Basic auth:** set username/password; store the same `user:password` string in `scm_webhooks.webhook_secret`. Ingestion verifies the `Authorization: Basic` header.

### Responses

| Outcome | Status |
|---------|--------|
| Valid signature, actionable event | `202` + `sync.command` published |
| Valid signature, non-actionable (e.g. GitHub `ping`) | `202`, no publish |
| Invalid signature / Basic auth | `401` (`WEBHOOK_SIGNATURE_INVALID`) |
| No webhook registration / unknown provider | `404` (`WEBHOOK_CONNECTION_NOT_FOUND`) |

Webhook responses are **not** wrapped in the standard response envelope.

## 15. Observed edges / OTel intake

Observed dependency edges ([ADR-0029](../adr/0029-observed-edges-from-otel-spans.md)) need an OTel collector that exports spans to Kafka. The deployable compose and the Helm chart (`infra/helm/cartogra`, `collector` subchart) share one config. Local procedure: [`local-development.md` → Testing observed edges](local-development.md#testing-observed-edges-otel-spans).

### Current state per environment

| Environment | Collector | Spans → Kafka | Observed edges |
|-------------|-----------|---------------|----------------|
| Local (`docker-compose.dev.yml`) | yes, single tenant via `CARTOGRA_TENANT_ID` | yes | works |
| `docker-compose.yml` (deployable) | yes, `infra/helm/cartogra/charts/collector/files/otel-collector.yml` (spans only, no Tempo/Loki/Prometheus), OTLP/HTTP on `127.0.0.1:4318` | yes | works, single tenant via `CARTOGRA_TENANT_ID` |
| Kubernetes (Helm `collector` subchart) | yes, same config file; ClusterIP only, never behind ingress | yes | works, single tenant via `collector.env.CARTOGRA_TENANT_ID` |

Topology is safe to deploy anyway: the `OtelSpanWorker` simply idles when `cartogra.observability.spans` receives nothing.

### Deploying the Topology change

- **Migration `V011__observed_edges.sql`** is additive and backward-compatible (nullable `dependencies.last_seen_at`, new tables `pending_span_links` and `unresolved_span_services` with RLS, one index). Old pods keep working during the rolling update; follow section 3, no expand/contract needed.
- **Kafka access:** Topology's consumer group `topology-consumer` needs read on `cartogra.observability.spans`, plus write on `cartogra.observability.spans.dlq` for quarantined records. The dev broker auto-creates both topics; **create them explicitly** in any environment where auto-create is off.
- **Rollback:** rolling Topology back is safe. The new tables and column are ignored by old code. Do not drop them on rollback; the `OtelSpanWorker` simply stops.
- **Metrics to watch after enabling:** `cartogra_spans_quarantined_total` (must stay ~0 once the tenant stamp is correct), `cartogra_spans_edges_total`, consumer lag on `cartogra.observability.spans`.

### Enabling the span feed (before 4.4 / 5.9 land)

Do this only on a private network. The tenant attribute is unauthenticated: anyone who can reach the collector (4317/4318) or the topic can attribute spans to **any tenant UUID that has live nodes**. Never expose those ports through an Ingress or LoadBalancer, and restrict the topic with Kafka ACLs to the collector (write) and Topology (read).

The config is the same as `infra/docker-compose/otel-collector.yml`. For the Kubernetes collector, add to `configmap.yml`:

```yaml
processors:
  resource/tenant:
    attributes:
      - { action: insert, key: cartogra.tenant.id, value: "${env:CARTOGRA_TENANT_ID}" }
  filter/no-self:
    error_mode: ignore
    traces:
      span:
        - 'resource.attributes["service.name"] == "topology"'
exporters:
  kafka/spans:
    brokers: [<kafka-bootstrap>:9092]
    traces:
      topic: cartogra.observability.spans
      encoding: otlp_json
service:
  pipelines:
    traces/spans:
      receivers: [otlp]
      processors: [memory_limiter, resource, resource/tenant, filter/no-self, batch]
      exporters: [kafka/spans]
```

and set `CARTOGRA_TENANT_ID` (a non-secret UUID) on the collector Deployment. Keep the existing `traces` pipeline for Tempo. The collector serves exactly one tenant, so this only suits a single-tenant environment. After applying, `kubectl -n infra rollout restart deployment/otel-collector` and check `kubectl -n infra logs deployment/otel-collector` for startup errors.

### Verification after enabling

1. Collector logs show no Kafka exporter errors.
2. Message count on `cartogra.observability.spans` grows (Kafka UI or `kafka-console-consumer`).
3. `cartogra_spans_quarantined_total` stays flat; if `reason=tenant_malformed` rises, the tenant env var is missing or invalid.
4. After real CLIENT traffic between catalogued services, `select count(*) from topology.dependencies where dependency_type='observed'` increases and the Observed graph shows edges (allow ~10 s).

### Planned

| Roadmap | Change |
|---------|--------|
| 5.9 | Authenticated gateway intake `POST /v1/otlp/traces` (`X-Cartogra-Api-Key`, scope `spans:write`); the gateway overwrites `cartogra.tenant.id` from the key and the collector stops accepting external traffic. `CARTOGRA_TENANT_ID` is dropped. Settings → Connections → OpenTelemetry card for keys, endpoint and status. |
