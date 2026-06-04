# Contract Context

**Service**: `services/contract` · Port `8083` · Phase 3 · **Planned**

---

## Purpose

The Contract context is Cartogra's API compatibility guardian. It stores OpenAPI and AsyncAPI specifications for every Service, runs backward-compatibility checks when a new spec version is published, tracks which Services consume which contracts, and notifies teams of breaking changes before they reach production.

---

## Ubiquitous Language

| Term | Meaning in this context |
|---|---|
| **API Contract** | The formal interface specification for a Service; owned by the producing Service |
| **Contract Version** | A specific immutable revision of an API Contract (semver or commit SHA); stored as raw YAML/JSON with a content hash |
| **Spec Type** | Format of the contract: `openapi` (REST) or `asyncapi` (event-driven) |
| **Consumer** | A Service that depends on another Service's API Contract |
| **Producer** | The Service that owns and publishes the API Contract |
| **Contract Check** | A compatibility analysis comparing a candidate version against a baseline; result: `compatible`, `breaking`, or `warning` |
| **Breaking Change** | A contract mutation that would break a registered Consumer (removed endpoint, changed field type, etc.) |
| **Finding** | A specific breaking change or warning detected during a Contract Check |
| **Outbox Event** | A durably persisted event awaiting Kafka publication (transactional outbox pattern) |
| **Consumer Matrix** | The cross-reference view of which Services consume which contracts; drives blast-radius-aware change alerting |

---

## Core Responsibilities

1. **Contract + version storage** — ingest spec content from Ingestion events; deduplicate by hash
2. **Compatibility checks** — compare candidate vs. baseline version; classify changes
3. **Consumer tracking** — register consumer relationships; clean up on service deletion
4. **Outbox relay** — publish `check.passed`, `check.failed`, `version.published` events via transactional outbox
5. **Breaking-change alerting** — `check.failed` events flow to Intelligence and Notification

---

## Domain Model

```
Service (ID ref) ──< ApiContract ──< ContractVersion
ApiContract ──< ContractCheck
ApiContract ──< ContractConsumer ──> Service (ID ref, consumer)
ApiContract ──< OutboxEvent
```

All cross-context Service references stored as UUIDs only.

**Tables** (Phase 3 schema):

| Table | Purpose |
|---|---|
| `api_contracts` | One per service; spec_type: openapi/asyncapi |
| `contract_versions` | Immutable revisions; deduped by `spec_hash` |
| `contract_consumers` | Producer–consumer relationships |
| `contract_checks` | Check result per (baseline, candidate) pair; `findings` JSONB |
| `outbox_events` | Transactional outbox; `published_at NULL` = pending |

---

## Inbound Ports (API) — Planned

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/contracts` | Create contract for a service |
| GET | `/api/v1/contracts` | List contracts for tenant |
| GET | `/api/v1/contracts/{id}` | Get contract with versions |
| POST | `/api/v1/contracts/{id}/versions` | Publish new version; triggers check |
| GET | `/api/v1/contracts/{id}/checks` | List check results |
| POST | `/api/v1/contracts/{id}/consumers` | Register consumer service |
| DELETE | `/api/v1/contracts/{id}/consumers/{consumerId}` | Remove consumer |

---

## Kafka

**Consumed:**

| Topic | Source | Action |
|---|---|---|
| `cartogra.ingestion.spec.updated` | Ingestion | Ingest new spec version; trigger compatibility check |
| `cartogra.registry.service.deleted` | Registry | Soft-delete contracts + consumer registrations for that service |

**Produced (via Outbox):**

| Topic | Trigger |
|---|---|
| `cartogra.contract.check.passed` | Compatibility check returned compatible |
| `cartogra.contract.check.failed` | Breaking change detected |
| `cartogra.contract.version.published` | New spec version accepted and stored |

---

## Context Relationships

| Neighbour | Relationship | Notes |
|---|---|---|
| Ingestion | Downstream (Conformist) | Consumes `spec.updated` events to receive spec content |
| Service Catalog (Registry) | Downstream (Conformist) | Consumes `service.deleted` to cascade cleanup |
| Intelligence | Upstream (Customer/Supplier) | Produces `check.failed` and `version.published` events |
| Identity & Access (Gateway) | Conformist | Receives proxied requests with `X-Tenant-Id` |
| Shared Kernel | Shared Kernel | `EventEnvelope`, `ApiResponse`, `ErrorCodes` |

---

## ADRs

- ADR-0018 — Spec discovery transport: Ingestion publishes spec envelopes to Kafka; Contract consumes them
