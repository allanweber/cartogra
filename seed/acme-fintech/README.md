# Acme Fintech seed

A simulated fintech SaaS (20 microservices, 5 teams) that **Cartogra monitors**. It is a standalone
project: no imports from Cartogra code, own `package.json`. It runs in the **kind** cluster.

| What | Where it comes from |
|---|---|
| 20 services in the catalog | Cartogra **discovery**: K8s (health, namespace) and SCM repos (repo, tech stack, ownership). Registry merges both by service name. The loader never calls `POST /services` |
| 5 teams, owners, tiers, declared dependencies | `pnpm acme load` through the gateway API |
| Observed edges, the drift | Real OTel spans emitted by the fake services through the collector |
| 20 source repos (all Azure DevOps; Java, Node, Python) | `pnpm acme repos`, generated from `seed-data.json` |

This is the only seed in the repo. It replaces `graph-perf-seed.sql`, `create-test-repos.sh`,
`k8s-discovery-manifests.yaml` / `k8s-discovery-teardown.sh` and `k8s-discovery-test.md` (see git history).

`seed-data.json` is the single source of truth: 5 teams, 20 services (3 orphans), 40 declared calls
containing exactly 2 cycles, 1 god service (`ledger-service`, 12 dependents), and 3 dormant undeclared
calls (`reporting-service -> payments-service`, `webhook-relay -> fraud-service`, `legacy-batch -> notification-service`)
that produce undeclared drift when switched on. One declared call (`loyalty-service -> fx-service`) is never made, so it
becomes missing drift once `topology.drift.missing-after` has passed.

## Prerequisites

`JDK 25`, `docker`, `kind`, `kubectl`, Node >= 24, pnpm. For `pnpm acme repos` only: `git`, `jq`, `az` with the `azure-devops` extension (`az login`), and a PAT (section 4b).

## 0. Start Cartogra and the cluster (from scratch)

From the repo root. Full detail in `docs/runbooks/quickstart.md`.

```bash
cp .env.example .env                                   # defaults work locally (RESEND_TEST_MODE=true)
docker compose -f infra/docker-compose/docker-compose.dev.yml up -d
docker compose -f infra/docker-compose/docker-compose.dev.yml ps    # wait until healthy (postgres, kafka, otel-collector)
```

Ingestion only encrypts stored cluster credentials if you give it a key. Put one in the gitignored
`services/ingestion/src/main/resources/application-dev.yml` (`ingestion.k8s.credentials-key`, from
`openssl rand -base64 32`); without it the kubeconfig is stored in plaintext (fine for a throwaway local cluster).

Each service in its own terminal (Flyway migrates on first start):

```bash
./gradlew :services:gateway:bootRun      # :8080
./gradlew :services:registry:bootRun     # :8081
./gradlew :services:topology:bootRun     # :8082
./gradlew :services:ingestion:bootRun --args='--spring.profiles.active=dev'   # :8085
cd frontend && pnpm install && pnpm dev  # :3006 (optional, for the UI)
```

kind cluster (skip if `kind get clusters` already lists `cartogra`): see `../install-kind-kubectl.md`, or just
`kind create cluster --name cartogra`.

## 1. Create the tenant (manual, once)

The tenant must be on the **business** plan: free caps at 10 services and 0 K8s clusters, and there is no API
to change plan. With the gateway and registry running (so Flyway has created the schema):

```bash
# 1. Register. Note data.tenantId from the response.
curl -s -X POST http://localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"acme@mail.com","password":"change-me-123","orgName":"Acme Fintech"}'

# 2. Read the OTP (the email is not really sent) and verify.
OTP=$(docker exec cartogra-postgres psql -U cartogra -d cartogra -Atc \
  "SELECT email_verification_token FROM registry.users WHERE email='acme@mail.com'")
curl -s -X POST http://localhost:8080/api/auth/verify -H 'Content-Type: application/json' \
  -d "{\"email\":\"acme@mail.com\",\"token\":\"$OTP\"}"

# 3. Upgrade the plan (there is no API for this).
docker exec cartogra-postgres psql -U cartogra -d cartogra -c \
  "UPDATE registry.tenants SET plan_id=(SELECT id FROM registry.billing_plans WHERE slug='business') WHERE name='Acme Fintech'"

# 4. Sanity check: plan is business, tenant id matches.
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"acme@mail.com","password":"change-me-123"}' | jq -r .data.accessToken)
curl -s http://localhost:8080/api/auth/tenant -H "Authorization: Bearer $TOKEN" | jq .data
```

Keep the email, password and `tenantId`; they become `ACME_EMAIL`, `ACME_PASSWORD`, `ACME_TENANT_ID`.
Don't name the org `E2E Tenant ...`; the e2e teardown purges those. Business plan limits: 100 services,
5 SCM connections, 3 K8s clusters.

## 2. Give the yml the tenant id

```bash
cd seed/acme-fintech && pnpm install
export ACME_TENANT_ID=<tenant uuid>
export ACME_OTLP_ENDPOINT=http://172.18.0.1:4318   # collector on the host, as seen from kind pods
pnpm acme manifests                                  # writes k8s/acme-fintech.yaml (gitignored)
```

`ACME_OTLP_ENDPOINT` must be reachable from inside the cluster. On Linux the host is the kind docker network
gateway: `docker network inspect kind -f '{{(index .IPAM.Config 0).Gateway}}'`. The collector publishes `4318`
on all interfaces; check your firewall if spans don't arrive.

The generated yml puts `cartogra.io/tenant-id: <uuid>` on the `acme-fintech` namespace (so discovery picks the
Services up) and sets `ACME_TENANT_ID` on every pod (so spans carry `cartogra.tenant.id`).

## 3. Build, load the image, deploy

```bash
docker build -t acme-fintech-service:1.0.0 .
kind load docker-image acme-fintech-service:1.0.0 --name cartogra
kubectl apply -f k8s/acme-fintech.yaml
kubectl -n acme-fintech get pods -w        # 20 pods Running 1/1
```

## 4. Register the cluster in ingestion

Discovery only runs for registered clusters. In the UI (`http://localhost:3006`, log in as the tenant admin) add a Kubernetes cluster and paste the kind kubeconfig
(`kind get kubeconfig --name cartogra`), see `docs/runbooks/kubernetes-cluster-config.md` (Local, Option A).
Or call it through the gateway (`$TOKEN` from step 1; the gateway derives the ADMIN role, calling ingestion on :8085 directly gets "Access Denied"):

```bash
curl -s -X POST http://localhost:8080/api/v1/ingestion/k8s/clusters -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d "$(jq -n --arg kc "$(kind get kubeconfig --name cartogra)" '{name:"local",source:"KUBECONFIG",kubeconfig:$kc}')"
```

The worker bootstraps by scanning every tenant-labelled namespace, then keeps live watches on Services,
Endpoints and Namespaces. Within seconds the 20 services appear in the catalog.

## 4b. Source repos (SCM discovery, optional)

Creates one Azure DevOps repo per service (20 total) with the files the providers read: tech-stack markers
(`build.gradle.kts`, `package.json`, `requirements.txt`, `Dockerfile`), `openapi.yaml`, a README, and `CODEOWNERS` for the
17 owned services (the 3 orphans have none).

Prerequisites (one time):

```bash
az extension add --name azure-devops
az login
az devops project list --org https://dev.azure.com/<org> --query "value[].name" -o tsv   # the project must already exist
```

Create a PAT at `https://dev.azure.com/<org>/_usersSettings/tokens` (organization = your org, scopes **Code: Read & write**,
plus **Project and Team: Read** for Cartogra's scan). Git needs it because `az login` tokens are rejected by orgs backed by a
personal Microsoft account.

```bash
export ACME_AZURE_ORG=https://dev.azure.com/<org>
export ACME_AZURE_PROJECT=<project>
export ACME_AZURE_PAT=<pat>
pnpm acme repos      # creates missing repos, clones, rewrites generated files, pushes only when something changed
```

Idempotent: a second run reports everything unchanged. Failures to create a repo (missing `az`, not logged in, unknown
project) abort with the CLI's message. Other repos in the project are never touched.

### SCM connection

Connect Cartogra to the org so it scans the repos (through the gateway; `$TOKEN` from step 1):

```bash
curl -s -X POST http://localhost:8080/api/v1/ingestion/scm-connections \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d "$(jq -n --arg org "<org>" --arg pat "$ACME_AZURE_PAT" \
        '{provider:"azuredevops", config:({organization:$org, pat:$pat}|tojson), syncScheduler:true, pollIntervalMinutes:15}')" | jq .
```

`<org>` is the bare organization name (`allanweber`), not the URL. Take the connection `id` from the response and trigger a sync:

```bash
curl -s -X POST http://localhost:8080/api/v1/ingestion/scm-connections/<id>/sync -H "Authorization: Bearer $TOKEN" | jq .
```

The repos are named exactly like the K8s Services, so the registry merges both into the same 20 services (repo URL, tech stack
and last commit appear on the K8s-discovered rows). Your project's other repos will also show up as separate services; the
business plan allows 100.

### CODEOWNERS team mapping check (manual, roadmap 3.4)

`pnpm acme load` assigns owners through the API, and Registry never overrides an existing owner from CODEOWNERS — so a
normal run never exercises the CODEOWNERS path. To prove it, clear the owners (through the API, so `service.updated`
keeps Topology in sync) and let an SCM sync restore them. On a brand-new tenant, use `pnpm acme load --skip-owners`
instead of clearing.

```bash
for id in $(curl -s "http://localhost:8080/api/v1/registry/services?limit=100" -H "Authorization: Bearer $TOKEN" \
            | jq -r '.data.items[] | select(.teamId != null) | .id'); do
  curl -s -o /dev/null -X PATCH "http://localhost:8080/api/v1/registry/services/$id/owner" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"teamId":null}'
done
curl -s -X POST http://localhost:8080/api/v1/ingestion/scm-connections/<id>/sync -H "Authorization: Bearer $TOKEN" | jq .
curl -s "http://localhost:8080/api/v1/registry/services?limit=100" -H "Authorization: Bearer $TOKEN" \
  | jq --slurpfile seed seed-data.json \
    '[.data.items[] | select(.name as $n | $seed[0].services | map(.name) | index($n))]
     | {owned: map(select(.teamId != null)) | length, orphans: map(select(.teamId == null) | .name)}'
pnpm acme verify   # no "owner set:" lines = CODEOWNERS picked the seed's team for every service
```

Expect `owned: 17` and the 3 seed orphans, which also stay listed under `type: "orphan"` in `GET /api/v1/topology/risks`.
If a service stays unowned, grep the registry log for `ownership.resolved:` — it says whether no service matched the repo,
no team matched the handle, or several teams matched. `service.discovered` and `ownership.resolved` travel on separate
topics, so a first sync can occasionally see ownership before the service exists; a second sync fixes it. The same
mapping is asserted without an SCM org by `AcmeCodeownersMappingIT` (registry).

## 5. Load teams, owners, tiers, declared edges

```bash
export ACME_EMAIL=... ACME_PASSWORD=...
pnpm acme load            # waits (<= 2 min) for discovery to produce all 20 services, then adds the rest
```

Run it as often as you like: it is additive and idempotent. If discovery hasn't produced every service, it
exits non-zero and lists the missing ones.

## 6. Verify, then demo

```bash
pnpm acme verify                 # load twice + identical-state diff, /cycles = 2, SPOF, 3 orphans, observed edges
pnpm acme verify --with-drift    # also switches the 3 undeclared calls on and waits for the observed edges and 4 drift records
pnpm acme drift on               # demo: watch 3 edges nobody declared appear on /graph (Drift edge mode)
pnpm acme drift off
```

Observed edges take up to ~60 s (collector batch 5 s, graph view refresh 5 s, worker latency). Drift appears on the next
sweep: start Topology with `TOPOLOGY_DRIFT_SWEEP_INTERVAL=PT15S TOPOLOGY_DRIFT_MISSING_AFTER=PT2M` for the demo
(defaults are 5 minutes and 7 days). Resolve each drift from the graph's Drift tab: accept one, dismiss one, remove the declared one.

## Discovery behaviour checks

What `k8s-discovery-test.md` used to cover, now against the Acme namespace. Watch Kafka UI
(`http://localhost:8086`, topic `cartogra.ingestion.service.discovered`).

**Bootstrap**: after registering the cluster, one message per Service (20), with `source: kubernetes`,
`externalId: acme-fintech/<name>`.

| Endpoint state | `healthStatus` |
|---|---|
| All pods ready | `HEALTHY` |
| Mix of ready and not-ready | `DEGRADED` |
| No pods ready | `UNHEALTHY` |
| No endpoints resource | `UNKNOWN` |

**UNHEALTHY** (then recover):

```bash
kubectl -n acme-fintech scale deploy/notification-service --replicas=0
kubectl -n acme-fintech scale deploy/notification-service --replicas=1
```

**DEGRADED**: give a service two replicas, then delete one pod while it restarts:

```bash
kubectl -n acme-fintech scale deploy/fraud-service --replicas=2
kubectl -n acme-fintech delete pod -l app=fraud-service --wait=false
kubectl -n acme-fintech scale deploy/fraud-service --replicas=1
```

Scaling a service to 0 also stops its outbound spans, so its observed edges go stale until it is back.

**Negative (unlabelled namespace)**: no message may appear for `ghost-service`.

```bash
kubectl create namespace no-tenant
kubectl -n no-tenant create service clusterip ghost-service --tcp=80:80
```

**Opt-in after the worker started**: a message for `late-adopter/new-service` appears once the label is added.

```bash
kubectl create namespace late-adopter
kubectl -n late-adopter create service clusterip new-service --tcp=80:80
kubectl label namespace late-adopter cartogra.io/tenant-id=$ACME_TENANT_ID
```

Clean up the scratch namespaces: `kubectl delete namespace no-tenant late-adopter`.

## Commands

| Command | Does |
|---|---|
| `pnpm acme validate` | Prints seed counts (no network) |
| `pnpm acme manifests` | Writes the k8s yml (needs `ACME_TENANT_ID`, `ACME_OTLP_ENDPOINT`) |
| `pnpm acme repos` | Generates and pushes the 20 source repos (idempotent) |
| `pnpm acme load` | Teams, owners, tiers, declared edges via the gateway |
| `pnpm acme verify [--with-drift] [--no-spans]` | End-to-end checks against the live stack |
| `pnpm acme drift on\|off` | `kubectl port-forward` to each caller and toggles the dormant calls |
| `pnpm test` / `pnpm typecheck` | Unit tests (no network) / types |

## Environment

| Variable | Default | |
|---|---|---|
| `ACME_TENANT_ID` | | required for `manifests`, `load`, `verify` |
| `ACME_OTLP_ENDPOINT` | | required for `manifests` |
| `ACME_EMAIL` / `ACME_PASSWORD` | | tenant admin for `load` / `verify` |
| `ACME_API_URL` | `http://localhost:8080` | gateway |
| `ACME_IMAGE` | `acme-fintech-service:1.0.0` | must not be `latest` |
| `ACME_AZURE_ORG`, `ACME_AZURE_PROJECT` | | required for `repos` |
| `ACME_AZURE_PAT` | | git auth for `repos` (Code: Read & write) |
| `ACME_SEED_FILE` / `ACME_MANIFEST_OUT` | `seed-data.json` / `k8s/acme-fintech.yaml` | |

## How the simulation works

Every pod runs the same image (`app/server.ts`) configured by env. Each pod calls its downstream services on a
timer (5 s) with a CLIENT span (`peer.service`, `server.address`) and the receiving pod emits a SERVER span
with the propagated context. Handlers do not call further downstream, so the cycles never loop. Spans go to the
collector, which writes them to Kafka for `OtelSpanWorker`.

## Teardown

```bash
kubectl delete -f k8s/acme-fintech.yaml
kubectl delete namespace no-tenant late-adopter --ignore-not-found
```

Catalog rows stay until deleted in Cartogra. The generated repos stay too; delete them in GitHub / Azure DevOps.
