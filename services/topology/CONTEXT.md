# Topology Context

**Service**: `services/topology` · Port `8082` · Phase 1 · **Live**

---

## Purpose

Topology maintains the live dependency graph of all Services within a tenant. It computes blast radius, detects cycles, identifies single points of failure (SPOFs), and surfaces drift between declared and observed dependencies. Graph queries use recursive CTEs against PostgreSQL — no graph database.

---

## Ubiquitous Language

| Term | Meaning in this context |
|---|---|
| **Dependency** | A directional edge from a **source** Service to a **target** Service; has a type (declared/observed) and a protocol |
| **Declared dependency** | A dependency explicitly registered by a developer (via the API or spec file) |
| **Observed dependency** | A dependency inferred from OTel trace spans by `OtelSpanWorker` in Topology (ADR-0029); carries `last_seen_at` |
| **Blast radius** | The set of Services reachable from a given Service within a bounded hop count, in both directions: `upstream` (what it depends on, forward traversal) and `downstream` (what would be affected if it degraded or went down, backward traversal) — computed via a recursive CTE over `dependency_graph_edges`. Note: this `upstream`/`downstream` pairing is the opposite of the one used by the 1-hop `/dependencies/{serviceId}` endpoint |
| **Cycle** | A set of Services in a circular dependency chain; indicates an architectural anti-pattern |
| **Drift** | A mismatch between declared and observed dependencies (`undeclared` = observed but not declared; `missing` = declared but not observed) |
| **Unresolved span service** | A `peer.service` name seen on CLIENT spans that matches no catalog Service (e.g. an external SaaS API). Listed by `seen_count` until a Service with that name is registered (`resolution='registered'`) or a user dismisses it (`resolution='dismissed'`, never re-recorded) |
| **SPOF** | Single Point of Failure — a Service with no redundancy that sits on the critical path of many other Services |
| **Graph snapshot** | The full dependency graph state at a point in time; published to Kafka after every update |
| **Orphan** | A Service with no team owner (`team_id IS NULL`); `team_id` is mirrored from Registry via the existing `service.{registered,updated}` lifecycle sync (ADR-0028, superseding ADR-0027's plan for a dedicated topic), used for orphan risk and SPOF severity |
| **Risk** | A single finding surfaced on `/v1/risks`: one of SPOF, cycle, drift, or orphan, each carrying `severity`, `type`, `affectedServices[]` (IDs), `title`, `explanation`, and `fix` |

---

## Core Responsibilities

1. **Dependency CRUD** — create, read, soft-delete declared dependencies
2. **Observed edge ingestion** — `OtelSpanWorker` consumes raw OTLP/JSON from `cartogra.observability.spans` and upserts observed edges (ADR-0029): `http`/`grpc` from CLIENT↔SERVER spans, `kafka` from PRODUCER/CONSUMER spans joined on the topic name, `db` from CLIENT spans with `db.system` whose DB matches a catalog service
3. **Blast radius computation** — recursive CTE traversal downstream from a given node
4. **Cycle detection** — recursive CTE looking for back-edges
5. **Drift detection** — `DriftService` owns the lifecycle: a sweep (one instance at a time, REPEATABLE READ per tenant) compares declared vs. observed pairs and raises *undeclared* (observed within `topology.drift.missing-after`, never declared) or *missing* (declared longer than that, not observed since); users accept/remove/dismiss; dependency changes go through `DependencyService` pair operations
6. **SPOF scoring** — identify highly-connected nodes on critical paths
7. **Graph events** — *deferred to Phase 6 (roadmap 3.5 → 6.7)*: `graph.updated`, `drift.detected`, `cycle.detected` are not produced until Intelligence consumes them
8. **Orphan flagging** — read `team_id IS NULL` off the existing `graph_nodes` projection (ADR-0028) to flag orphan status; no separate ownership topic
9. **Risk aggregation** — combine SPOF + cycle + drift + orphan into the paginated `/v1/risks` list — [2.4]/[2.5]

---

## Domain Model

```
Service (ID ref from Registry) ──< Dependency ──> Service (ID ref)
Dependency ──○ DependencyDrift
```

Cross-context references stored as IDs only — Topology never hydrates a `Service` object from Registry.

**Tables** (Phase 2 schema):

| Table | Purpose |
|---|---|
| `dependencies` | Directional edges; `dependency_type`: declared/observed; `protocol`: http/grpc/kafka/db |
| `dependency_drifts` | Drift records: `drift_type`: undeclared/missing; `detected_at`/`resolved_at`/`resolved_by`; `resolution`: accepted/removed/dismissed/auto (auto = condition cleared on its own) |
| `pending_span_links` | Join buffer for span halves (ADR-0029): CLIENT/SERVER halves parked until their partner arrives (`topology.span-links.ttl`, default 5 min), and PRODUCER/CONSUMER endpoints per (topic, kind, service), kept for `topology.span-links.messaging-ttl` (default 24 h); expired rows deleted by `PendingSpanLinkReaperScheduler` every `topology.span-links.reap-interval` |
| `unresolved_span_services` | Unknown `peer.service` names from spans, capped per tenant (`topology.span-links.max-unresolved-per-tenant`); `seen_count`/`last_seen_at`; soft-deleted with `resolution`: dismissed/registered and `resolved_by` |
| `graph_nodes` | Local projection of Registry services (`GraphNodeEventConsumer` — [1.1]); one row per (tenant_id, service_id), soft-deleted on `service.deleted` |
| `processed_events` | Consumer-side idempotency ledger, keyed `(tenant_id, event_id)` — a replayed envelope is a no-op |

---

## Inbound Ports (API)

Implemented: backfill, dependency CRUD, graph read (`[1.1]`–`[1.3]`), blast radius (`[2.1]`), cycles (`[2.3]`), SPOFs (`[2.4]`), risks (`[2.5]`). Drift detection and resolution (`[3.3]`). Unresolved span services (`[3.6]`).

| Method | Path | Description |
|---|---|---|
| POST | `/internal/backfill` | Admin: walk Registry once via `GraphNodeService.backfill()`, seeding `graph_nodes` for tenants that predate `GraphNodeEventConsumer` — [1.1] |
| POST | `/api/v1/topology/dependencies` | Declare a dependency — validated against `graph_nodes` (404 unknown/soft-deleted node, 422 self-edge, 409 duplicate, 403 unauthorized); always `dependency_type=declared` — [1.2] |
| PUT | `/api/v1/topology/dependencies/{id}` | Full replace of a declared dependency; same validation as POST, re-checked against both the old and new source/target pair — [1.2] |
| GET | `/api/v1/topology/dependencies` | List dependencies for tenant |
| DELETE | `/api/v1/topology/dependencies/{id}` | Remove a declared dependency (soft delete) — [1.2] |
| GET | `/api/v1/topology/graph` | `{nodes[], edges[], truncated}` joining `graph_nodes` to the `dependency_graph_edges` MV; optional `teamId`/`type`/`limit`; 500-node hard cap; no per-team access control (read-only) — [1.3] |
| GET | `/api/v1/topology/blast-radius/{serviceId}` | `{serviceId, upstream: {entries[], depthTruncated, nodesBeyondDepth, nodeCapTruncated, nodesBeyondCap}, downstream: {...}, maxDepth}`; recursive CTE over `dependency_graph_edges`, cycle-safe via a visited-path array; optional `direction` (`UPSTREAM`/`DOWNSTREAM`) and `depth` (default 3, clamped to max 6); 200-node cap per direction; 404 unknown/soft-deleted node, 400 `depth < 1` — [2.1]. **Direction naming is the opposite of the `/dependencies/{serviceId}` upstream/downstream above**: here `upstream` = what this service depends on, `downstream` = who is impacted if it fails — see `BlastRadiusService`'s Javadoc |
| GET | `/api/v1/topology/cycles` | Elementary cycles, rotation-deduplicated; optional `type` filter — [2.3] |
| GET | `/api/v1/topology/drifts` | Active drift records, paginated (`limit` ≤ 200, `offset`) — [3.3] |
| POST | `/api/v1/topology/drifts/{id}/resolve` | `{action: ACCEPT_OBSERVED \| REMOVE_DECLARED \| DISMISS}`; accept only for `undeclared`, remove only for `missing` (422 `INVALID_DRIFT_ACTION`); already resolved 409 `DRIFT_ALREADY_RESOLVED`; accept with no live observed edge left 409 `DRIFT_NO_LONGER_OBSERVED`; ADMIN or team member on either side; dismissed drift is never re-raised — [3.3] |
| GET | `/api/v1/topology/spofs` | Fan-in ≥ tenant-configurable threshold (default 5); severity escalates to CRITICAL for CRITICAL-tier or orphan services; threshold + rationale ship in the payload — [2.4] |
| GET | `/api/v1/topology/unresolved-span-services` | Live unresolved span services, sorted by `seen_count` desc, paginated (`limit` ≤ 200, `offset`) — [3.6] |
| POST | `/api/v1/topology/unresolved-span-services/{id}/dismiss` | Soft delete, sticky (never re-recorded from spans); any tenant user, recorded in `resolved_by`; already resolved 409 `UNRESOLVED_SPAN_SERVICE_ALREADY_RESOLVED` — [3.6] |
| GET | `/api/v1/topology/risks` | Paginated SPOF + cycle + drift + orphan findings, severity-sorted — [2.5] |

---

## Kafka

**Consumed:**

| Topic | Source | Action |
|---|---|---|
| `cartogra.registry.service.registered` | Registry | Create node in graph (no-op if already exists) |
| `cartogra.registry.service.updated` | Registry | Refresh node metadata cache, including `team_id`/`tier` (used for orphan risk and SPOF severity — ADR-0028) |
| `cartogra.registry.service.deleted` | Registry | Soft-delete all edges for that service |
| `cartogra.observability.spans` | OTel Collector | Raw OTLP/JSON, not an `EventEnvelope`; upsert observed edges; unattributable records go to `cartogra.observability.spans.dlq` (ADR-0029) |

**Produced:**

| Topic | Trigger |
|---|---|
None today. The only producer is the span DLQ (`cartogra.observability.spans.dlq`). Topology lifecycle events are deferred to Phase 6 (roadmap 3.5 → 6.7) until a consumer exists:

| Topic (deferred) | Trigger |
|---|---|
| `cartogra.topology.graph.updated` | After any dependency change |
| `cartogra.topology.drift.detected` | When drift is raised |
| `cartogra.topology.cycle.detected` | When a cycle is found in the graph |

---

## Context Relationships

| Neighbour | Relationship | Notes |
|---|---|---|
| Service Catalog (Registry) | Downstream (Conformist) | Consumes registry lifecycle events; references service IDs only. `team_id`/`tier` from those same events also feed orphan risk and SPOF severity (ADR-0028) — no separate topic. Additionally calls Registry's internal `POST /internal/services/access` synchronously (direct service-to-service, bypasses the Gateway) to authorize declared-dependency mutations — "is this user a member of the team owning this service?" — fail closed on error — [1.2] |
| OTel Collector | Downstream (Conformist) | Consumes its OTLP/JSON span export; Ingestion is not on this path |
| Intelligence | Upstream (Customer/Supplier) | Produces graph + drift + cycle events |
| Identity & Access (Gateway) | Conformist | Receives proxied requests with `X-Tenant-Id` |
| Shared Kernel | Shared Kernel | `EventEnvelope`, `ApiResponse`, `ErrorCodes` |

---

## ADRs

- ADR-0001 — PostgreSQL + recursive CTEs (no graph database)
- ADR-0016 — OTel span worker ingestion path — **superseded by ADR-0029**
- ADR-0029 — Observed edges from OTel spans: raw OTLP topic, tenant stamping, span-id join fallback
- ADR-0027 — Topology consumes Registry `ownership-changed` events for orphan risk (chosen over a client-side merge) — **superseded by ADR-0028**
- ADR-0028 — Topology reads `graph_nodes.team_id` directly for orphan risk (no new topic needed; the existing lifecycle sync already carries it)
