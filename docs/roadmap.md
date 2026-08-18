# Cartogra — Product Roadmap

> **Plan of record.** Supersedes `docs/execution-checklist.md`, which is kept for reference only.
> Written 2026-08-18 against commit `d6922fa`, after reading the code — not the old plan.

---

## 1. Where we actually are

Measured, not claimed:

| Area | State | Evidence |
|---|---|---|
| Gateway (identity, proxy) | **Live** | 111 java / 21 test classes. Local login + OTP, Google/GitHub OAuth, per-tenant OIDC, refresh tokens, invitations, admin user management, Redis rate limit, Spring Cloud Gateway routes with circuit breakers |
| Registry (catalog) | **Live** | 91 java / 28 test classes, 15 migrations. Service + Team CRUD, team members, ownership assign, history/snapshots, orphan list, health probing (`HealthProbeScheduler`, `RestClientHealthChecker`), plan limits, server-side pagination **and** Postgres FTS (`to_tsvector` + GIN index in V005) |
| Ingestion (discovery) | **Live** | 100 java / 31 test classes, 5 migrations. GitHub + Azure DevOps providers, repo scan, tech-stack detection, CODEOWNERS ownership, webhooks with signature verification, K8s cluster watch, sync jobs + stale-job reaper |
| Topology | **Skeleton only** | 25 java, 3 migrations, **0 tests**, no service class, no controller, no consumer. `dependencies`, `dependency_drifts`, `dependency_graph_edges` MV + refresh scheduler + advisory lock exist |
| Contract, Intelligence | **Empty directories** | 0 files |
| Frontend | **Partial** | Real data: catalog list/detail, teams, settings (connections, tenant, users). Mock data: dashboard, risks, contracts, timeline. Placeholders: graph, intelligence, ops. Honest stubs: settings/api-keys, settings/notifications |
| Kafka | **5 real topics** | `registry.service.{registered,updated,deleted}`, `registry.sync.command`, `ingestion.{service.discovered,ownership.resolved,sync.completed}` (+2 DLQs). `docs/architecture/kafka-topics.md` documents ~20 — the rest do not exist |
| CI | **Gap** | `ci.yml` and `ci-full.yml` have the topology/contract/intelligence build steps **commented out**. The newest code in the repo is never compiled or tested |

**Velocity:** 64 commits since 2026-06-04. June 24 · July 38 · August 2. Last feature commit 2026-08-05.
Phases 0+1 (gateway + registry + ingestion + auth/catalog UI) cost roughly 10 weeks at that pace. Every estimate below is calibrated against that, assuming one developer.

**Product position:** the catalog half of the product works. The differentiator — dependency intelligence — has schema and nothing else. Everything in this roadmap is ordered to close that gap first, because a catalog without a graph is a spreadsheet with OAuth.

---

## 2. Rules this roadmap follows

The old checklist broke each of these; that is why it stopped being usable.

1. **Linear.** A phase depends only on phases before it. No story references a later phase.
2. **One vertical per story.** API + UI + tests + OpenAPI ship together, or the story is not done.
3. **No screen ships on mock data.** A story that adds a screen removes its `MOCK_*` import.
4. **No topic without a consumer.** Producers are written when the consumer exists, not before.
5. **No story on data that does not exist.** If a story needs a column, an API, or an event that is not implemented, the story that creates it comes first — explicitly.
6. **Marketing is not a phase gate.** Content is a separate track (§12); it never blocks a release.
7. **Done means demoable.** Every phase ends with a scripted demo a stranger could follow.

---

## 3. Phase 0 — Make the repo honest (≈1 week)

No user-facing value. It exists because the next phases are unverifiable without it.

- **0.1 — Topology enters CI.** Uncomment the topology build steps in `.github/workflows/ci.yml` and `ci-full.yml`; `:services:topology:build` green on every push touching that path.
  *Done:* a deliberate compile error in topology fails CI.
- **0.2 — Topology test harness.** `AbstractTopologyIT` with Testcontainers Postgres, mirroring registry's. ITs for `JdbcDependencyRepository` (insert, soft delete, edge-identity uniqueness), `JdbcDependencyDriftRepository`, and the MV refresh path including advisory-lock contention between two callers.
  *Done:* topology jacoco report exists and CI uploads it like the other services.
- **0.3 — Restore the decision record.** `git checkout e6f0b45^ -- docs/adr` brings back ADR-0001…0021 plus README/TEMPLATE (deleted in "clean up", Jun 25, while ~40 references to them remain live in `CONTRIBUTING.md`, `CONTEXT-MAP.md`, six `CONTEXT.md` files, and published BIP posts). New ADRs continue at **0028**.
  *Done:* no dangling `ADR-00xx` reference in the repo.
- **0.4 — Docs match code.** `CONTEXT-MAP.md` topology status; `services/registry/CONTEXT.md` (claims V001–V011 and an `application/usecase` layer that no longer exists); `kafka-topics.md` cut to the 8 real topics with the rest moved to a clearly-marked "proposed" appendix; drop the "Notification worker" context that is not a module.
  *Done:* every table, topic, and package named in docs exists in code.
- **0.5 — Retire the old checklist.** Banner at the top of `docs/execution-checklist.md` pointing here.

---

## 4. Phase 1 — See the map (≈4 weeks)

**Goal:** a user can express and see how their services connect. This is the first time Cartogra does something a catalog cannot.

- **1.1 — Graph nodes projected from the registry.** *As the platform, I keep a local node list so graph queries never call the registry synchronously.*
  Topology consumes `cartogra.registry.service.{registered,updated,deleted}` into a `graph_nodes` table (`tenant_id`, `service_id`, `name`, `team_id`, `tier`, `health_status`, `deleted_at`), idempotent via a `(tenant_id, event_id)` dedupe table. Admin `POST /v1/internal/backfill` walks the registry once for tenants that predate the consumer.
  *Acceptance:* replaying an envelope is a no-op; `service.deleted` soft-deletes the node; ITs prove all three.
- **1.2 — Declared dependencies.** *As an engineer, I record that service A calls service B.*
  `POST/PUT/DELETE /v1/dependencies` validated against `graph_nodes` (404 unknown node, 409 duplicate edge, 422 self-edge — the CHECK constraint already exists). Every mutation calls `markDirty()` on the MV scheduler.
- **1.3 — Graph read API.** `GET /v1/graph?teamId=&type=&limit=` returns `{nodes[], edges[], truncated}` from `dependency_graph_edges` joined to `graph_nodes`. Hard node cap with `truncated: true` rather than an unbounded payload.
- **1.4 — Graph page.** D3 force-directed canvas replacing `PhasePlaceholder` in `routes/_authenticated/graph.tsx`: zoom, pan, node select, declared/observed toggle (observed empty until Phase 3), shadcn chrome, TanStack Query, `Skeleton` + `Alert`-with-`traceId`. Deletes `MOCK_GRAPH_NODES` / `MOCK_GRAPH_EDGES`.
- **1.5 — Dependencies on the service page.** Upstream/downstream lists on `catalog.$serviceId` with add/remove, so the graph is editable from where users already work.
- **1.6 — `docs/api/topology.openapi.yaml`** reduced to exactly the endpoints that exist.

**Demo:** register three services, draw two edges, open `/graph`, click a node, see its neighbours.

---

## 5. Phase 2 — Answer impact questions (≈4 weeks)

**Goal:** the graph stops being a picture and starts making decisions. This is the "seconds, not hours" promise in `PRODUCT.md`.

- **2.1 — Blast radius.** `GET /v1/blast-radius/{serviceId}?direction=&depth=` — recursive CTE over the MV, default depth 3 / max 6, visited-path array so cycles terminate, node cap with explicit truncation flag. Returns upstream and downstream with distance.
  *Acceptance:* fixture graph with a known 12-node fan-out returns exactly those 12; a 3-node cycle terminates.
- **2.2 — Impact panel + on-graph highlight.** Selecting a node dims everything outside its blast radius; side panel lists affected services grouped by distance, with owning team.
- **2.3 — Cycles.** `GET /v1/cycles` returning normalized, deduplicated cycles (rotation-invariant key). Cycle badge on the graph and on each member node.
- **2.4 — Single points of failure.** `GET /v1/spofs` — documented heuristic: fan-in ≥ threshold (default 5, configurable per tenant), weighted by `tier=CRITICAL` and by orphan status. The threshold and its rationale ship in the response payload, not just in docs.
- **2.5 — Risks page.** `GET /v1/risks` aggregating cycles + SPOFs + orphans into `{severity, type, title, description, affectedServices[]}`, paginated. `risks.tsx` drops `MOCK_RISKS`.
- **2.6 — Performance evidence.** 200-edge fixture; record graph read, blast radius p95, and MV refresh duration in `docs/architecture/topology-performance.md` with the hardware named.

**Demo:** "if payments-api goes down, what breaks?" answered in one click, with the owning teams listed.

---

## 6. Phase 3 — The map maintains itself (≈5 weeks)

**Goal:** remove manual upkeep — the reason internal service catalogs die.

- **3.1 — Observed edges from traces.** OTel Collector exports spans to `cartogra.observability.spans` (config in `infra/docker-compose/otel-collector.yml`); `OtelSpanWorker` derives `dependency_type='observed'` edges idempotently on the existing edge-identity index. Synthetic span fixtures work without a live collector. ADR-0028.
- **3.2 — Drift detection.** Declared-without-observed and observed-without-declared written to `dependency_drifts`; `GET /v1/drifts` + `POST /v1/drifts/{id}/resolve` (accept observed / remove declared / dismiss). Overlay on the graph plus a drift list.
- **3.3 — Orphan risk from ownership events.** Consume `cartogra.ingestion.ownership.resolved` (ADR-0027) so ownership changes update `graph_nodes.team_id` without a registry round-trip; orphans feed the risk list from 2.5.
- **3.4 — Topology events *(only if a consumer exists)*.** `dependency.declared/observed/removed` and `drift.detected` are **deferred to Phase 6**, where Intelligence consumes them. Rule 4.

**Demo:** run the seed traffic generator, watch an edge appear on the graph that nobody declared, resolve the drift.

---

## 7. Phase 4 — A product a stranger can try (≈4 weeks)

**Goal:** the first public artifact. Moved forward from the old plan's Phase 5 — nothing is validated until someone outside can click it.

- **4.1 — Audit trail.** `audit_events` migration in registry (`entity_type`, `entity_id`, `action`, `actor_id`, `payload` JSONB, RLS, GIN); `AuditEventPort` in `shared:common`; registry writes directly, topology publishes `cartogra.platform.audit.recorded` for registry to consume. Admin `GET /v1/audit-events` filterable + paginated. `timeline.tsx` drops `MOCK_TIMELINE`.
- **4.2 — Dashboard on real data.** Health summary from registry, top risks from 2.5, recent activity from 4.1. `dashboard.tsx` drops all `MOCK_*`. Vitest covers loading/error/empty.
- **4.3 — Acme Fintech seed.** `seed/seed-data.json`: 20 services, 5 teams, 3 orphans, 2 cycles, 1 god service (12 dependents), 1 declared/observed drift. Idempotent loader against the real APIs.
- **4.4 — Guest read-only mode.** Gateway issues a short-lived `roles=["guest"]` token bound to the demo tenant; no anonymous path. Audit every mutating endpoint in registry + topology for `@PreAuthorize`; IT proves guest reads succeed and every write is 403. ADR-0029.
- **4.5 — One public environment.** Cheapest thing that holds the demo: the existing `docker-compose.yml` on a single host behind TLS on `cartogra.dev`, documented in `docs/runbooks/deployment.md`. Kubernetes waits for Phase 7 — do not build a cluster to host a demo.

**Demo:** send someone a link. They browse Acme Fintech, run a blast radius, and cannot change anything.

---

## 8. Phase 5 — Change safety: contracts (≈7 weeks)

**Goal:** the second product pillar — catch breaking API changes before merge. Start with the smallest loop that a CI pipeline can block on.

- **5.1 — Contract store.** Contract service skeleton + `api_contracts`, `contract_versions`, `contract_consumers`, `contract_checks`. `POST /v1/contracts` and `/versions` parse and validate OpenAPI 3 / AsyncAPI 2, canonicalize, store `spec_hash`. Invalid specs return envelope errors.
- **5.2 — Diff + timeline.** `GET /v1/contracts` (paginated) and `/versions/{v}/diff` returning a structured diff. Contract list + side-by-side diff + version timeline in the UI; `contracts.tsx` drops `MOCK_CONTRACTS`.
- **5.3 — Breaking-change engine.** Removed fields, type changes, newly-required fields, enum removals → `is_breaking` + `changes[]`. Golden tests over compatible and breaking pairs for both spec types.
- **5.4 — Consumers, manually first.** `POST/DELETE /v1/contracts/{id}/consumers` with `evidence_type='manual'`, surfaced in contract detail and feeding `affected_consumers`. Observed consumers derived from spans are a **Phase 6 story**, after the manual path proves the model.
- **5.5 — CI check.** Tenant API keys (hashed, scoped; the migration lands in the **registry** schema — the gateway has no Flyway setup today, contrary to the old plan) + `POST /ci/check` authenticated by `X-Cartogra-Api-Key` with scope `ci:check`. API key management page replaces the "coming soon" stub.
- **5.6 — GitHub Action.** One CI integration, published under `ci-extensions/`, blocking on breaking changes. Azure Pipelines only when a user asks for it.
- **5.7 — Slack notification on breaking checks.** One channel end to end (Resend email already exists as the second). `notification_rules` + `notification_log` + the settings drawer replacing the stub. Teams on demand.
- **5.8 — Spec discovery.** Ingestion publishes `cartogra.ingestion.spec.discovered` after each sync; contract consumes idempotently on `(tenant_id, file_path, content_sha256)` into 5.1. Last, because it is worthless until the pipeline it feeds works.

**Demo:** open a PR that deletes a required field; CI fails with the affected consumers named; Slack shows it.

---

## 9. Phase 6 — Intelligence (≈5 weeks)

**Goal:** explanation on top of evidence. Deliberately after Phases 1–5, because an LLM narrating an empty graph is a demo, not a product.

- **6.1 — Deterministic findings.** Intelligence service + `analysis_runs`, `anti_pattern_findings`. Circular dependency, god service, orphaned service — each with structured evidence (cycle path, fan-in count, days since last commit). Fixture ITs assert exact findings on known shapes.
- **6.2 — Narrative layer.** Claude client with externalized prompts, Redis cache, per-tenant rate limits, token/latency persisted per request. The model writes prose over 6.1's evidence and never invents a fact. ADR-0030.
- **6.3 — Findings feed.** `POST /v1/intelligence/analyze`, `GET /findings`, acknowledge/resolve. `intelligence.tsx` replaces its placeholder.
- **6.4 — NL query, guarded.** Allowlisted views only, read-only role, bound parameters, row limit, statement timeout. `POST /v1/intelligence/query` returns `{answer, data?, generated_sql?, query_id}` + feedback endpoint. Committed eval set under `src/test/resources/nl-eval/` with a stated pass bar.
- **6.5 — Health score + weekly digest**, persisted as trends, surfaced in the same panel.
- **6.6 — Observed consumers** (deferred from 5.4): match span peer URLs against contract servers/base paths to populate `contract_consumers` with `evidence_type='observed'`.
- **6.7 — Topology events published** (deferred from 3.4), now that Intelligence consumes them.

**Cut from the old plan, with reasons:** *AI ownership suggestions* assumed `ScmProvider.getCommitHistory` — the interface has `getLastCommit` only, so the prerequisite is a new SCM API and 90 days of commit aggregation; it is not a story until someone builds that. *Anomaly detection* assumed `services.last_deploy_at` history — no such column, no deploy-event ingestion, no history table. Both return as candidates once their data sources exist.

---

## 10. Phase 7 — Production hardening (≈5 weeks)

Triggered by real users, not by a calendar. Before this, Phase 4's single host is enough.

- **7.1** Helm charts per service + umbrella, with the probes, resources, and security contexts already mandated in `.claude/rules/infra.md`.
- **7.2** Terraform modules + one staging environment + remote state (S3 + DynamoDB lock + encryption + versioning).
- **7.3** Staging deploy pipeline + `:smoke:test` (readiness on every service, one authenticated call each, envelope conformance) gating the deploy.
- **7.4** NetworkPolicy restricting services to gateway-namespace ingress + short-lived `X-Gateway-Token` validated by a shared filter in `shared:common`; IT proves direct access is rejected.
- **7.5** DLQ (`cartogra.platform.dead-letter`) + admin replay writing audit events; IT proves poison → DLQ → replay.
- **7.6** Grafana dashboards auto-provisioned per service + platform overview; alerts on consumer lag, error rate, MV refresh duration.
- **7.7** Playwright E2E (guest browse, graph exploration, contract check, one NL query) + k6 thresholds committed under `perf/results/`.
- **7.8** Docs site with the ADR index and runbooks.

---

## 11. Phase 8 — Commercial (only when someone wants to pay)

Partially built already: `billing_plans` (V010/V013), `tenants.plan_id` (V011), `PlanLimitService`, and the plan-limit advisory lock exist.

- **8.1** Stripe checkout + customer portal + webhook receiver; `tenant_subscriptions`, `billing_events`; `PlanEnforcementFilter` with a Redis-cached plan tier returning 402 on overage.
- **8.2** `/settings/billing` page.
- **8.3** GDPR erasure: admin + re-auth, anonymize users and audit events, `tenant.erased` event, 30-day hard delete, signed receipt.

Moved out of the old Phase 3 because none of it tests the product hypothesis, and all of it blocked contract work behind Stripe.

---

## 12. Not in the plan

**Content track (parallel, never blocking).** The old checklist carried 31 build-in-public items — a third of all remaining work — and made phase gates depend on them. Write posts when a phase ships something worth showing; a missed post never blocks a release.

**Removed as unexecutable or already done:**

| Old item | Why it is gone |
|---|---|
| Gate criteria requiring `/impeccable audit`, `/improve`, `/shape` | External npm-installed skills, not committed to the repo; a gate that cannot be re-run is not a gate |
| "Write ADR-0016…0021" | Those numbers belong to ADRs deleted in `e6f0b45`; new ADRs start at 0028 |
| 2.10 full-text search, 2.11 pagination | Already shipped — `to_tsvector` + GIN index (V005), `limit`/`offset` on services and teams, `search` wired in `catalog.index.tsx`. Only audit-event pagination remained, and it moved into 4.1 |
| Gateway-owned Flyway migrations (API keys, billing) | Gateway has no Flyway and shares the `registry` schema; the plan assumed an ownership split that does not exist |
| ~12 speculative Kafka topics + "Notification worker" context | No producer, no consumer, no module. Violates the project's own no-speculative-topics rule |
| Azure Pipelines extension, Teams channel, dual-marketplace listing | Duplicate integrations before the first one has a user |

**Parking lot (research, unscheduled):** gRPC for internal calls, Avro + Schema Registry, SSE/WebSocket live graph updates, multi-region, users in multiple tenants, tenant-level OIDC beyond what ships today.

---

## 13. Sequencing at a glance

```
P0 repo truth  →  P1 see the map  →  P2 answer impact  →  P3 self-maintaining
                                                              │
                                            P4 public demo ◄──┘
                                                 │
                                     P5 contracts + CI check
                                                 │
                                        P6 intelligence
                                                 │
                                    P7 production   →   P8 commercial
```

Cumulative: **P0–P4 ≈ 18 weeks** to a public, self-maintaining dependency map with an audit trail — the smallest thing that is recognisably Cartogra rather than a catalog. P5 adds the contract pillar, P6 the intelligence pillar.

Estimates assume one developer at the June–July pace (~30 commits/month). At August's pace they are meaningless — if the gap persists, cut Phase 3 to 3.1+3.2 and Phase 5 to 5.1–5.3 rather than stretching every phase.
