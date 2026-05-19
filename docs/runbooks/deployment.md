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

SCM provider credentials are stored in the `registry.scm_connections` table as JSONB. They are **never** stored in environment variables on the ingestion service.

### GitHub — Personal Access Token

1. Create a PAT at **GitHub → Settings → Developer settings → Personal access tokens** with scopes: `repo` (read), `read:org`.
2. Insert a connection record via the Registry API (requires `ADMIN` role):

```http
POST /connections
Authorization: Bearer <token>
X-Tenant-Id: <tenantId>
Content-Type: application/json

{
  "providerType": "github",
  "config": {
    "token": "<PAT value>",
    "org": "your-github-org"
  }
}
```

**Security:** The PAT is stored encrypted-at-rest via Postgres RLS + column encryption policy. It is never logged by the ingestion service.

### Azure DevOps — Personal Access Token

1. Create a PAT at **Azure DevOps → User Settings → Personal Access Tokens** with scopes: `Code (Read)`, `Graph (Read)`.
2. Insert a connection record:

```http
POST /connections
Authorization: Bearer <token>
X-Tenant-Id: <tenantId>
Content-Type: application/json

{
  "providerType": "azuredevops",
  "config": {
    "pat": "<PAT value>",
    "organization": "your-ado-org"
  }
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
