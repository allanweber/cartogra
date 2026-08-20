# ADR-0018 — Spec discovery transport

**Status**: Proposed
**Date**: 2026-05-20
**Deciders**: Allan Weber

---

## Context

Phase 3 task 3.30 says "Implement spec discovery from ingestion so discovered OpenAPI/AsyncAPI files are processed idempotently." Ingestion already has the mechanism to read files from SCM via `ScmProvider.getFileContents(...)`. The gap is the delivery mechanism from ingestion to the contract service.

Two transports are viable:

| Transport | Description | Cost |
|-----------|-------------|------|
| **(a) Kafka topic** | Ingestion publishes `cartogra.ingestion.spec.discovered`; contract consumes. | One topic + one consumer + idempotency rule. |
| **(b) REST** | Ingestion calls `POST /specs/discovered` on contract via a new `RestClient` bean. | New base-URL config in ingestion + `RestClient` bean + circuit breaker (task 1.71) + envelope handling. Couples ingestion availability to contract availability. |

The audit identified this as **dead-end 9** in the phase 1–5 review.

---

## Decision

**Recommend option (a) — Kafka topic `cartogra.ingestion.spec.discovered`.**

Payload:

```json
{
  "tenant_id": "<UUID>",
  "connection_id": "<UUID>",
  "repository_full_name": "<owner/repo>",
  "file_path": "openapi.yaml",
  "content_sha256": "<hex>",
  "content_b64": "<base64-encoded file bytes>"
}
```

Wrapped in the shared `EventEnvelope` with `eventType = "spec.discovered"`, `entityId` = a UUIDv5 derived from `(tenant_id, repository_full_name, file_path)` so the same file location across re-syncs produces the same `entityId` (deterministic per-file identity), `traceparent` carried on the Kafka header.

**Idempotency** in contract: skip processing if a `contract_versions` row already exists for `(tenant_id, file_path, content_sha256)`. The hash is the only thing that needs to change to trigger a new version.

Rationale:

1. **Decoupling**: contract restarts or deploys do not stall ingestion. Option (b) makes sync runs fragile to contract availability.
2. **Symmetric with the rest of the platform**: 1.38 (service lifecycle), 1.66 (SCM connection lifecycle), `SyncResultProducer` (sync.completed), and ADR-0017 (audit.recorded) all use Kafka. Adding spec discovery as Kafka keeps the cross-service eventing model consistent.
3. **No new HTTP surface in contract**: contract focuses on the customer-facing spec API; an internal-only `POST /specs/discovered` endpoint adds auth + envelope considerations that Kafka avoids.
4. **Idempotency is natural**: `content_sha256` is the perfect idempotency key for a file's contents.

Document the topic in [docs/architecture/kafka-topics.md](../architecture/kafka-topics.md) as part of task 3.29a.

---

## Consequences

### Positive

- Ingestion's responsibility ends at the publish; contract's consumer rate-limits itself naturally via consumer-group lag.
- The `content_b64` payload is bounded by typical OpenAPI spec sizes (single-digit MB at the outside); Kafka handles this fine with `max.request.size` tuning if needed.
- Reuses the existing producer + consumer + envelope + traceparent patterns; zero new infrastructure beyond a topic.

### Negative / Trade-offs

- Base64-encoded file contents inflate by ~33% in transit. Acceptable for typical spec sizes; if a tenant's spec exceeds Kafka message limits, the producer falls back to a reference + S3 pattern in a Phase 4 ADR (not blocked by this one).
- Adds one more Kafka topic to the platform's surface.

### Neutral

- Contract's parse/validate/store pipeline (task 3.8) becomes the consumer's body. No new parsing code.

---

## Alternatives Considered

| Option | Reason rejected |
|--------|-----------------|
| (b) REST `POST /specs/discovered` on contract | Synchronous coupling; requires new base-URL config, RestClient bean, circuit breaker. Defeats async-platform design. |
| Push raw file from ingestion to S3, send only a reference event | Adds an S3 dependency to Phase 3; revisit when payload sizes are demonstrably a problem. |
| Have contract poll SCM directly | Duplicates the ScmProvider work; couples contract to SCM auth. Ingestion's job is exactly this. |

---

## References

- Task 3.29a (this ADR), 3.30 (the implementation it unblocks)
- ADR-0017 — Audit events transport (same Kafka-over-REST reasoning)
- [docs/architecture/kafka-topics.md](../architecture/kafka-topics.md)
