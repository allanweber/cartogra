# K8s Discovery Path — Local Test Guide

Tests the `KubernetesWorker` against the 8 Trading-Journal services defined in
`seed/create-test-repos.sh`. No real images needed — `nginx:1.25` acts as a
stand-in so discovery can be verified without building anything.

## Services under test

| Service | Team | Port |
| --- | --- | --- |
| `trade-service` | trading-core | 8090 |
| `portfolio-service` | portfolio | 8091 |
| `market-data-service` | data | 8092 |
| `notification-service` | platform | 8093 |
| `analytics-service` | data | 8094 |
| `user-profile-service` | platform | 8095 |
| `report-generator` | portfolio | 8096 |
| `risk-engine` | trading-core | 8097 |

All 8 deploy into namespace `prod`, which is labeled with a fixed test tenant ID:

```yaml
cartogra.io/tenant-id: a0000000-0000-0000-0000-000000000001
```

---

## Prerequisites

| Tool | Check |
| --- | --- |
| Docker Desktop with Kubernetes enabled | `kubectl cluster-info` |
| Local infra running (Kafka + Postgres) | `docker compose ... ps` |

Enable Kubernetes in Docker Desktop: Settings → Kubernetes → Enable Kubernetes → Apply & Restart.

Verify the node is ready:

```bash
kubectl get nodes
# NAME             STATUS   ROLES           AGE
# docker-desktop   Ready    control-plane   …
```

Start Kafka and Postgres if not already running:

```bash
docker compose -f infra/docker-compose/docker-compose.dev.yml up -d postgres kafka
```

---

## Step 0 — Resolve your tenant ID

The manifest hardcodes a placeholder tenant ID (`a0000000-0000-0000-0000-000000000001`).
Before applying it you must replace that value with the real tenant ID already in your
local database.

**Query the tenant from Postgres:**

```bash
docker exec -it cartogra-postgres psql -U cartogra -d cartogra -c "SET search_path TO registry; SELECT id, name FROM tenants WHERE deleted_at IS NULL;"
```

Copy the `id` value for your tenant, then update the namespace label in the manifest:

```bash
# Replace <YOUR-TENANT-ID> with the UUID from the query above
sed -i 's/a0000000-0000-0000-0000-000000000001/<YOUR-TENANT-ID>/g' seed/k8s-discovery-manifests.yaml
```

Verify the substitution:

```bash
grep 'tenant-id' seed/k8s-discovery-manifests.yaml
```

> **Note:** The teardown script and this guide both use the same manifest file, so the
> correct tenant ID will also be used when cleaning up.

---

## Step 1 — Deploy the test services

```bash
kubectl apply -f seed/k8s-discovery-manifests.yaml
```

This creates the `prod` namespace (with the tenant label), 8 Deployments, and 8 Services.

Wait for all pods to be ready:

```bash
kubectl get pods -n prod -w
```

Expected — all 16 pods `Running 1/1` (2 replicas × 8 services):

```text
trade-service-xxx          1/1   Running
portfolio-service-xxx      1/1   Running
market-data-service-xxx    1/1   Running
notification-service-xxx   1/1   Running
analytics-service-xxx      1/1   Running
user-profile-service-xxx   1/1   Running
report-generator-xxx       1/1   Running
risk-engine-xxx            1/1   Running
```

---

## Step 2 — Start the ingestion service with K8s enabled

```bash
INGESTION_WORKERS_K8S_ENABLED=true ./gradlew :services:ingestion:bootRun
```

The fabric8 client reads `~/.kube/config` automatically (Docker Desktop sets this up).

Look for this line in the logs:

```text
Starting Kubernetes worker watching all namespaces for label cartogra.io/tenant-id
```

The worker registers three watches:

- **Services** — fires for `ADDED` / `MODIFIED` events cluster-wide (initial discovery + updates)
- **Endpoints** — fires for `MODIFIED` events when pod readiness changes (health-status tracking)
- **Namespaces** — fires for `MODIFIED` events; when the tenant label is added to a namespace, all its Services are discovered automatically

---

## Step 3 — Verify Kafka events

Open Kafka UI at `http://localhost:8086` → Topics → `cartogra.ingestion.service.discovered`.

You should see **8 messages**, one per service. Example message for `trade-service`:

```json
{
  "tenantId": "a0000000-0000-0000-0000-000000000001",
  "connectionId": null,
  "source": "kubernetes",
  "externalId": "prod/trade-service",
  "name": "trade-service",
  "namespace": "prod",
  "healthStatus": "HEALTHY"
}
```

Health status mapping:

| Endpoint state | `healthStatus` |
| --- | --- |
| All pods ready | `HEALTHY` |
| Mix of ready + not-ready | `DEGRADED` |
| No pods ready | `UNHEALTHY` |
| No endpoint resource | `UNKNOWN` |

---

## Step 4 — Test health-status transitions

The worker watches both **Service** and **Endpoints** objects. When pod readiness
changes (scale, rolling restart, crash), Kubernetes updates the Endpoints resource and
the worker fires a new discovery event automatically.

**Simulate UNHEALTHY** — scale to 0:

```bash
kubectl scale deployment trade-service -n prod --replicas=0
```

As pods terminate, the Endpoints resource is updated and a new Kafka message appears
with `"healthStatus": "UNHEALTHY"` (all addresses not-ready) or `"UNKNOWN"` (no
addresses at all).

Scale back up to get `HEALTHY` again:

```bash
kubectl scale deployment trade-service -n prod --replicas=2
```

**Simulate DEGRADED** — delete one pod while replicas stay at 2:

```bash
kubectl delete pod -n prod -l app=risk-engine --wait=false
```

While the replacement pod is starting, endpoints will have one ready address and one
not-ready address → `"healthStatus": "DEGRADED"`.

---

## Step 5 — Negative test (unlabeled namespace)

```bash
kubectl create namespace no-tenant
kubectl apply -n no-tenant -f - <<'EOF'
apiVersion: v1
kind: Service
metadata:
  name: ghost-service
spec:
  ports:
    - port: 80
EOF
```

No Kafka message should be published for `ghost-service` — its namespace has no
`cartogra.io/tenant-id` label.

---

## Step 6 — Test opt-in after the worker starts

Create a new namespace and service, then add the label later:

```bash
kubectl create namespace late-adopter
kubectl apply -n late-adopter -f - <<'EOF'
apiVersion: v1
kind: Service
metadata:
  name: new-service
spec:
  ports:
    - port: 80
EOF
# No event yet — namespace has no tenant label.

kubectl label namespace late-adopter cartogra.io/tenant-id=a0000000-0000-0000-0000-000000000001
```

Labeling the namespace fires a `MODIFIED` event on the Namespace resource. The worker catches it,
sees the `cartogra.io/tenant-id` label, lists all Services in `late-adopter`, and publishes a
discovery event for each one automatically.

A Kafka message for `late-adopter/new-service` should now appear.

---

## Using real images (optional)

After running `create-test-repos.sh` and building + pushing images to GHCR, replace
the stand-in image in the manifests:

```bash
# Example for trade-service
kubectl set image deployment/trade-service -n prod \
  trade-service=ghcr.io/Trading-Journal/trade-service:1.0.0
```

Or re-apply the manifests from each repo's `k8s/` directory, which already use the
correct `ghcr.io/Trading-Journal/<name>:1.0.0` image references.

---

## Teardown

Run the dedicated teardown script to remove all 8 Deployments, Services, and the `prod` namespace:

```bash
bash seed/k8s-discovery-teardown.sh
```

To keep the namespace and only remove workloads:

```bash
bash seed/k8s-discovery-teardown.sh --keep-namespace
```

The script also removes the `no-tenant` and `late-adopter` namespaces created in Steps 5 and 6.

Stop the ingestion service with `Ctrl+C`.
