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
| **Topology** | `services/topology` | Skeleton (Phase 0 done: schema, CI, IT harness; no service/controller/consumer yet — that's Phase 1) |
| **Contract** | `services/contract` | Empty directory (Phase 5) |
| **Intelligence** | `services/intelligence` | Empty directory (Phase 6) |
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
  ║  Catalog   ║  ║  (Ph. 1)   ║   ║   (Ph. 5)    ║
  ║ (Registry) ║  ╚═════╤══════╝   ╚══════╤═══════╝
  ╚═════╤══════╝         │                 │
        │                └────────┬────────┘
        │  Kafka                  │  Kafka
        │  (lifecycle events)     │  (graph + contract events)
        └────────────────┬────────┘
                         ▼
                ╔═════════════════╗
                ║  Intelligence   ║  ← Conformist consumer
                ║   (Ph. 6)       ║    (reads from all others)
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
| Identity & Access | Topology | Open Host Service / Conformist | Declarative reverse proxy — route already exists in `gateway/application.yml` (`Path=/api/v1/topology/**`); no endpoints behind it yet, that's Phase 1 |
| Identity & Access | Contract | Open Host Service / Conformist | Declarative reverse proxy (Phase 5 — route not yet created) |
| Identity & Access | Intelligence | Open Host Service / Conformist | Declarative reverse proxy (Phase 6 — route not yet created) |
| Service Catalog (U) | Topology (D) | **Customer / Supplier** | Planned, Phase 1: Kafka `cartogra.registry.service.{registered,updated,deleted}`, plus a planned `ownership-changed` event for orphan risk only (ADR-0027). No consumer exists in Topology yet — Registry only produces the three lifecycle topics today, and `ownership-changed` does not exist in code at all |
| Service Catalog (U) | Contract (D) | Customer / Supplier | Planned, Phase 5: Kafka `cartogra.registry.service.deleted` |
| Service Catalog (U) | Intelligence (D) | Customer / Supplier | Planned, Phase 6: Kafka registry events |
| Ingestion (U) | Service Catalog (D) | **Published Language** | Live today: Kafka `cartogra.ingestion.service.discovered`, `ownership.resolved` |
| Ingestion (U) | Contract (D) | Published Language | Planned, Phase 5 (5.8): Kafka `cartogra.ingestion.spec.discovered` |
| Topology (U) | Intelligence (D) | Customer / Supplier | Planned, deferred to Phase 6 per roadmap 3.4 — exact topic names TBD, see `docs/roadmap.md` §6/§9 |
| Contract (U) | Intelligence (D) | Customer / Supplier | Planned, Phase 6 — no topic names committed yet in `docs/roadmap.md` |
| Shared Kernel | All JVM modules | **Shared Kernel** | Gradle dep `:shared:common` |

**Observed dependencies (Phase 3) no longer route through Ingestion.** Earlier drafts of this map had
Ingestion publish `cartogra.ingestion.dependency.observed` for Topology to consume. The current plan of
record (`docs/roadmap.md` 3.1) instead has the OTel Collector export spans directly to
`cartogra.observability.spans`, consumed by Topology's own `OtelSpanWorker` — Ingestion is not in that path.

**Orphan-risk ownership source is unresolved between two docs.** ADR-0027 records the chosen mechanism as
Registry publishing a new `cartogra.registry.service.ownership-changed` topic, consumed by Topology solely to
flag orphan status. `docs/roadmap.md` 3.3 instead describes Topology consuming the already-existing
`cartogra.ingestion.ownership.resolved` topic "without a registry round-trip," citing the same ADR. Neither
topic is consumed by Topology today — this is Phase 3, unbuilt — so this map follows the ADR (the formal
decision record) and flags the roadmap wording as needing reconciliation before 3.3 is implemented.

**Anti-corruption layer notes:**
- Gateway strips any client-supplied `X-Tenant-Id` / `X-User-Id` before forwarding — protecting all downstream contexts from tenant spoofing.
- Each Kafka consumer extracts `traceparent` via `W3CTraceContextPropagator` — trace context never bleeds across context boundaries unintentionally.
- Cross-context references store IDs only (e.g. Topology stores `source_service_id` as a UUID, never a hydrated `Service` object from the Catalog context).
- Exception: Topology will mirror `team_id` from a Registry ownership event solely to flag orphan risk on `/v1/risks` (planned, Phase 3 — ADR-0027; see the reconciliation note above on which topic) — a narrow, documented deviation, not a general hydration path.

---

## Data Sovereignty

Each bounded context owns its own Postgres schema (same `cartogra` database, one schema per service via
`currentSchema=<service>` in each `application.yml`). No cross-service joins allowed. Every schema's Flyway
history table is simply `flyway_schema_history` — scoping comes from the schema, not a suffixed table name.

| Context | Database schema | Flyway history table |
|---|---|---|
| Identity & Access | Reads the `registry` schema's tables directly (shared DB, Phase 0 compromise) — owns no migrations itself | — |
| Service Catalog | `registry` | `flyway_schema_history` (in `registry`) |
| Ingestion | `ingestion` | `flyway_schema_history` (in `ingestion`) |
| Topology | `topology` — already dedicated, not a future-phase item; `dependencies`, `dependency_drifts`, `dependency_graph_edges` MV exist there today | `flyway_schema_history` (in `topology`) |
| Contract | Not created yet (Phase 5) | — |
| Intelligence | Not created yet (Phase 6) | — |

> **Phase 0 compromise**: Gateway reads the `registry` schema's `users`, `tenants`, `refresh_tokens`, and `tenant_oidc_configs` tables directly. This avoids a premature auth microservice. Tracked in ADR-0010.

---

## Event Flow Summary

```
SCM / K8s ──► Ingestion ──Kafka──► Service Catalog ──Kafka──► Topology ──Kafka──► Intelligence
                    │                                    │
                    └──────────────────────────Kafka──► Contract ──Kafka──► Intelligence
```

Today, only the first arrow is real: Ingestion → Service Catalog (`service.discovered`, `ownership.resolved`).
Everything from Topology onward is still schema-and-plan — see `docs/roadmap.md`. There is no "Notification
worker" module, planned or otherwise; it was removed from the plan as a speculative context with no producer,
no consumer, and no code (`docs/roadmap.md` §12).

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
