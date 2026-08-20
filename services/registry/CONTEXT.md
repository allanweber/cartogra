# Service Catalog Context — Registry

**Service**: `services/registry` · Port `8081` · Phase 0/1 · **Live**

---

## Purpose

The Registry is Cartogra's source of truth for the service estate. It owns every **Service** and **Team** record, tracks full mutation history, publishes lifecycle events to the rest of the platform, and consumes discovery events from the Ingestion context to auto-populate the catalog.

> **SCM Connection moved.** Earlier revisions of this doc listed `ScmConnection` as a Registry aggregate. It is now owned entirely by `services/ingestion` (`scm_connections` table, `ScmConnectionService`) — Registry has no SCM Connection code, table, or endpoint.

---

## Ubiquitous Language

| Term | Meaning in this context |
|---|---|
| **Service** | A deployable unit tracked in the catalog (microservice, job, library, or infra component) |
| **Team** | An ownership group; owns one or more Services |
| **Orphan** | A Service with no Team assigned |
| **History / Snapshot** | An append-only record of every state a Service has been in; supports point-in-time queries |
| **Health Status** | Current runtime health of a Service: `HEALTHY`, `DEGRADED`, `UNHEALTHY`, `UNKNOWN` |
| **Tier** | Criticality classification of a Service: `CRITICAL`, `STANDARD`, or `EXPERIMENTAL`. Governs incident response priority and change-management strictness |
| **Tags** | Operational or business labels on a Service (e.g. `pci-scope`, `payment`). Free-form; distinct from `techStack` which identifies technologies used |
| **SLA Target** | Numeric uptime percentage a Service commits to (e.g. `99.9`). Human-declared; not yet computed by the platform |
| **Discovery** | Automatic creation or update of a Service record from an external signal (SCM scan, K8s watch) |
| **External ID** | The provider-scoped stable key used for upsert deduplication (`tenant_id + external_id` UNIQUE) |
| **Source** | How a Service was discovered: `scm`, `k8s`, or `manual` |
| **Ownership Resolution** | Assigning a Team to a Service based on CODEOWNERS or K8s namespace labels |
| **Team Member** | A tenant User attached to a Team via `team_members` (any tenant role — VIEWER/MEMBER/ADMIN — is eligible). All members of a Team are equal; there is no distinguished lead |
| **Team management** | Mutating a Team's own state: rename, delete, add/remove members. Gated to ADMIN or an existing member of that Team; creating a new Team is ADMIN-only (a new Team has no members yet). Distinct from editing the Services a Team owns |

---

## Core Responsibilities

1. **Service CRUD** — create, read, update, soft-delete; name UNIQUE per tenant (case-insensitive)
2. **Team management** — CRUD for Teams, including membership (`team_members`)
3. **History** — snapshot saved on every mutating operation; point-in-time retrieval
4. **Orphan detection** — list Services with `team_id IS NULL`
5. **Discovery upsert** — idempotent create-or-update keyed on `(tenant_id, external_id)`
6. **Ownership assignment** — auto-assign Team when exactly one match (ADR-0015); manual override always wins
7. **Lifecycle events** — publish `service.registered`/`updated`/`deleted` and `team.created`/`updated`/`deleted` to Kafka
8. **Plan limits** — expose per-tenant service/team plan limits to other services (`/internal/plan-limits`)

---

## Domain Model

```
Tenant ──< Team ──< Service
Team ──< TeamMember ──> User   (child of Team aggregate; user_id references Gateway's User by ID only)
Service ──< ServiceSnapshot   (append-only history)
Service ──> Team               (team_id FK, nullable — orphan when NULL)
```

**Aggregates** (Spring Data JDBC — one Repository per aggregate root):

| Aggregate root | Entity | Table |
|---|---|---|
| `Service` | `Service` record | `services` |
| `Team` | `Team` record; `TeamMember` is a child entity saved through the `Team` root | `teams`, `team_members` |

**Authorization model**: service edit (`PUT /services/{id}`) and team management (rename/delete/add-member/remove-member) are both checked live against `team_members` on each request — ADMIN always passes; otherwise the requester must be a member of the Team in question. Orphan services (`team_id IS NULL`) have no owning Team, so only ADMIN can edit them. No role is baked into the JWT for this (see ADR-0025, supersedes ADR-0022).

**Value objects / supporting types**:
- `ServiceHealthStatus` enum: `HEALTHY`, `DEGRADED`, `UNHEALTHY`, `UNKNOWN`
- `ServiceSnapshot` record: full service row + changed_by + changed_at

**Discovery fields on `Service`** (part of `V005__create_services.sql`): `external_id`, `connection_id`, `source`, `repository_ref`, `k8s_cluster`, `k8s_namespace`, `k8s_deployment`, `health_endpoint`, `last_commit_at`, `last_commit_sha`

---

## Domain Services

No `*UseCase`/`*UseCaseImpl` layer — one `@Service` class per domain concept in `domain/`, called directly by the controllers (see `.claude/rules/patterns.md` — Layering).

| Service class | Key methods | Notes |
|---|---|---|
| `ServiceService` | `create`, `update`, `delete`, `get`, `list`, `detectOrphans`, `assignOwner`, `history`, `historyAt`, `listTechStacks`, `countByConnectionId`, `upsertDiscovered`, `resolveOwnership` | Every mutating method saves a history snapshot; `create`/`update`/`delete` publish the matching lifecycle event; `upsertDiscovered`/`resolveOwnership` are invoked from the Kafka consumers below, not from HTTP |
| `TeamService` | `create`, `update`, `delete`, `get`, `list`, `addMember`, `removeMember`, `listMembers`, `myTeamIds` | `create`/`update`/`delete` publish `team.created`/`updated`/`deleted` |
| `ServiceHealthService` | health probing | Backs `HealthProbeScheduler` |
| `PlanLimitService` | plan-limit checks | Backs `/internal/plan-limits`; advisory-lock guarded against TOCTOU |

---

## Inbound Ports (API)

All require header `X-Tenant-Id` (injected by Gateway). All responses use `ApiResponse<T>` envelope.
The service's own `server.servlet.context-path` is `/api/v1/registry` — paths below are relative to that
(e.g. `POST /services` is `POST /api/v1/registry/services` behind the Gateway route `Path=/api/v1/registry/**`).

| Method | Path | Service method |
|---|---|---|
| POST | `/services` | `ServiceService.create` |
| GET | `/services` | `ServiceService.list` |
| GET | `/services/counts-by-connection` | `ServiceService.countByConnectionId` |
| GET | `/services/tech-stacks` | `ServiceService.listTechStacks` |
| GET | `/services/orphaned` | `ServiceService.detectOrphans` |
| GET | `/services/{id}` | `ServiceService.get` |
| PUT | `/services/{id}` | `ServiceService.update` |
| DELETE | `/services/{id}` | `ServiceService.delete` |
| PATCH | `/services/{id}/owner` | `ServiceService.assignOwner` |
| DELETE | `/services/{id}/owner` | `ServiceService.assignOwner` (unassign) |
| GET | `/services/{id}/history` | `ServiceService.history` / `historyAt` |
| POST/GET/GET·`/mine`/GET·`{id}`/PUT·`{id}`/DELETE·`{id}` | `/teams` | Team CRUD — `TeamService` |
| GET/POST·`/members`/DELETE·`/members/{memberUserId}` | `/teams/{id}/members` | Team membership — `TeamService` |
| GET | `/internal/plan-limits/{tenantId}` | `PlanLimitInternalController` → `PlanLimitService` |
| GET | `/internal/services` | `ServiceInternalController` → `ServiceService.listAllActive` — cross-tenant, paginated; no `X-Tenant-Id`. Backs Topology's admin backfill (Topology issue [1.1]) |

There is no `/scm-connections` endpoint in this service — see the SCM Connection note above.

---

## Outbound Ports (Dependencies)

| Dependency | Type | Purpose |
|---|---|---|
| PostgreSQL | Spring Data JDBC + `NamedParameterJdbcTemplate` | All service, team, history reads/writes |
| Kafka | `KafkaTemplate` | Publish service + team lifecycle events |

---

## Kafka

**Produced:**

| Topic | Trigger |
|---|---|
| `cartogra.registry.service.registered` | Service created (manual or discovered) |
| `cartogra.registry.service.updated` | Service mutated |
| `cartogra.registry.service.deleted` | Service soft-deleted |
| `cartogra.registry.team.created` | Team created |
| `cartogra.registry.team.updated` | Team renamed |
| `cartogra.registry.team.deleted` | Team deleted |

`cartogra.registry.sync.command` is **not** produced here despite the `registry.` prefix — it is produced and
consumed entirely within `services/ingestion` (`SyncCommandProducer` → `SyncCommandConsumer`), triggered by an
SCM Connection created/synced there. See `docs/architecture/kafka-topics.md`.

**Consumed:**

| Topic | Consumer class | Method invoked |
|---|---|---|
| `cartogra.ingestion.service.discovered` | `RegistryServiceDiscoveryConsumer` | `ServiceService.upsertDiscovered` |
| `cartogra.ingestion.ownership.resolved` | `OwnershipResolvedConsumer` | `ServiceService.resolveOwnership` (single-match rule) |

---

## Database (Flyway V001–V015)

| Version | Description |
|---|---|
| V001 | `tenants` table |
| V002 | `teams` table |
| V003 | `users` table (auth data, owned by Gateway semantically) |
| V005 | `services` table (includes the discovery fields — `external_id`, `connection_id`, `source`, etc. — from day one) |
| V006 | `services_history` table |
| V008 | `refresh_tokens` table |
| V009 | `tenant_oidc_configs` table |
| V010 | `billing_plans` table |
| V011 | Add `plan_id` to `tenants` |
| V012 | `team_members` table |
| V013 | Add `max_teams` to `billing_plans` |
| V014 | `invitations` table |
| V015 | Add `disabled_at` to `users` |

V004 and V007 are gaps in the sequence — no migration with those numbers exists in this service (nothing to restore; Flyway only requires versions to be increasing, not contiguous). Neither `scm_connections` nor `scm_webhooks` live here — both moved to `services/ingestion`.

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
| `src/main/java/.../api/` | `ServiceController`, `TeamController`, `PlanLimitInternalController`, `GlobalExceptionHandler` |
| `src/main/java/.../api/dto/` | Request/response records (`static from(domain)` mapping on each response) |
| `src/main/java/.../domain/` | `Service`, `Team`, `TeamMember` records; `ServiceService`, `TeamService`, `ServiceHealthService`, `PlanLimitService`; `domain/event`, `domain/exception` |
| `src/main/java/.../repository/` | `ServiceRepository`, `TeamRepository`, `ServiceHistoryRepository`, `PlanLimitRepository`, `AdvisoryLockRepository` (port interfaces) + `ServiceFilter` |
| `src/main/java/.../infrastructure/jdbc/` | `JdbcServiceRepository`, `JdbcTeamRepository`, `JdbcServiceHistoryRepository`, `JdbcPlanLimitRepository`, `JdbcAdvisoryLockRepository` |
| `src/main/java/.../infrastructure/kafka/` | `ServiceLifecycleEventProducer`, `TeamLifecycleEventProducer`, `RegistryServiceDiscoveryConsumer`, `OwnershipResolvedConsumer` |
| `src/main/resources/db/migration/` | V001–V015 Flyway scripts (see table above) |
| `src/main/resources/application.yml` | DB, Kafka, OTel config; `server.servlet.context-path: /api/v1/registry` |

---

## ADRs

- ADR-0009 — Spring Data JDBC (no JPA/Hibernate)
- ADR-0015 — CODEOWNERS → auto-assign team_id when exactly one team matches; ambiguous/no-match → log WARN, skip
- ADR-0017 — Audit events ownership port (`AuditEventPort` in `shared:common`, planned for Phase 4)
- ADR-0021 — Service discovery upsert keyed on `(tenantId, externalId)`
- ADR-0022 — `TEAM_OWNER` enforced tenant-wide (superseded by ADR-0025)
- ADR-0025 — Live `team_members` check replaces the `TEAM_OWNER` role
