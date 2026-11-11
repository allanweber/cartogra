# Contract Context

**Service**: `services/contract` · Port `8083` · Phase 5 · **Planned**

---

## Purpose

The Contract context is Cartogra's API compatibility guardian. It stores the OpenAPI specification of every Service, checks a candidate spec against the stored baseline before merge, names the Services that consume the changed contract, and notifies teams of breaking changes before they reach production.

---

## Ubiquitous Language

| Term | Meaning in this context |
|---|---|
| **API Contract** | The formal interface specification for a Service; owned by the producing Service; one per Service |
| **Contract Version** | A specific immutable revision of an API Contract; stored canonicalized with a `spec_hash` |
| **Spec Type** | Format of the contract: `openapi` (OpenAPI 3.x) only. AsyncAPI is a candidate once a user brings an AsyncAPI spec |
| **Baseline** | The latest stored Contract Version of a Service; what a candidate spec is checked against |
| **Consumer** | A Service with an inbound declared or observed dependency edge to the contract's Service; derived from the Topology graph, never registered by hand |
| **Producer** | The Service that owns and publishes the API Contract |
| **Contract Check** | A compatibility analysis comparing a candidate spec against the Baseline; result: `breaking` or `compatible` |
| **Breaking Change** | A direction-aware contract mutation that would break a Consumer (removed path or operation, removed response field, type change, new required request field or parameter, enum value removed from a request or added to a response) |
| **Change** | One classified difference found during a Contract Check |
| **Affected Consumers** | The Consumers named by a breaking Contract Check |

---

## Core Responsibilities

1. **Contract + version storage** — ingest spec content from manual `POST /versions` and Ingestion `spec.discovered` events; deduplicate by hash
2. **Compatibility checks** — compare candidate vs. Baseline; classify each change as breaking or compatible
3. **Consumer projection** — project Topology `dependency.*` events into a local inbound-edge table to name Affected Consumers
4. **Service cleanup** — soft-delete a Service's contract and versions on `service.deleted`
5. **Breaking-change alerting** — send Slack notifications in-process when a check is breaking, per tenant notification rules

---

## Domain Model

```
Service (ID ref) ──1 ApiContract ──< ContractVersion
ApiContract ──< ContractCheck
Service (ID ref) ──< InboundEdge ──> Service (ID ref, consumer)   (projection of Topology edges)
Tenant ──< NotificationRule ──< NotificationLog
```

All cross-context Service references stored as UUIDs only.

**Tables** (Phase 5 schema):

| Table | Purpose |
|---|---|
| `api_contracts` | One per service; spec_type `openapi` |
| `contract_versions` | Immutable revisions; deduped by `spec_hash` |
| `contract_checks` | Check result per candidate vs. baseline; `is_breaking` + `changes` JSONB |
| inbound-edge projection | Consumer edges projected from Topology `dependency.*` events, idempotent on event id |
| `notification_rules` | Per-tenant Slack delivery rules |
| `notification_log` | Every delivery attempt, with retry/backoff outcome |

---

## Inbound Ports (API) — Planned

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/contracts` | Create contract for a service |
| GET | `/api/v1/contracts` | List contracts for tenant (paginated) |
| GET | `/api/v1/contracts/{id}` | Get contract with versions and consumers |
| POST | `/api/v1/contracts/{id}/versions` | Publish a new version |
| GET | `/api/v1/contracts/{id}/versions/{v}/diff` | Structured diff against the previous version |
| POST | `/api/v1/contracts/check` | CI check: `{service, spec}` → `{is_breaking, changes[], affected_consumers[]}`; API key scope `ci:check`; never creates a version |

---

## Kafka

**Consumed:**

| Topic | Source | Action |
|---|---|---|
| `cartogra.ingestion.spec.discovered` | Ingestion | Store a new version, idempotent on `(tenant_id, repository_full_name, file_path, content_sha256)` |
| `cartogra.topology.dependency.changed` (`event_type` `dependency.{declared,observed,removed}`) | Topology | Maintain the inbound-edge projection that names Consumers |
| `cartogra.registry.service.changed` (on `event_type` `service.deleted`) | Registry | Soft-delete the service's contract and versions |

**Produced:**

| Topic | Trigger |
|---|---|
| `cartogra.platform.audit.recorded` | Every contract write, notification-rule change (ADR-0017) |

No `check.*` or `version.published` topics: no consumer exists for them (roadmap rule 4).

---

## Context Relationships

| Neighbour | Relationship | Notes |
|---|---|---|
| Ingestion | Downstream (Conformist) | Consumes `spec.discovered` to receive spec content |
| Topology | Downstream (Conformist) | Consumes `dependency.*` to derive Consumers |
| Service Catalog (Registry) | Downstream (Conformist) | Consumes `service.changed` (`service.deleted`) to cascade cleanup; receives audit events |
| Identity & Access (Gateway) | Conformist | Receives proxied requests with `X-Tenant-Id`; gateway validates `X-Cartogra-Api-Key` for the CI check |
| Shared Kernel | Shared Kernel | `EventEnvelope`, `ApiResponse`, `ErrorCodes` |

---

## ADRs

- ADR-0018 — Spec discovery transport: Ingestion publishes spec envelopes to Kafka; Contract consumes them
