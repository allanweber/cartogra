# ADR-0016 — OtelSpanWorker ingestion path

**Status**: Proposed
**Date**: 2026-05-20
**Deciders**: Allan Weber

---

## Context

Phase 2 task 2.20 says "Implement `OtelSpanWorker` so real or synthetic spans can create observed edges" but does not specify how the topology service receives span data. Three viable ingestion mechanisms exist:

| Mechanism | Description | Cost |
|-----------|-------------|------|
| **(a) Kafka topic from OTel Collector** | The OTel Collector is already in the local stack ([infra/docker-compose/otel-collector.yml](../../infra/docker-compose/otel-collector.yml)) and the staging Helm path (ADR-0008 LGTM stack). Add a `kafka` exporter that emits spans to `cartogra.observability.spans`; topology consumes. | Collector config + one new topic. |
| **(b) Topology exposes `POST /topology/observed-edges`** | The gateway forwards span batches from the OTel Collector via HTTP. | New REST endpoint + auth + envelope contract + collector-side HTTP exporter config. Adds a synchronous coupling between collector and topology. |
| **(c) Direct OTLP receiver inside topology** | Topology embeds an OTLP receiver (gRPC or HTTP) and competes with the collector for spans. | New port; bypasses the collector's batching / sampling pipeline. |

The audit identified this as **dead-end 8** in the phase 1–5 review.

---

## Decision

**Recommend option (a) — Kafka topic from OTel Collector.**

Rationale:

1. **Consistent eventing model**: the rest of the platform uses Kafka for asynchronous cross-service communication. Adding a span Kafka topic keeps topology's read-path consistent with how it already consumes `cartogra.registry.service.*`.
2. **Batching and back-pressure for free**: the OTel Collector already buffers and batches; topology consumes at its own pace via the consumer group lag mechanism.
3. **No new endpoints**: option (b) requires new auth (the OTel Collector doesn't carry a JWT), envelope handling (or an envelope exception), and rate-limit considerations. Option (a) reuses existing Kafka security.
4. **No collector bypass**: option (c) creates two ingestion paths (Tempo via collector, topology via OTLP direct) for the same span data, which complicates sampling and risks divergence.

Topic: `cartogra.observability.spans`.
Payload: the OTel `ResourceSpans` proto serialised as JSON (Jackson 3), wrapped in the shared `EventEnvelope` with `eventType = "observability.span.batch"`, `tenantId` derived from the `tenant.id` resource attribute set by the gateway, and `traceparent` carried on the Kafka header.

Document the topic in [docs/architecture/kafka-topics.md](../architecture/kafka-topics.md) as part of task 2.19a.

---

## Consequences

### Positive

- One ingestion path, one set of tests, one consumer-lag metric.
- Synthetic span fixtures are trivial: produce the JSON payload directly to `@EmbeddedKafka`; no collector required in tests.
- Tempo continues to receive the same spans for trace UI; the Kafka exporter is additive.

### Negative / Trade-offs

- Adds one Kafka topic and one collector exporter configuration. Operational footprint grows slightly.
- The collector must set `tenant.id` as a resource attribute for partitioning to work. Gateway-issued OTel context already carries the tenant; the collector config needs to surface it.

### Neutral

- `OtelSpanWorker` becomes a standard Kafka consumer — same shape as `SyncCommandConsumer`, same `traceparent` extraction pattern.

---

## Alternatives Considered

| Option | Reason rejected |
|--------|-----------------|
| (b) REST endpoint on topology | Adds auth + envelope complexity for a non-user-facing ingest path; couples collector retries to topology availability. |
| (c) Direct OTLP receiver inside topology | Bypasses the existing collector pipeline; risks divergence with Tempo; doubles operational surface for spans. |

---

## References

- ADR-0008 — LGTM observability stack
- Task 2.20 (the consumer this ADR unblocks)
- [docs/architecture/kafka-topics.md](../architecture/kafka-topics.md)
