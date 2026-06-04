# Intelligence Context

**Service**: `services/intelligence` · Port `8084` · Phase 4 · **Planned**

---

## Purpose

Intelligence is Cartogra's AI layer. It listens to events from every other context, maintains per-service health scores, answers natural language queries about the architecture, and generates periodic digests. It uses the Claude API (cheapest available model) and is a pure consumer — it never mutates other contexts' data.

---

## Ubiquitous Language

| Term | Meaning in this context |
|---|---|
| **Health Score** | A 0–100 numeric rating for a Service; computed from deployment recency, test coverage, dependency health, ownership, contract compliance, and drift state |
| **Score Factor** | A named dimension contributing to the Health Score (e.g., `ownership`, `contract_compliance`, `dependency_health`) |
| **NL Query** | A natural language question posed by a user about the architecture (e.g., "Which services own the payment domain?") |
| **Architecture Digest** | A weekly AI-generated summary of the tenant's architecture health, notable changes, and risk trends |
| **Alert** | An AI-detected anti-pattern or anomaly (e.g., cycle found, orphan cluster, high blast radius with low health score) |
| **Context Window** | The set of graph snapshots, contract states, and service metadata assembled for a Claude prompt |

---

## Core Responsibilities

1. **Health score computation** — recompute scores on registry, topology, and contract events; persist to `health_scores`
2. **NL query serving** — accept user questions; assemble context window; call Claude API; return answer; log to `nl_query_logs`
3. **Digest generation** — weekly scheduled job; compile tenant-wide health summary; publish `digest.generated`
4. **Alert detection** — react to topology cycles, high-risk drift, breaking contract failures; publish `alert.raised`
5. **Feedback loop** — record user `positive`/`negative` ratings on NL query responses for future tuning

---

## Domain Model

```
Service (ID ref from Registry) ──< HealthScore
User (ID ref from Registry)    ──< NlQueryLog
```

**Tables** (Phase 4 schema):

| Table | Purpose |
|---|---|
| `health_scores` | Latest computed score per service; `factors` JSONB breakdown |
| `nl_query_logs` | Per-query audit trail; model used, latency_ms, feedback |

---

## Inbound Ports (API) — Planned

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/intelligence/query` | Submit NL query; returns AI answer |
| GET | `/api/v1/intelligence/health-scores` | List health scores for tenant |
| GET | `/api/v1/intelligence/health-scores/{serviceId}` | Health score for one service |
| POST | `/api/v1/intelligence/query/{id}/feedback` | Submit thumbs up/down on a query response |

---

## Kafka

**Consumed:**

| Topic | Source | Action |
|---|---|---|
| `cartogra.registry.service.registered` | Registry | Seed initial health score |
| `cartogra.registry.service.updated` | Registry | Recompute health score |
| `cartogra.registry.service.deleted` | Registry | Mark health score inactive |
| `cartogra.topology.graph.updated` | Topology | Refresh dependency-health factor |
| `cartogra.topology.drift.detected` | Topology | Penalise drift factor; raise alert if severe |
| `cartogra.topology.cycle.detected` | Topology | Raise alert |
| `cartogra.contract.check.failed` | Contract | Penalise contract-compliance factor; raise alert |
| `cartogra.contract.version.published` | Contract | Update contract-compliance factor |

**Produced:**

| Topic | Trigger |
|---|---|
| `cartogra.intelligence.digest.generated` | Weekly scheduled job |
| `cartogra.intelligence.alert.raised` | Anti-pattern or anomaly detected |

---

## Claude API Integration

- Model: cheapest available Claude model (per CLAUDE.md rule; currently `claude-haiku-4-5-20251001`)
- Prompt caching: use `cache_control` on stable context blocks (service metadata, graph snapshots)
- No streaming for NL queries (latency acceptable at this scope)
- Latency and model tracked in `nl_query_logs` per query

---

## Context Relationships

| Neighbour | Relationship | Notes |
|---|---|---|
| Service Catalog (Registry) | Downstream (Conformist) | Consumes all registry lifecycle events |
| Topology | Downstream (Conformist) | Consumes graph + drift + cycle events |
| Contract | Downstream (Conformist) | Consumes contract check results |
| Identity & Access (Gateway) | Conformist | Receives proxied requests with `X-Tenant-Id` |
| Shared Kernel | Shared Kernel | `EventEnvelope`, `ApiResponse`, `ErrorCodes` |
| Claude API (external) | External Service | NL query answering + digest generation |

---

## ADRs

- ADR-0016 — OTel span worker feeds observed dependencies → Topology → Intelligence health score
