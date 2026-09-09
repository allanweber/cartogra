# Topology Context

**Service**: `services/topology` · Port `8082` · Phase 2 · **Planned**

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
| **Blast radius** | The set of Services that would be affected if a given Service degraded or went down; computed via downstream graph traversal |
| **Cycle** | A set of Services in a circular dependency chain; indicates an architectural anti-pattern |
| **Drift** | A mismatch between declared and observed dependencies (`undeclared` = observed but not declared; `missing` = declared but not observed) |
| **SPOF** | Single Point of Failure — a Service with no redundancy that sits on the critical path of many other Services |
| **Graph snapshot** | The full dependency graph state at a point in time; published to Kafka after every update |
| **Orphan** | A Service with no team owner (`team_id IS NULL`); ownership state mirrored from Registry via `ownership-changed` events (ADR-0027), used only to flag orphan risk |
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
8. **Orphan flagging** — mirror `team_id` from Registry's `ownership-changed` events (ADR-0027) to flag orphan status; no other use of ownership data
9. **Risk aggregation** — combine SPOF + cycle + drift + orphan into the paginated `/v1/risks` list

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

## Inbound Ports (API) — Planned

| Method | Path | Description |
|---|---|---|
| POST | `/internal/backfill` | Admin: walk Registry once via `GraphNodeService.backfill()`, seeding `graph_nodes` for tenants that predate `GraphNodeEventConsumer` — [1.1] |
| POST | `/api/v1/topology/dependencies` | Declare a dependency |
| GET | `/api/v1/topology/dependencies` | List dependencies for tenant |
| DELETE | `/api/v1/topology/dependencies/{id}` | Remove a declared dependency |
| GET | `/api/v1/topology/blast-radius/{serviceId}` | Downstream impact set |
| GET | `/api/v1/topology/cycles` | Current cycle list |
| GET | `/api/v1/topology/drifts` | Active drift records |
| POST | `/api/v1/topology/drifts/{id}/resolve` | Mark a drift record resolved |
| GET | `/api/v1/topology/spofs` | Current SPOF list (fan-in threshold + redundancy assumptions documented at implementation) |
| GET | `/api/v1/topology/risks` | Paginated SPOF + cycle + drift + orphan findings |

---

## Kafka

**Consumed:**

| Topic | Source | Action |
|---|---|---|
| `cartogra.registry.service.registered` | Registry | Create node in graph (no-op if already exists) |
| `cartogra.registry.service.updated` | Registry | Refresh node metadata cache |
| `cartogra.registry.service.deleted` | Registry | Soft-delete all edges for that service |
| `cartogra.ingestion.dependency.observed` | Ingestion | Upsert observed edge; trigger drift detection |
| `cartogra.registry.service.ownership-changed` | Registry | Mirror `team_id` (incl. `NULL`) to flag orphan status (ADR-0027) — no other use |

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
| Service Catalog (Registry) | Downstream (Conformist) | Consumes registry lifecycle events; references service IDs only. Also consumes `ownership-changed` for orphan risk flagging only (ADR-0027) — does not otherwise read team/ownership data |
| Ingestion | Downstream (Conformist) | Consumes observed dependency edges |
| Intelligence | Upstream (Customer/Supplier) | Produces graph + drift + cycle events |
| Identity & Access (Gateway) | Conformist | Receives proxied requests with `X-Tenant-Id` |
| Shared Kernel | Shared Kernel | `EventEnvelope`, `ApiResponse`, `ErrorCodes` |

---

## ADRs

- ADR-0001 — PostgreSQL + recursive CTEs (no graph database)
- ADR-0016 — OTel span worker feeds `dependency.observed` events into Topology
- ADR-0027 — Topology consumes Registry `ownership-changed` events for orphan risk (chosen over a client-side merge)
