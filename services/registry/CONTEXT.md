# Service Catalog Context — Registry

**Service**: `services/registry` · Port `8081` · Phase 0/1 · **Live**

---

## Purpose

The Registry is Cartogra's source of truth for the service estate. It owns every **Service**, **Team**, and **SCM Connection** record, tracks full mutation history, publishes lifecycle events to the rest of the platform, and consumes discovery events from the Ingestion context to auto-populate the catalog.

---

## Ubiquitous Language

| Term | Meaning in this context |
|---|---|
| **Service** | A deployable unit tracked in the catalog (microservice, job, library, or infra component) |
| **Team** | An ownership group; owns one or more Services |
| **Orphan** | A Service with no Team assigned |
| **SCM Connection** | A configured link to a source-code provider (GitHub or Azure DevOps) belonging to a Tenant |
| **History / Snapshot** | An append-only record of every state a Service has been in; supports point-in-time queries |
| **Health Status** | Current runtime health of a Service: `HEALTHY`, `DEGRADED`, `UNHEALTHY`, `UNKNOWN` |
| **Tier** | Criticality classification of a Service: `CRITICAL`, `STANDARD`, or `EXPERIMENTAL`. Governs incident response priority and change-management strictness |
| **Tags** | Operational or business labels on a Service (e.g. `pci-scope`, `payment`). Free-form; distinct from `techStack` which identifies technologies used |
| **SLA Target** | Numeric uptime percentage a Service commits to (e.g. `99.9`). Human-declared; not yet computed by the platform |
| **Discovery** | Automatic creation or update of a Service record from an external signal (SCM scan, K8s watch) |
| **External ID** | The provider-scoped stable key used for upsert deduplication (`tenant_id + external_id` UNIQUE) |
| **Source** | How a Service was discovered: `scm`, `k8s`, or `manual` |
| **Sync Command** | A Kafka message requesting Ingestion to start a sync job for a given SCM Connection |
| **Ownership Resolution** | Assigning a Team to a Service based on CODEOWNERS or K8s namespace labels |
| **Team Member** | A tenant User attached to a Team via `team_members` (any tenant role — VIEWER/MEMBER/ADMIN — is eligible). All members of a Team are equal; there is no distinguished lead |
| **Team management** | Mutating a Team's own state: rename, delete, add/remove members. Gated to ADMIN or an existing member of that Team; creating a new Team is ADMIN-only (a new Team has no members yet). Distinct from editing the Services a Team owns |

---

## Core Responsibilities

1. **Service CRUD** — create, read, update, soft-delete; name UNIQUE per tenant (case-insensitive)
2. **Team management** — CRUD for Teams; track `slack_channel`
3. **SCM Connection management** — CRUD for provider credentials; trigger sync on creation
4. **History** — snapshot saved on every mutating operation; point-in-time retrieval
5. **Orphan detection** — list Services with `team_id IS NULL`
6. **Discovery upsert** — idempotent create-or-update keyed on `(tenant_id, external_id)`
7. **Ownership assignment** — auto-assign Team when exactly one match (ADR-0015); manual override always wins
8. **Lifecycle events** — publish `service.registered`, `service.updated`, `service.deleted` to Kafka

---

## Domain Model

```
Tenant ──< Team ──< Service
Tenant ──< ScmConnection
Team ──< TeamMember ──> User   (child of Team aggregate; user_id references Gateway's User by ID only)
Service ──< ServiceSnapshot   (append-only history)
Service ──> Team               (team_id FK, nullable — orphan when NULL)
```

**Aggregates** (Spring Data JDBC — one Repository per aggregate root):

| Aggregate root | Entity | Table |
|---|---|---|
| `Service` | `Service` record (23 fields) | `services` |
| `Team` | `Team` record; `TeamMember` is a child entity saved through the `Team` root | `teams`, `team_members` |
| `ScmConnection` | `ScmConnection` record | `scm_connections` |

**Authorization model**: service edit (`PUT /services/{id}`) and team management (rename/delete/add-member/remove-member) are both checked live against `team_members` on each request — ADMIN always passes; otherwise the requester must be a member of the Team in question. Orphan services (`team_id IS NULL`) have no owning Team, so only ADMIN can edit them. No role is baked into the JWT for this (see ADR-0025, supersedes ADR-0022).

**Value objects / supporting types**:
- `ServiceHealthStatus` enum: `HEALTHY`, `DEGRADED`, `UNHEALTHY`, `UNKNOWN`
- `ServiceSnapshot` record: full service row + changed_by + changed_at

**Discovery fields on `Service`** (added V011): `external_id`, `connection_id`, `source`, `repository_ref`, `k8s_cluster`, `k8s_namespace`, `k8s_deployment`, `health_endpoint`, `last_commit_at`, `last_commit_sha`

---

## Use Cases

| Use Case Interface | Trigger | Side Effects |
|---|---|---|
| `CreateServiceUseCase` | POST `/services` | Save + history snapshot + publish `service.registered` |
| `UpdateServiceUseCase` | PUT `/services/{id}` | Save + history snapshot + publish `service.updated` |
| `DeleteServiceUseCase` | DELETE `/services/{id}` | Soft delete + history snapshot + publish `service.deleted` |
| `AssignOwnerUseCase` | PATCH `/services/{id}/owner` | Update `team_id` + history snapshot |
| `UpsertDiscoveredServiceUseCase` | Kafka `service.discovered` | Upsert on `(tenantId, externalId)` + history snapshot |
| `ListServicesUseCase` | GET `/services` | Filter by teamId, health, techStack, fulltext search |
| `FindServiceUseCase` | GET `/services/{id}` | Single lookup |
| `DetectOrphansUseCase` | GET `/services/orphaned` | Filter `team_id IS NULL` |
| `GetServiceHistoryUseCase` | GET `/services/{id}/history` | Paginated snapshots; optional `?at=<instant>` |
| `CreateTeamUseCase` | POST `/teams` | Save team |
| `CreateScmConnectionUseCase` | POST `/scm-connections` | Save + publish sync command to Ingestion |

---

## Inbound Ports (API)

All require header `X-Tenant-Id` (injected by Gateway). All responses use `ApiResponse<T>` envelope.

| Method | Path | Use Case |
|---|---|---|
| POST | `/api/v1/services` | CreateService |
| GET | `/api/v1/services` | ListServices |
| GET | `/api/v1/services/orphaned` | DetectOrphans |
| GET | `/api/v1/services/{id}` | FindService |
| PUT | `/api/v1/services/{id}` | UpdateService |
| DELETE | `/api/v1/services/{id}` | DeleteService |
| PATCH | `/api/v1/services/{id}/owner` | AssignOwner |
| GET | `/api/v1/services/{id}/history` | GetServiceHistory |
| POST/GET/GET/{id}/PUT/{id}/DELETE/{id} | `/api/v1/teams` | Team CRUD |
| POST/GET/GET/{id}/PUT/{id}/DELETE/{id} | `/api/v1/scm-connections` | SCM Connection CRUD |

---

## Outbound Ports (Dependencies)

| Dependency | Type | Purpose |
|---|---|---|
| PostgreSQL | Spring Data JDBC + `NamedParameterJdbcTemplate` | All service, team, SCM connection, history reads/writes |
| Kafka | `KafkaTemplate` | Publish lifecycle events + sync commands |

---

## Kafka

**Produced:**

| Topic | Trigger |
|---|---|
| `cartogra.registry.service.registered` | Service created (manual or discovered) |
| `cartogra.registry.service.updated` | Service mutated |
| `cartogra.registry.service.deleted` | Service soft-deleted |
| `cartogra.registry.sync.command` | SCM Connection created (triggers Ingestion sync) |

**Consumed:**

| Topic | Consumer class | Use Case invoked |
|---|---|---|
| `cartogra.ingestion.service.discovered` | `RegistryServiceDiscoveryConsumer` | `UpsertDiscoveredServiceUseCase` |
| `cartogra.ingestion.ownership.resolved` | `OwnershipResolvedConsumer` | `AssignOwnerUseCase` (single-match rule) |

---

## Database (Flyway V001–V011)

| Version | Description |
|---|---|
| V001 | `tenants` table |
| V002 | `teams` table |
| V003 | `users` table (auth data, owned by Gateway semantically) |
| V004 | `scm_connections` table |
| V005 | `services` table |
| V006 | `services_history` table |
| V007 | `scm_webhooks` table |
| V008 | `refresh_tokens` table |
| V009 | `tenant_oidc_configs` table |
| V010 | Add password reset fields to `users` |
| V011 | Add discovery fields to `services`; add `(tenant_id, external_id)` UNIQUE index |

Flyway history table: `flyway_schema_history_registry`

---

## Context Relationships

| Neighbour | Relationship | Notes |
|---|---|---|
| Identity & Access (Gateway) | Conformist ← Open Host Service | Receives proxied requests with `X-Tenant-Id` already validated |
| Ingestion | Downstream ← Upstream (Published Language) | Consumes `service.discovered` and `ownership.resolved` events |
| Topology | Upstream → Downstream (Customer/Supplier) | Produces registry lifecycle events; Topology builds graph from them |
| Contract | Upstream → Downstream | Produces `service.deleted` (consumer matrix cleanup) |
| Intelligence | Upstream → Downstream | Produces registry events; Intelligence builds health baselines |
| Shared Kernel | Shared Kernel | `EventEnvelope`, `ApiResponse`, `SyncCommandPayload`, `ErrorCodes` |

---

## Key Files

| Path | Role |
|---|---|
| `src/main/java/.../domain/` | Service, Team, ScmConnection records; domain exceptions |
| `src/main/java/.../application/usecase/` | Use case interfaces |
| `src/main/java/.../application/usecase/impl/` | Use case implementations |
| `src/main/java/.../infrastructure/jdbc/` | JDBC repository implementations |
| `src/main/java/.../infrastructure/kafka/` | ServiceLifecycleEventProducer, SyncCommandProducer, consumers |
| `src/main/resources/db/migration/` | V001–V011 Flyway scripts |
| `src/main/resources/application.yml` | DB, Kafka, OTel config |

---

## ADRs

- ADR-0009 — Spring Data JDBC (no JPA/Hibernate)
- ADR-0015 — CODEOWNERS → auto-assign team_id when exactly one team matches; ambiguous/no-match → log WARN, skip
- ADR-0017 — Audit events ownership port (`AuditEventPort` in `shared:common`, Phase 2)
- ADR-0020 — Event choreography over sagas for cross-context workflows
- ADR-0021 — Service discovery upsert keyed on `(tenantId, externalId)`
