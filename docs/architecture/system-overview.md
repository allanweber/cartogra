# Cartogra — System Overview

## Purpose

Cartogra is a **multi-tenant service intelligence platform**. It auto-discovers microservices from Kubernetes and SCM providers, maps declared and observed dependencies, guards API contracts, and surfaces AI-generated insights. External clients communicate with the Gateway via REST. The Gateway proxies requests to downstream services via REST using Spring's `RestClient`. Asynchronous events flow over Kafka. No service calls another service's database.

---

## Implementation Status

| Service | Phase | Status |
|---------|-------|--------|
| `gateway` | Phase 0 | Implemented |
| `registry` | Phase 0 | Implemented |
| `ingestion` | Phase 0 | Implemented |
| `frontend` | Phase 1 | Planned |
| `topology` | Phase 2 | Planned |
| `contract` | Phase 3 | Planned |
| `intelligence` | Phase 4 | Planned |

---

## Service Map

| Service | Port | Responsibility |
|---------|------|---------------|
| `gateway` | 8080 | Auth (JWT issuance), tenant injection (`X-Tenant-Id`), rate limiting (Redis), reverse proxy to domain services |
| `registry` | 8081 | Service CRUD, team ownership, SCM ingestion, K8s sync, API contract storage |
| `topology` | 8082 | Dependency graph, blast radius, cycle detection, SPOF scoring, drift detection |
| `contract` | 8083 | Contract validation, breaking-change detection, consumer-producer matrix, outbox relay |
| `intelligence` | 8084 | Claude API integration, natural language queries, health score, architecture digest |
| `ingestion` | 8085 | SCM/K8s sync workers, webhook receivers (GitHub, Azure DevOps), spec discovery |
| `frontend` | 3000 | TanStack Start SPA — catalog, D3 dependency graph, contract hub, intelligence panel |

---

## Architecture Style

```
┌─────────────────────────────────────────────────────┐
│                     Browser / CI                    │
└──────────────────────┬──────────────────────────────┘
                       │ HTTPS
                       ▼
              ┌─────────────────┐
              │    Gateway      │  JWT · Redis rate limit
              │    :8080        │  X-Tenant-Id injection
              └────────┬────────┘
          ┌────────────┼────────────┐
          │            │            │
          ▼            ▼            ▼
     ┌─────────┐  ┌──────────┐  ┌──────────┐
     │Registry │  │Topology  │  │Contract  │
     │ :8081   │  │  :8082   │  │  :8083   │
     └────┬────┘  └────┬─────┘  └────┬─────┘
          │            │              │
          └────────────┴──────────────┘
                       │ Kafka
                       ▼
              ┌─────────────────┐
              │  Intelligence   │
              │    :8084        │
              └─────────────────┘

     ┌─────────────────┐
     │   Ingestion     │  Webhooks (GitHub, Azure DevOps)
     │    :8085        │  K8s watch · SCM polling
     └─────────────────┘
```

---

## Cross-Cutting Concerns

### Multi-Tenancy

- Every domain table carries `tenant_id UUID NOT NULL`.
- Gateway strips client-supplied `X-Tenant-Id` and re-injects from the validated JWT (`tid` claim).
- PostgreSQL RLS policy on all tenant tables as defence-in-depth.
- Redis keys: `tenant:{tenantId}:...`

### Authentication

| Client type | Mechanism |
|------------|-----------|
| Browser | httpOnly JWT cookie (gateway-issued) |
| Non-browser API | `Authorization: Bearer <token>` |
| CI/automation | `X-Cartogra-Api-Key: <key>` (tenant-scoped) |

Auth flows: local email+password with Resend OTP, Google OAuth, GitHub OAuth, per-tenant OIDC. All token issuance in the gateway — no separate auth service.

### Observability

- **Tracing:** OpenTelemetry on every JVM service; OTLP export; W3C `traceparent` propagated to all downstream HTTP calls and Kafka message headers.
- **Metrics:** Micrometer → Prometheus → Grafana.
- **Logs:** Structured JSON; every log line includes `traceId`.
- **HTTP envelope:** All REST responses carry `traceId` in body and `X-Trace-Id` response header.

### HTTP Response Envelope

```json
// Success
{ "data": <T>, "traceId": "a3f1c8d2...32 hex chars" }

// Error
{ "error": { "code": "...", "message": "...", "details": {} }, "traceId": "..." }
```

Webhook receivers (GitHub, Azure DevOps) are exempt — they respond as the upstream expects.

### Persistence

- PostgreSQL for all domain data; Spring Data JDBC (never JPA/Hibernate).
- Flyway migrations per-service under `src/main/resources/db/migration/`.
- Redis for session state, rate limit counters, and short-lived caches.
- Soft deletes only: `deleted_at TIMESTAMPTZ`; no `DELETE` on domain rows.

### Communication Model

| Channel | Protocol | When to use |
|---------|----------|-------------|
| External (client → Gateway) | REST / JSON / HTTPS | Browser, API clients, CI integrations |
| Internal synchronous (Gateway → service) | REST / JSON / HTTP (`RestClient`) | Gateway-proxied calls to domain services (registry, topology, contract, intelligence) |
| Internal asynchronous (service → service) | Kafka | Event propagation, decoupled workflows, fan-out |

No service calls another service's database — all cross-service data access goes through the Gateway (REST) or Kafka.

### Messaging

- Apache Kafka for all async event propagation.
- Topic naming: `cartogra.{domain}.{entity}.{event}`.
- Every Kafka message uses the standard event envelope (`event_id`, `event_type`, `entity_id`, `tenant_id`, `timestamp`, `version`, `correlation_id`, `payload`).
- W3C `traceparent` included as a Kafka header on every producer send.

---

## Data Flow Examples

### Service Registration

```
POST /registry/services (via Gateway)
  → Registry validates + persists
  → Publishes cartogra.registry.service.registered
  → Topology consumer updates graph
  → Intelligence consumer updates health baseline
```

### Breaking Contract Change

```
POST /ingestion/webhooks/github (raw, no envelope)
  → Ingestion detects spec change
  → Publishes cartogra.ingestion.spec.updated
  → Contract consumer runs compatibility check
  → Publishes cartogra.contract.check.failed (if breaking)
  → Notification pipeline → Slack/Teams webhook
```

---

## References

- [data-model.md](data-model.md) — per-service table definitions
- [kafka-topics.md](kafka-topics.md) — full topic catalog
- [ADR-0001](../adr/ADR-0001-postgresql-over-graph-database.md) — graph storage decision
- [ADR-0002](../adr/ADR-0002-scm-provider-abstraction.md) — SCM SPI decision
- ADR-0003 — gRPC for internal sync communication (Superseded 2026-05-18; deferred to Phase 6 research)
