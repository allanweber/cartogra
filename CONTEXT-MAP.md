# Cartogra — Context Map

> Bounded-context relationships across the monorepo, using the project's domain vocabulary.
> Each box is an independent deployable unit with its own schema, ubiquitous language, and lifecycle.

---

## Bounded Contexts

| Context | Module | Status |
|---------|--------|--------|
| **Identity & Access** | `services/gateway` | Live (Phase 0/1) |
| **Service Catalog** | `services/registry` | Live (Phase 0/1) |
| **Ingestion** | `services/ingestion` | Live (Phase 0/1) |
| **Topology** | `services/topology` | Planned (Phase 2) |
| **Contract** | `services/contract` | Planned (Phase 3) |
| **Intelligence** | `services/intelligence` | Planned (Phase 4) |
| **Frontend Shell** | `frontend/` | Partial (Phase 1) |
| **Shared Kernel** | `shared/common` | Live |

---

## Relationship Map

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        External Clients                                  │
│            Browser · CI/CD tools · Third-party integrations              │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │ HTTPS  (JWT cookie / Bearer / API Key)
                               ▼
        ╔══════════════════════════════════╗
        ║    Identity & Access (Gateway)   ║  ← Open Host Service
        ║  Issues JWT · Rate-limits Redis  ║
        ║  Injects X-Tenant-Id · Proxies   ║
        ╚═══════════╤══════════════════════╝
                    │  REST (Spring Cloud Gateway declarative proxy + traceparent)
          ┌─────────┼──────────────────────┐
          │         │                      │
          ▼         ▼                      ▼
  ╔════════════╗  ╔════════════╗   ╔══════════════╗
  ║  Service   ║  ║  Topology  ║   ║   Contract   ║
  ║  Catalog   ║  ║  (Ph. 2)   ║   ║   (Ph. 3)    ║
  ║ (Registry) ║  ╚═════╤══════╝   ╚══════╤═══════╝
  ╚═════╤══════╝         │                 │
        │                └────────┬────────┘
        │  Kafka                  │  Kafka
        │  (lifecycle events)     │  (graph + contract events)
        └────────────────┬────────┘
                         ▼
                ╔═════════════════╗
                ║  Intelligence   ║  ← Conformist consumer
                ║   (Ph. 4)       ║    (reads from all others)
                ╚═════════════════╝

  ╔═══════════════╗
  ║   Ingestion   ║  ← Separate inbound channel
  ║   (Ph. 0/1)   ║    (SCM webhooks · K8s watch)
  ╚══════╤════════╝
         │  Kafka: service.discovered / ownership.resolved
         ▼
  ╔════════════╗
  ║  Service   ║  ← Catalog consumes ingestion events
  ║  Catalog   ║
  ╚════════════╝

  ┌────────────────────┐
  │   Shared Kernel    │  shared/common — zero Spring dependencies
  │  EventEnvelope<P>  │  ApiResponse<T> · ApiError · PageResult<T>
  │  ErrorCodes        │  TenantId · ServiceId · SystemActors
  │  SyncCommandPayload│  UuidV5
  └────────────────────┘
```

---

## Relationship Types (DDD vocabulary)

| Upstream (U) | Downstream (D) | Relationship | Integration point |
|---|---|---|---|
| Identity & Access | Service Catalog | **Open Host Service / Conformist** | Declarative reverse proxy (Spring Cloud Gateway route, circuit-breaker guarded); Gateway forwards `X-Tenant-Id` derived from JWT |
| Identity & Access | Topology | Open Host Service / Conformist | Declarative reverse proxy (Phase 2 — route not yet created) |
| Identity & Access | Contract | Open Host Service / Conformist | Declarative reverse proxy (Phase 3 — route not yet created) |
| Identity & Access | Intelligence | Open Host Service / Conformist | Declarative reverse proxy (Phase 4 — route not yet created) |
| Service Catalog (U) | Topology (D) | **Customer / Supplier** | Kafka `cartogra.registry.service.{registered,updated,deleted}` |
| Service Catalog (U) | Contract (D) | Customer / Supplier | Kafka `cartogra.registry.service.deleted` |
| Service Catalog (U) | Intelligence (D) | Customer / Supplier | Kafka registry events |
| Ingestion (U) | Service Catalog (D) | **Published Language** | Kafka `cartogra.ingestion.service.discovered`, `ownership.resolved` |
| Ingestion (U) | Contract (D) | Published Language | Kafka `cartogra.ingestion.spec.updated` (Phase 3) |
| Ingestion (U) | Topology (D) | Published Language | Kafka `cartogra.ingestion.dependency.observed` (Phase 2) |
| Topology (U) | Intelligence (D) | Customer / Supplier | Kafka `cartogra.topology.graph.updated`, `drift.detected`, `cycle.detected` |
| Contract (U) | Intelligence (D) | Customer / Supplier | Kafka `cartogra.contract.check.failed`, `version.published` |
| Shared Kernel | All JVM modules | **Shared Kernel** | Gradle dep `:shared:common` |

**Anti-corruption layer notes:**
- Gateway strips any client-supplied `X-Tenant-Id` / `X-User-Id` before forwarding — protecting all downstream contexts from tenant spoofing.
- Each Kafka consumer extracts `traceparent` via `W3CTraceContextPropagator` — trace context never bleeds across context boundaries unintentionally.
- Cross-context references store IDs only (e.g. Topology stores `source_service_id` as a UUID, never a hydrated `Service` object from the Catalog context).

---

## Data Sovereignty

Each bounded context owns its schema exclusively. No cross-service joins allowed.

| Context | Database schema | Flyway history table |
|---|---|---|
| Identity & Access | Reads registry tables directly (shared DB, Phase 0 compromise) | — |
| Service Catalog | `public` (via registry service) | `flyway_schema_history_registry` |
| Ingestion | `public` (via ingestion service) | `flyway_schema_history_ingestion` |
| Topology | Dedicated schema (Phase 2) | `flyway_schema_history_topology` |
| Contract | Dedicated schema (Phase 3) | `flyway_schema_history_contract` |
| Intelligence | Dedicated schema (Phase 4) | `flyway_schema_history_intelligence` |

> **Phase 0 compromise**: Gateway reads the registry service's `users`, `tenants`, `refresh_tokens`, and `tenant_oidc_configs` tables. This avoids a premature auth microservice. Tracked in ADR-0010.

---

## Event Flow Summary

```
SCM / K8s ──► Ingestion ──Kafka──► Service Catalog ──Kafka──► Topology ──Kafka──► Intelligence
                    │                                    │
                    └──────────────────────────Kafka──► Contract ──Kafka──► Intelligence
                                                                      │
                                                          Intelligence ──Kafka──► Notification worker
```

---

## See Also

- `services/gateway/CONTEXT.md` — Identity & Access context
- `services/registry/CONTEXT.md` — Service Catalog context
- `services/ingestion/CONTEXT.md` — Ingestion context
- `services/topology/CONTEXT.md` — Topology context (planned)
- `services/contract/CONTEXT.md` — Contract context (planned)
- `services/intelligence/CONTEXT.md` — Intelligence context (planned)
- `frontend/CONTEXT.md` — Frontend Shell context
- `shared/common/CONTEXT.md` — Shared Kernel
- `docs/architecture/kafka-topics.md` — full topic catalog
- `docs/architecture/data-model.md` — per-service table definitions
