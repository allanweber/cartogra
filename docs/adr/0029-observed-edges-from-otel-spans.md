# ADR-0029 — Observed edges from OTel spans

**Date:** 2026-10-07
**Status:** Accepted (supersedes [ADR-0016](ADR-0016-otel-span-worker-ingestion.md))
**Deciders:** Allan Weber

---

## Context

ADR-0016 proposed an OTel Collector `kafka` exporter feeding `cartogra.observability.spans`, consumed by Topology. Building it (roadmap 3.1) exposed three gaps in that proposal:

- The collector's `kafka` exporter emits raw OTLP; it cannot wrap spans in the platform `EventEnvelope`.
- Nothing stamps a tenant on spans, and Topology is tenant-scoped everywhere (`tenant_id` on every row).
- Spans do not always name their target: a CLIENT span often carries only an IP or a host, not `peer.service`.

## Decision

1. **Raw OTLP/JSON topic.** The collector exports with `encoding: otlp_json`; the topic `cartogra.observability.spans` is a documented exception to the Kafka envelope rule. `OtelSpanWorker` (Topology) parses it with Jackson 3 into minimal records, ignoring unknown fields. No new dependency.
2. **Tenant on the resource.** Spans carry the resource attribute `cartogra.tenant.id`. The dev collector `insert`s it from `CARTOGRA_TENANT_ID` only when absent. Until API keys exist (roadmap 5.5, trusted intake 5.9) this attribute is **unauthenticated** — anyone who can reach the collector can attribute spans to any tenant UUID that has live nodes. Acceptable for the dev stack only.
3. **Bad tenant goes to the DLQ.** Missing, non-UUID, or a tenant with no live `graph_nodes` throws the non-retryable `UnattributedSpanBatchException(reason)`. The existing `DefaultErrorHandler` publishes the record to `cartogra.observability.spans.dlq`; counter `cartogra.spans.quarantined{reason}`. Validation is per OTLP resource: attributable resources in the same record are still ingested, and the record then goes to the DLQ if any resource was quarantined.
4. **Target resolution.** For a CLIENT span the target is `peer.service`, else the host of `server.address` (port stripped), matched to `graph_nodes` by `lower(name)`.
5. **Span-id join fallback.** A CLIENT span whose target does not resolve waits in `pending_span_links` for the SERVER span whose `parent_span_id` equals its `span_id`; a SERVER span with an unseen parent waits for its CLIENT. On match the edge is upserted and both halves are hard-deleted. A reaper removes rows past `expires_at` (TTL 5 min, `topology.span-links.ttl`). Persisting the buffer survives restarts and multiple instances, and works in any arrival order. The partner lookup and pending insert run under a per-tenant `pg_advisory_xact_lock`, so two instances handling the halves of one call cannot both miss each other. A SERVER span whose CLIENT resolved directly in the same batch is not parked.
6. **Protocol.** `rpc.system=grpc` → `grpc`; `http.request.method` / `http.method` → `http`; anything else is skipped. Messaging and DB edges are out of scope (roadmap 3.7).
7. **Unknown names.** Unmatched `service.name` and `peer.service` values are upserted into `unresolved_span_services` (first/last seen, `seen_count`) for later review (roadmap 3.6). `server.address` hosts and IPs are never recorded. A name is not recorded when the fallback join resolves the edge in the same batch, and distinct names per tenant are capped (`topology.span-links.max-unresolved-per-tenant`, default 1000) because span data is untrusted.
8. **Freshness.** `dependencies.last_seen_at` (null for declared edges) is refreshed only when more than one minute stale, so a replayed or chatty span stream does not rewrite rows. Drift detection (3.3) reads it. It is deliberately not projected into `dependency_graph_edges`.
9. **Impact calculations.** Blast radius, SPOF and cycle detection keep counting observed edges alongside declared ones — an observed call is a real dependency. No code change.
10. **Graph refresh.** Any inserted or refreshed edge calls `DependencyGraphViewRefreshScheduler.markDirty()` once per batch.

## Consequences

### Positive

- Observed edges appear without any manual upkeep, idempotently on the existing edge-identity index.
- JSON fixtures published straight to Kafka exercise the whole path without a collector.

### Negative / Trade-offs

- Tenant attribution is spoofable until 5.9; the public environment must not expose the collector until then.
- The topic breaks the envelope rule, so no `event_id`-based dedupe: idempotency comes from the edge upsert instead.
- Cartogra's own services do not emit CLIENT spans yet (roadmap 7.9); only instrumented customer services produce edges.

### Neutral

- All of a tenant's resources in one Kafka record are ingested as one batch, so both halves of a call in the same record join in memory in either order. A parked SERVER span is deleted as soon as its CLIENT resolves directly (`peer.service` / `server.address`) in a later record. Only a SERVER span arriving in a record *after* its directly-resolved CLIENT's record (or racing it on another instance) still sits in `pending_span_links` until TTL.
- The collector's Kafka pipeline drops spans from the `topology` service (`filter/no-self`) so the worker's own consumer/JDBC/DLQ spans cannot feed it; revisit with 7.9.

## Alternatives Considered

| Option | Reason rejected |
|--------|----------------|
| Ingestion forwards `dependency.observed` events (original plan) | Extra hop and topic with no benefit; Ingestion has no span data |
| Topology REST endpoint for span batches (ADR-0016 option b) | Synchronous collector→Topology coupling, new auth surface |
| In-memory CLIENT/SERVER join | Loses halves on restart, breaks with multiple instances |
| Protobuf encoding | Needs the OTLP proto dependency; JSON is readable in fixtures |

## References

- ADR-0016, ADR-0028
- `docs/roadmap.md` 3.1, 3.3, 3.6, 3.7, 5.9, 7.9
- `plans/012-observed-edges-from-traces.md`
