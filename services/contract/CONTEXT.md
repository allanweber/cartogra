# Contract Context

**Service**: `services/contract` · Port `8083` · Context path `/api/v1/contracts` · Phase 5 · **Live: contract store (5.1), diff + timeline (5.2), breaking-change engine (5.3), consumers (5.4), CI check (5.5), spec discovery (5.8)**; alerts planned

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
| **Version Diff** | A version against the previous one: `changes` (structural diff of the canonical trees, RFC 6901 pointers), `lines` (side-by-side rows over the canonical spec rendered as YAML) and `compatibility` (the engine verdict, computed on each read). Version 1 is diffed against an empty spec |
| **Compatibility Rule** | One rule of the breaking-change engine (`BreakingChangeEngine`), with a fixed verdict: for example `path-removed` (breaking) or `response-field-added` (compatible). The engine compares operation by operation; parameters and request bodies have the request direction, responses the response direction. Local `$ref`s are resolved at each use site, so a shared schema gives one change per operation and direction. `allOf` is merged; `oneOf`/`anyOf` are not walked |
| **Unchecked** | A Contract Version stored before 5.3: `breaking` is null, it has no badge, and neither `status` filter matches it. Its diff still gets a live `compatibility` |
| **Discovered Spec** | One content of an OpenAPI file that Ingestion found in a repository (`spec.discovered`), stored when it differs from the last spec seen at that repository and path. Status `PENDING` (no service yet), `APPLIED` (stored as a version), `UNCHANGED` (same canonical hash as the latest), `REJECTED` (invalid, with parser errors), `SKIPPED` (tombstoned service) or `SUPERSEDED` (a newer spec came first) |
| **Version Source** | `manual` (`POST /versions`) or `discovery` (applied from a discovered spec, audited as the system actor) |
| **Spec Type** | Format of the contract: `openapi` (OpenAPI 3.x) only. AsyncAPI is a candidate once a user brings an AsyncAPI spec |
| **Baseline** | The latest stored Contract Version of a Service; what a candidate spec is checked against |
| **Consumer** | A Service with a live inbound declared or observed `http` or `grpc` edge to the contract's Service; derived from the Topology graph, never registered by hand. Deleted services and self edges are not consumers |
| **Producer** | The Service that owns and publishes the API Contract |
| **Contract Check** | A compatibility analysis of a **Candidate Spec** against the Baseline, sent by CI with an API Key; result: `breaking` or `compatible`. It records one `contract_checks` row and never creates a Contract Version |
| **Candidate Spec** | The spec a Contract Check compares with the Baseline: a proposed change, not yet a Contract Version. Only its hash is stored |
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
| `contract_versions` | Live (5.1) | Immutable revisions; a spec hashing the same as the latest stores nothing. `is_breaking` (5.3, V004): the engine verdict against the previous latest version, set at publish in the same transaction; v1 is `false`; NULL = Unchecked (no backfill). The classified changes are not stored. `source` `manual`/`discovery` and `discovered_spec_id` (5.8, V005) |
| `discovered_specs` | Live (5.8) | Discovered specs; `is_latest` marks the last spec seen for each `(tenant_id, repository_full_name, file_path)`; keeps the raw spec so a PENDING row can be applied later; `status`, `service_id`, `contract_version_id`, `errors` JSONB |
| `services` | Live (5.2) | Projection of Registry services: name for the contract list (from `service.registered`/`updated`; a service with no name yet is looked up once via Registry `POST /internal/services/names` and stored), `deleted_at` tombstone from `service.deleted` (replaced 5.1's `deleted_services`). `external_id` (5.8, SCM repository full path, unique per live service) from `service.registered`/`updated` or a Registry `GET /internal/services/by-external-id` lookup |
| `contract_checks` | Live (5.5, V007) | One row per completed CI check: service, contract, `baseline_version_id`, `candidate_hash`, `is_breaking`, `changes` JSONB, `affected_consumer_ids`, `api_key_id`. A failed check writes no row. V007 also indexes `services (tenant_id, lower(name))` for the check by name |
| `inbound_edges` | Live (5.4) | Topology edges projected from `dependency.changed`, PK `(tenant_id, edge_id)`; all protocols stored. `removed_at` is final, so a replayed or late event cannot bring a removed edge back |
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
| GET | `/api/v1/contracts?q&status&limit&offset` | Live (5.2; `status` 5.3) | `PageResult` of live contracts with `serviceName` (null until projected) and latest version, most recently changed first; `q` matches the service name; `status=breaking\|compatible` filters on the latest version's `breaking` (another value → 400 `VALIDATION_ERROR`) |
| GET | `/api/v1/contracts/{id}/versions?limit&offset` | Live (5.2) | Versions newest first (no spec text). Each version has `source`, and `repositoryFullName` + `filePath` for a discovered one (5.8) |
| GET | `/api/v1/contracts/{id}/versions/{v}/diff` | Live (5.2; `compatibility` 5.3) | `{from, to, changes[], lines[], compatibility}` against version v-1; v1 → `from: null`, all lines added. `compatibility: {isBreaking, changes[{rule, breaking, direction, method, path, location, message}]}` |
| GET | `/api/v1/contracts/{id}/consumers?limit&offset` | Live (5.4) | `PageResult` of Consumers `{serviceId, serviceName, dependencyTypes[], protocols[]}`, one row per service, sorted by name; unknown names fetched from Registry in one call; 404 when the contract is not live |
| POST | `/api/v1/contracts/check` | Live (5.5) | CI check: `{service, spec}` → `{isBreaking, baselineVersion, changes[], affectedConsumers[]}`; `service` is an id or a name (not case-sensitive); role `CI_CHECK` only (an API key with scope `ci:check`; a JWT gets 403). All consumers when breaking (at most 500), else none. Unknown/deleted/other-tenant service → 404 `SERVICE_NOT_FOUND`; 2+ live name matches → 409 `SERVICE_AMBIGUOUS`; no contract or version → 404 `BASELINE_NOT_FOUND`; bad spec → 422. Never creates a version, audit or Kafka event |

Spec rules: OpenAPI 3.0/3.1, YAML or JSON, ≤ 1 MiB (blank or larger → 400 `VALIDATION_ERROR`). Not YAML/JSON, Swagger 2.0, AsyncAPI, or any swagger-parser validation message → 422 `INVALID_SPEC` with up to 50 `details.messages`. `$ref`s are never resolved, so parsing never fetches a URL.

---

## Kafka

**Consumed:**

| Topic | Source | Action |
|---|---|---|
| `cartogra.ingestion.spec.discovered` | Ingestion | **Live (5.8)**, `SpecDiscoveredConsumer`. Record the spec as the last spec seen for its repository and path: equal to the last spec seen and its service still live (or the row `REJECTED`) → acked, no change; equal, but the row `SKIPPED` or its service tombstoned → a `PENDING` copy becomes the latest row and the repository is resolved again; different (also a revert) → new row, an older `PENDING` row → `SUPERSEDED`. Resolve the repository to a service (live projection `external_id`, else Registry lookup); no match → `PENDING`. Else apply: tombstoned → `SKIPPED`; invalid → `REJECTED` (acked, no DLQ); no contract → contract + v1 (audit `created`); same canonical hash → `UNCHANGED`; else next version (audit `version_published`). A race with a manual create publishes a version instead. Unreadable records → `.dlq` |
| `cartogra.topology.dependency.changed` (`event_type` `dependency.{declared,observed,removed}`) | Topology | **Live**, `DependencyEventConsumer`, group `contract-consumer`. `declared`/`observed` upsert the edge by edge id, `removed` marks it removed (final). Other event types are acked and ignored. Poison records → `.dlq` |
| `cartogra.registry.service.changed` | Registry | **Live**, `ServiceEventConsumer`, group `contract-consumer`. `service.registered`/`updated` (5.2): upsert the projected name, never on a tombstone; store `externalId` and apply the PENDING discovered specs of that repository (5.8). `service.deleted` (5.1): tombstone the service, soft-delete its contract and versions, audit `deleted` (system) per contract changed. Replay changes and audits nothing; other event types are acked and ignored. Poison records → `.dlq` |

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
| Service Catalog (Registry) | Downstream (Conformist) | Consumes `service.changed` for names and to cascade `service.deleted`; calls `POST /internal/services/names` directly (bypasses the Gateway) only for services with no stored name, failing open to an empty name; calls `GET /internal/services/by-external-id` for a discovered spec whose repository is not in the projection, failing open to PENDING; receives audit events |
| Identity & Access (Gateway) | Conformist | Receives proxied requests with `X-Tenant-Id`; gateway validates `X-Cartogra-Api-Key` for the CI check |
| Shared Kernel | Shared Kernel | `EventEnvelope`, `ApiResponse`, `ErrorCodes` |

---

## ADRs

- ADR-0018 — Spec discovery transport: Ingestion publishes spec envelopes to Kafka; Contract consumes them
