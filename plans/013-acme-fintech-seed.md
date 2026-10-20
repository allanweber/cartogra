# 3.2 — Acme Fintech seed
Issue: #127  ·  Branch: `feat/3.2-acme-fintech-seed`  ·  Save to: `plans/013-acme-fintech-seed.md`

## Context
Phase 3 needs a believable tenant: a fintech SaaS that Cartogra monitors, with known risks (orphans, cycles, SPOF, drift). Today only `seed/graph-perf-seed.sql` exists (raw SQL into registry/topology tables, bypasses APIs and Kafka). 3.2 replaces it with **Acme Fintech**: a standalone simulation that runs in the **kind** cluster, registers its catalog through the real gateway API, and emits real OTel spans through the collector so observed edges and the drift come from 3.1's pipeline.

Facts from exploration:
- Gateway `:8080`; no default users. Free plan caps 10 services / 2 teams, so the tenant must be on a business plan. No API changes plan, so the **user creates/verifies/upgrades the tenant manually** (README steps).
- Registry: `POST /teams {name}`; `PUT /services/{id}` for tier (not on create; the loader never creates services); `PATCH /services/{id}/owner`; duplicate name = 409. Topology: `POST /dependencies {sourceServiceId, targetServiceId, protocol:HTTP}`; duplicate = error; no list-all endpoint, so diff via `GET /graph?type=DECLARED`.
- `graph_nodes` is filled async from registry Kafka events: `POST /dependencies` fails until the node exists. Graph MV lags up to ~5 s (`PT5S`).
- Spans: `OtelSpanWorker` maps CLIENT span `peer.service` (else `server.address`) to target, `service.name` is the source, names matched case-insensitively against `graph_nodes`; tenant from resource attr `cartogra.tenant.id` (3.1 D2). Collector OTLP/HTTP `:4318`, batch 5 s; e2e allows 40 s end to end. Collector drops `service.name == "topology"` only.
- Ingestion's `KubernetesWorker` registers every Service in a namespace labelled `cartogra.io/tenant-id` (needs a registered cluster). It does **not** map team labels.
- Existing `seed/k8s-discovery-manifests.yaml` uses namespace `prod`; Acme uses its own namespace.
- `dependency_drifts` is not written by anything until 3.3. The seeded drift is purely data (an observed edge with no declared edge).

## Decisions
| # | Decision | Choice | Why |
|---|---|---|---|
| D1 | Standalone | `seed/acme-fintech/`, own `package.json`/pnpm, TS/Node, **no imports from Cartogra code** | Acme is a customer, not part of Cartogra |
| D2 | Simulation | Real fake microservices in kind: ONE generic image, 20 Deployments driven by `seed-data.json` | Realistic, but cheap: cycles/god/drift are config |
| D3 | Source of truth | `seed-data.json` (teams, services, declared edges, dormant undeclared call) | One place to edit |
| D4 | Tenant/auth | Manual tenant setup (README). Loader logs in via `ACME_EMAIL`/`ACME_PASSWORD`, asserts `GET /api/auth/tenant` id == `ACME_TENANT_ID` | No plan API; OTP only in DB |
| D5 | Tenant id in yml | `pnpm acme manifests` requires `ACME_TENANT_ID` and bakes it into the namespace label `cartogra.io/tenant-id` and the pods' `ACME_TENANT_ID` env | User gives the yml the tenant id before applying |
| D6 | Namespace | New `acme-fintech`, **with** the discovery label; the user registers the kind cluster in ingestion (README) | Services come from k8s only |
| D7 | Service source | **Loader never POSTs /services.** Discovery creates the 20 services (k8s Service name = seed name). Loader waits for all 20 (timeout), fails with a clear error if missing | User decision: services must come from k8s |
| D8 | Traffic | Each pod: independent timer (default 5 s) calls each enabled downstream; server handler returns 200 and does **not** call downstream | Cycles cannot loop |
| D9 | Spans | Manual OTel spans: CLIENT (`http.request.method`, `peer.service`, `server.address`) and SERVER with W3C propagation; resource `service.name`, `cartogra.tenant.id`; OTLP/HTTP to `ACME_OTLP_ENDPOINT` | Matches `OtlpSpanMapper` |
| D10 | Drift | 1 call in the JSON with `declared:false, enabled:false`. `POST /admin/calls/{target}/enable\|disable` on the caller; `pnpm acme drift on\|off` uses `kubectl port-forward` | "Watch an edge appear" demo, reversible |
| D11 | Loader semantics | Additive and idempotent (never deletes). Create missing **teams**, PUT tier only when different (send the service's current fields + tier), PATCH owner when different (orphans: none), POST edges missing from `GET /graph?type=DECLARED` | Discovery can't set teams/owners/tiers/edges |
| D12 | Verification | Vitest invariants + `pnpm acme verify` against a live stack; dev only, not CI, not in frontend e2e | Chosen |
| D13 | Replace old seed | Delete `seed/graph-perf-seed.sql`; perf evidence stays on `perf/graph-perf-200-seed.sql` | Roadmap says replace |
| D14 | Image | `seed/acme-fintech/Dockerfile` multi-stage, pinned `node` tag (no `latest`), non-root uid 1000, loaded with `kind load docker-image --name cartogra` | `infra.md` rules |

## Implementation steps
Layout `seed/acme-fintech/`: `package.json`, `tsconfig.json`, `Dockerfile`, `README.md`, `seed-data.json`, `src/cli.ts`, `src/schema.ts`, `src/api.ts`, `src/load.ts`, `src/manifests.ts`, `src/drift.ts`, `src/verify.ts`, `app/server.ts`, `app/tracing.ts`, `test/*.test.ts`; `.gitignore` for `node_modules/`, `k8s/` (generated, contains tenant id).

1. **`seed-data.json` + `src/schema.ts`** (zod/hand validation, shared by loader, manifests, app):
   - 5 teams; 20 services (kebab-case fintech names such as `ledger-service`; tiers mixed CRITICAL/STANDARD/EXPERIMENTAL); 3 with `team: null`.
   - `calls[]`: `{from, to, declared, enabled}`. Declared edges form exactly **2 simple cycles** (no extra cycles from other edges), **1 god service** with 12 distinct declared dependents, and **1** call `{declared:false, enabled:false}` that adds no new cycle.
   - Validation: unique names, refs resolve, no self edges, counts as above.
2. **`app/` generic fake service** (env: `SERVICE_NAME`, `ACME_TENANT_ID`, `ACME_OTLP_ENDPOINT`, `ACME_CALLS` JSON, `ACME_CALL_INTERVAL_MS`): HTTP `/health`, `/work` (SERVER span, 200), `/admin/calls/:target/enable|disable`; timer loop calling `http://<target>.acme-fintech.svc.cluster.local:8080/work` with CLIENT span + `traceparent`.
3. **`src/manifests.ts`**: generate `k8s/acme-fintech.yaml` (Namespace + labels `cartogra.io/tenant-id`, 20 Deployment + ClusterIP Service). Per `infra.md`: requests and limits, startup/liveness/readiness probes on `/health`, `runAsNonRoot` uid 1000, `readOnlyRootFilesystem`, `allowPrivilegeEscalation:false`, drop ALL, `/tmp` emptyDir, labels `app`,`version`,`environment`,`team`, image `acme-fintech-service:<version>` (IfNotPresent). Pod env carries the service's enabled calls.
4. **`src/api.ts` + `src/load.ts`** (`pnpm acme load`): login (Bearer, cookie fallback), tenant assertion, poll `GET /services` (paged, match by lowercase name) until the 20 discovered services exist (timeout ~120 s, else error naming the missing ones and pointing at cluster registration), create teams, PUT tier / PATCH owner per D11, poll `GET /graph` until nodes carry expected tier/team, declared edges with retry/backoff on unknown-node, summary of created/unchanged. Reuse the payload shapes in `postman/*.postman_collection.json`.
5. **`src/drift.ts`** (`pnpm acme drift on|off`): resolve caller/target from JSON, spawn `kubectl -n acme-fintech port-forward deploy/<caller> <random>:8080`, POST admin, tear down.
6. **`src/verify.ts`** (`pnpm acme verify [--with-drift]`): run load twice; snapshot normalised teams/services/declared edges before and after the second run and diff; assert `GET /cycles` = 2, `GET /spofs` contains the god service, `GET /risks` contains the 3 orphans, `GET /graph?type=DECLARED` = expected edges, and, once pods run, `GET /graph?type=OBSERVED` contains the declared calls; with `--with-drift` enable the call, poll up to 60 s for the new observed edge, then disable.
7. **README.md**: prerequisites (kind cluster `cartogra`, infra + gateway + registry + topology + collector up); manual tenant steps (register, read OTP from `registry.users`, verify, upgrade `registry.tenants` plan to business, note tenant id); env vars; build and `kind load docker-image`; reaching the host collector from kind pods (`ACME_OTLP_ENDPOINT`, bridge gateway IP from `docker network inspect kind`); steps `manifests` → `kubectl apply` → `load` → **required** cluster registration in ingestion (point to `seed/k8s-discovery-test.md` Step 2) so discovery creates the services → `verify` → `drift on`.
8. **Cleanup**: delete `seed/graph-perf-seed.sql`; update `docs/architecture/topology-performance.md:37` and comments at `perf/graph-perf-200-seed.sql:10,117`; `docs/roadmap.md` 3.2 text (kind, traffic generator = fake services, `pnpm acme` commands) and tick the "loader run twice" gate after verify passes; add `seed/acme-fintech` mention to `CONTEXT-MAP.md` or `docs/agents` only if a seed section exists.
9. **Image scan**: ensure the Dockerfile base is pinned and clean for the Trivy PR gate (pin patched deps as in recent security commits).

## Acceptance criteria
- [ ] `seed-data.json` validates: 20 services, 5 teams, 3 unowned, exactly 2 cycles in declared edges, god service with 12 dependents, 1 dormant undeclared call.
- [ ] With the cluster registered and manifests applied, discovery produces the 20 services; `pnpm acme load` then creates 5 teams, sets tiers/owners and all declared edges. The loader issues no `POST /services`.
- [ ] Without discovery data the loader exits non-zero listing the missing services.
- [ ] Running `load` twice leaves identical state (`verify` diff empty).
- [ ] `manifests` fails without `ACME_TENANT_ID`; output has namespace label, probes, resources, security contexts, no `latest`.
- [ ] After `kubectl apply`, observed edges for the declared calls appear in `GET /graph?type=OBSERVED` within ~60 s.
- [ ] `/cycles` reports 2, `/spofs` flags the god service, `/risks` lists the 3 orphans.
- [ ] `drift on` makes an observed edge with no declared counterpart appear on the graph; `drift off` stops new spans.
- [ ] `seed/graph-perf-seed.sql` removed and no dangling references.
- [ ] README lets the user reproduce everything, including manual tenant setup.

## Tests
- **Vitest (no network):** seed-data invariants (counts, cycles via own SCC check, god dependents, refs); loader against an in-memory fake API (second run no-op, never POSTs services, fails when services missing, 409 skip, retry on unknown node); manifest generation (tenant label, probes, resources, securityContext, no `latest`, env calls); fake-service call scheduler and admin toggle.
- **Live (`pnpm acme verify`):** idempotency diff and graph/risk assertions above.
- No Playwright e2e and no CI wiring (D12).

## Verification
```
cd seed/acme-fintech && pnpm install && pnpm test && pnpm typecheck
docker build -t acme-fintech-service:<version> . && kind load docker-image acme-fintech-service:<version> --name cartogra
ACME_TENANT_ID=<id> ACME_OTLP_ENDPOINT=http://<bridge-ip>:4318 pnpm acme manifests && kubectl apply -f k8s/acme-fintech.yaml
ACME_EMAIL=... ACME_PASSWORD=... pnpm acme load && pnpm acme verify && pnpm acme drift on
```
Manual: open `/graph` (declared + observed overlay), `/risks`, confirm the new undeclared edge after `drift on`.

## Out of scope
Drift detection and `/v1/drifts` (3.3); team members; grpc/kafka/db edges (3.7); UI changes; automated tenant/plan provisioning; CI wiring and Playwright coverage; hosted/public seed (Phase 4); chained request propagation between services.

## Open verification points (resolve while implementing)
- Confirm the gateway accepts `Authorization: Bearer` for `/api/v1/**` (else use the `jwt` cookie).
- Confirm `GET /cycles` counts simple cycles the way the seed's 2 cycles are defined; adjust the edge design, not the definition.
- Confirm how the gateway maps `/api/v1/registry/*` and `/api/v1/topology/*` paths (check `postman/` collection).

## Addendum — consolidation (one seed)
Decided after implementation: Acme also replaces `seed/create-test-repos.sh`, `k8s-discovery-manifests.yaml`, `k8s-discovery-teardown.sh` and `k8s-discovery-test.md`.
- New `pnpm acme repos` (`src/repos.ts`): 20 generated repos (15 GitHub, 5 Azure DevOps; java/node/python markers, openapi, README, CODEOWNERS for owned services), env-configured orgs, idempotent clone-rewrite-push. Registry merges SCM and K8s discoveries by service name.
- `seed-data.json` services gain `repo: {provider, stack, port}`.
- Discovery behaviour checks (health transitions, negative namespace, late opt-in) live in the Acme README against the `acme-fintech` namespace.
- `seed/start-fake-service.sh` (host health-endpoint debug helper) and `seed/install-kind-kubectl.md` are kept.
