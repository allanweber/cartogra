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
| **Observed dependency** | A dependency inferred from OTel trace spans collected by the Ingestion worker (ADR-0016) |
| **Blast radius** | The set of Services reachable from a given Service within a bounded hop count, in both directions: `upstream` (what it depends on, forward traversal) and `downstream` (what would be affected if it degraded or went down, backward traversal) — computed via a recursive CTE over `dependency_graph_edges`. Note: this `upstream`/`downstream` pairing is the opposite of the one used by the 1-hop `/dependencies/{serviceId}` endpoint |
| **Cycle** | A set of Services in a circular dependency chain; indicates an architectural anti-pattern |
| **Drift** | A mismatch between declared and observed dependencies (`undeclared` = observed but not declared; `missing` = declared but not observed) |
| **SPOF** | Single Point of Failure — a Service with no redundancy that sits on the critical path of many other Services |
| **Graph snapshot** | The full dependency graph state at a point in time; published to Kafka after every update |
| **Orphan** | A Service with no team owner (`team_id IS NULL`); `team_id` is mirrored from Registry via the existing `service.{registered,updated}` lifecycle sync (ADR-0028, superseding ADR-0027's plan for a dedicated topic), used for orphan risk and SPOF severity |
| **Risk** | A single finding surfaced on `/v1/risks`: one of SPOF, cycle, drift, or orphan, each carrying `severity`, `type`, `affectedServices[]` (IDs), `title`, `explanation`, and `fix` |

---

## Core Responsibilities

1. **Dependency CRUD** — create, read, soft-delete declared dependencies
2. **Observed edge ingestion** — consume `dependency.observed` events from Ingestion; upsert observed edges
3. **Blast radius computation** — recursive CTE traversal downstream from a given node
4. **Cycle detection** — recursive CTE looking for back-edges
5. **Drift detection** — compare declared vs. observed sets per service; persist drift records
6. **SPOF scoring** — identify highly-connected nodes on critical paths
7. **Graph events** — publish `graph.updated`, `drift.detected`, `cycle.detected` to Kafka for Intelligence and Notification
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
| `dependency_drifts` | Drift records: `drift_type`: undeclared/missing; `detected_at`/`resolved_at` |
| `graph_nodes` | Local projection of Registry services (`GraphNodeEventConsumer` — [1.1]); one row per (tenant_id, service_id), soft-deleted on `service.deleted` |
| `processed_events` | Consumer-side idempotency ledger, keyed `(tenant_id, event_id)` — a replayed envelope is a no-op |

---

## Inbound Ports (API)

Implemented: backfill, dependency CRUD, graph read (`[1.1]`–`[1.3]`), blast radius (`[2.1]`), cycles (`[2.3]`), SPOFs (`[2.4]`), risks (`[2.5]`). Drifts (`GET`/`resolve`) are still planned (Phase 3.2).

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
| GET | `/api/v1/topology/drifts` | Active drift records |
| POST | `/api/v1/topology/drifts/{id}/resolve` | Mark a drift record resolved |
| GET | `/api/v1/topology/spofs` | Fan-in ≥ tenant-configurable threshold (default 5); severity escalates to CRITICAL for CRITICAL-tier or orphan services; threshold + rationale ship in the payload — [2.4] |
| GET | `/api/v1/topology/risks` | Paginated SPOF + cycle + drift + orphan findings, severity-sorted — [2.5] |

---

## Kafka

**Consumed:**

| Topic | Source | Action |
|---|---|---|
| `cartogra.registry.service.registered` | Registry | Create node in graph (no-op if already exists) |
| `cartogra.registry.service.updated` | Registry | Refresh node metadata cache, including `team_id`/`tier` (used for orphan risk and SPOF severity — ADR-0028) |
| `cartogra.registry.service.deleted` | Registry | Soft-delete all edges for that service |
| `cartogra.ingestion.dependency.observed` | Ingestion | Upsert observed edge; trigger drift detection |

**Produced:**

| Topic | Trigger |
|---|---|
| `cartogra.topology.graph.updated` | After any dependency change |
| `cartogra.topology.drift.detected` | When new drift record created |
| `cartogra.topology.cycle.detected` | When cycle found in graph |

---

## Context Relationships

| Neighbour | Relationship | Notes |
|---|---|---|
| Service Catalog (Registry) | Downstream (Conformist) | Consumes registry lifecycle events; references service IDs only. `team_id`/`tier` from those same events also feed orphan risk and SPOF severity (ADR-0028) — no separate topic. Additionally calls Registry's internal `POST /internal/services/access` synchronously (direct service-to-service, bypasses the Gateway) to authorize declared-dependency mutations — "is this user a member of the team owning this service?" — fail closed on error — [1.2] |
| Ingestion | Downstream (Conformist) | Consumes observed dependency edges |
| Intelligence | Upstream (Customer/Supplier) | Produces graph + drift + cycle events |
| Identity & Access (Gateway) | Conformist | Receives proxied requests with `X-Tenant-Id` |
| Shared Kernel | Shared Kernel | `EventEnvelope`, `ApiResponse`, `ErrorCodes` |

---

## ADRs

- ADR-0001 — PostgreSQL + recursive CTEs (no graph database)
- ADR-0016 — OTel span worker feeds `dependency.observed` events into Topology
- ADR-0027 — Topology consumes Registry `ownership-changed` events for orphan risk (chosen over a client-side merge) — **superseded by ADR-0028**
- ADR-0028 — Topology reads `graph_nodes.team_id` directly for orphan risk (no new topic needed; the existing lifecycle sync already carries it)
