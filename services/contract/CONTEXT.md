# Contract Context

**Service**: `services/contract` · Port `8083` · Context path `/api/v1/contracts` · Phase 5 · **Live: contract store (5.1)**; checks, consumers, discovery, alerts planned

---

## Purpose

The Contract context is Cartogra's API compatibility guardian. It stores the OpenAPI specification of every Service, checks a candidate spec against the stored baseline before merge, names the Services that consume the changed contract, and notifies teams of breaking changes before they reach production.

---

## Ubiquitous Language

| Term | Meaning in this context |
|---|---|
| **API Contract** | The formal interface specification for a Service; owned by the producing Service; one per Service |
| **Contract Version** | A specific immutable revision of an API Contract, numbered 1, 2, 3 by the server; stored raw and canonicalized with a `spec_hash`. Its **label** is the spec's `info.version`, display only |
| **Canonical Spec** | The spec as compact JSON with object keys sorted recursively and arrays kept in order; `$ref` not resolved. YAML vs JSON, comments and key order never change it |
| **Deleted Service** | A tombstone of a service Registry deleted; its contract is soft-deleted and no contract can be created or versioned for it |
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

**Tables**:

| Table | Status | Purpose |
|---|---|---|
| `api_contracts` | Live (5.1) | One live contract per service (partial unique index); spec_type `openapi` |
| `contract_versions` | Live (5.1) | Immutable revisions; a spec hashing the same as the latest stores nothing |
| `deleted_services` | Live (5.1) | Tombstones from `service.deleted` |
| `contract_checks` | Planned (5.5) | Check result per candidate vs. baseline; `is_breaking` + `changes` JSONB |
| inbound-edge projection | Planned (5.4) | Consumer edges projected from Topology `dependency.*` events, idempotent on event id |
| `notification_rules` | Planned (5.7) | Per-tenant Slack delivery rules |
| `notification_log` | Planned (5.7) | Every delivery attempt, with retry/backoff outcome |

---

## Inbound Ports (API)

Contract: `docs/api/contract.openapi.yaml`. Writes need ADMIN or MEMBER (`@PreAuthorize(Roles.CAN_WRITE)`).

| Method | Path | Status | Description |
|---|---|---|---|
| POST | `/api/v1/contracts` | Live (5.1) | `{serviceId, spec}` → 201 contract + version 1. Live contract for the service → 409 `CONFLICT` with `details.contractId`; deleted service → 404 |
| GET | `/api/v1/contracts/{id}` | Live (5.1) | Contract + latest version |
| POST | `/api/v1/contracts/{id}/versions` | Live (5.1) | `{spec}` → 201 next version; same hash as latest → 200 latest, nothing stored; an older version's spec (revert) → new version |
| GET | `/api/v1/contracts` | Planned (5.2) | List contracts for tenant (paginated) |
| GET | `/api/v1/contracts/{id}/versions/{v}/diff` | Planned (5.2) | Structured diff against the previous version |
| POST | `/api/v1/contracts/check` | Planned (5.5) | CI check: `{service, spec}` → `{is_breaking, changes[], affected_consumers[]}`; API key scope `ci:check`; never creates a version |

Spec rules: OpenAPI 3.0/3.1, YAML or JSON, ≤ 1 MiB (blank or larger → 400 `VALIDATION_ERROR`). Not YAML/JSON, Swagger 2.0, AsyncAPI, or any swagger-parser validation message → 422 `INVALID_SPEC` with up to 50 `details.messages`. `$ref`s are never resolved, so parsing never fetches a URL.

---

## Kafka

**Consumed:**

| Topic | Source | Action |
|---|---|---|
| `cartogra.ingestion.spec.discovered` | Ingestion | Store a new version, idempotent on `(tenant_id, repository_full_name, file_path, content_sha256)` |
| `cartogra.topology.dependency.changed` (`event_type` `dependency.{declared,observed,removed}`) | Topology | Maintain the inbound-edge projection that names Consumers |
| `cartogra.registry.service.changed` (on `event_type` `service.deleted`) | Registry | **Live (5.1)**, `ServiceDeletedConsumer`, group `contract-consumer`: tombstone the service, soft-delete its contract and versions, audit `deleted` (system) per contract changed. Replay changes and audits nothing; other event types are acked and ignored. Poison records → `.dlq` |

**Produced:**

| Topic | Trigger |
|---|---|
| `cartogra.platform.audit.recorded` | Every contract write (live: `created`, `version_published`, `deleted`; payload `serviceId`, `versionNumber`, `label`, `specHash` — never spec content), notification-rule change (ADR-0017) |

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
