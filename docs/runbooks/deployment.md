# Runbook: Deployment

## Overview

Cartogra uses a three-environment promotion model:

```
feat/<name> → main → staging (auto) → production (manual gate)
```

All deployments use Kubernetes rolling updates (`maxUnavailable: 0`, `maxSurge: 1`). No service has planned downtime.

---

## Environments

| Environment | Namespace | Trigger | Approval |
|------------|-----------|---------|---------|
| `dev` | `dev` | PR preview (optional) | None |
| `staging` | `staging` | Merge to `main` | None |
| `production` | `prod` | Manual promotion from staging | Required (1 approver) |

---

## Prerequisites

```bash
# Required tools
kubectl   >= 1.28
helm      >= 3.14
terraform >= 1.7
```

Authenticate to the cluster:

```bash
aws eks update-kubeconfig --region us-east-1 --name cartogra-prod
kubectl config use-context arn:aws:eks:us-east-1:ACCOUNT:cluster/cartogra-prod
```

---

## 1. CI pipeline (automated)

On every PR:

1. `./gradlew build` — compile + unit tests
2. Trivy image scan — blocks on CRITICAL/HIGH CVEs
3. Docker build (multi-stage, non-root, `eclipse-temurin:25-jre-jammy`)
4. Push to ECR with `sha-<short-git-sha>` tag (**never `latest`**)

On merge to `main`:

5. Integration tests (Testcontainers)
6. Push image with `staging-<sha>` tag
7. `kubectl set image` on staging Deployment — rolling update

---

## 2. Promote staging → production

### Pre-flight checks

```bash
# Verify staging is healthy
kubectl -n staging get pods -l app=registry
kubectl -n staging rollout status deployment/registry

# Check no pending alerts in Grafana
# Check Sentry error rate baseline (< 0.1% p99 error rate)
```

### Promote

```bash
# Tag the staging image for production
STAGING_SHA=$(kubectl -n staging get deployment registry -o jsonpath='{.spec.template.spec.containers[0].image}' | cut -d: -f2)
AWS_ACCOUNT=123456789012
AWS_REGION=us-east-1
REGISTRY=$AWS_ACCOUNT.dkr.ecr.$AWS_REGION.amazonaws.com

docker pull $REGISTRY/cartogra-registry:$STAGING_SHA
docker tag  $REGISTRY/cartogra-registry:$STAGING_SHA $REGISTRY/cartogra-registry:prod-$STAGING_SHA
docker push $REGISTRY/cartogra-registry:prod-$STAGING_SHA
```

### Apply to production

```bash
# Update the production Deployment image
kubectl -n prod set image deployment/registry registry=$REGISTRY/cartogra-registry:prod-$STAGING_SHA

# Watch the rollout
kubectl -n prod rollout status deployment/registry

# Verify pods are healthy
kubectl -n prod get pods -l app=registry
```

Repeat for each service: `gateway`, `registry`, `topology`, `contract`, `intelligence`, `ingestion`, `frontend`.

---

## 3. Database migrations (Flyway)

Flyway migrations run automatically on service startup via Spring Boot auto-configuration.

**Before deploying a service with new migrations:**

1. Verify the migration is backward-compatible (new nullable column, new table — not dropping columns used by the current version).
2. If the migration is not backward-compatible, use a multi-step expand/contract pattern:
   - **Expand:** deploy migration that adds new structures (compatible with old code)
   - **Deploy new code:** now both old and new code work
   - **Contract:** remove old structures once old code is fully drained

---

## 4. Rolling back a deployment

### Kubernetes rollback

```bash
# Roll back to the previous ReplicaSet
kubectl -n prod rollout undo deployment/registry

# Roll back to a specific revision
kubectl -n prod rollout history deployment/registry
kubectl -n prod rollout undo deployment/registry --to-revision=<N>
```

### Database rollback

Flyway does not provide automatic rollback. To undo a migration:

1. Write a new `V00N__rollback_<desc>.sql` migration that reverses the change.
2. Never edit or delete an already-applied migration file.
3. If the migration added a column with no data, a `DROP COLUMN` migration is safe.
4. If the migration wrote data, escalate to the on-call engineer and engineering lead before proceeding.

---

## 5. Secrets management

Secrets are stored in AWS Secrets Manager and mounted as volumes in the pod (never as env vars).

```bash
# Rotate a secret (example: JWT_SECRET)
aws secretsmanager put-secret-value \
  --secret-id cartogra/prod/gateway/jwt-secret \
  --secret-string "$(openssl rand -base64 32)"

# Restart pods to pick up new secret value
kubectl -n prod rollout restart deployment/gateway
```

**Never commit Secret YAML to Git.** Use sealed-secrets or External Secrets Operator.

---

## 6. Scaling

### Manual scale

```bash
kubectl -n prod scale deployment/registry --replicas=5
```

### HPA configuration

Each service has an HPA. View current state:

```bash
kubectl -n prod get hpa
```

Adjust CPU/memory targets via Terraform (`terraform/modules/k8s-service/`). Never edit HPA YAML directly in production.

---

## 7. Infrastructure changes (Terraform)

```bash
cd terraform/environments/prod

# Preview changes
terraform plan -var-file=terraform.tfvars

# Apply (requires approval in CI — do not run locally in production without a second engineer present)
terraform apply -var-file=terraform.tfvars
```

**Never run `terraform destroy` in CI without a human approval gate.**

State is stored in S3 (`cartogra-tf-state-prod`) with DynamoDB locking.

---

## 8. SCM provider credential setup

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

## 9. Scheduled sync

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

## 10. Webhook setup

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
