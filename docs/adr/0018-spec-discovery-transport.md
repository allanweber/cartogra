# ADR-0018 — Spec discovery transport

**Status**: Accepted (implemented in roadmap 5.8)
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
  "service_id": "<UUID>",
  "repository_full_name": "<owner/repo>",
  "file_path": "openapi.yaml",
  "content_sha256": "<hex>",
  "content_b64": "<base64-encoded file bytes>"
}
```

Wrapped in the shared `EventEnvelope` with `eventType = "spec.discovered"`, `entityId` = a UUIDv5 derived from `(tenant_id, repository_full_name, file_path)` so the same file location across re-syncs produces the same `entityId` (deterministic per-file identity), `traceparent` carried on the Kafka header.

`service_id` is the catalog service ingestion resolved from the repository (ADR-0021 keying); contract upserts the service's API contract by it.

**Idempotency** in contract: skip processing if a `contract_versions` row already exists for `(tenant_id, repository_full_name, file_path, content_sha256)`. The hash is the only thing that needs to change to trigger a new version.

> **Amended 2026-11-19 (story 5.8, implementation).** This amendment replaces the payload, the service-resolution
> rule and the idempotency rule above.
>
> - **No `service_id` in the payload.** Ingestion cannot know it: Registry makes service ids with a random UUID,
>   keyed by `(tenant, external_id = repository full path)` (ADR-0021). The payload is
>   `{tenantId, connectionId, repositoryFullName, filePath, contentSha256, contentB64}` (camelCase, as every other
>   envelope payload). The message key is the `entityId`.
> - **Contract resolves the repository to a service.** It looks in its `services` projection by `external_id`
>   first, and only a live service is a hit: a tombstoned service is a miss. On a miss, it calls Registry `GET /internal/services/by-external-id` with the internal token, and stores
>   a hit in the projection. `service.registered`/`service.updated` store `externalId` in the projection.
> - **PENDING.** The first sync sends `service.discovered` and `spec.discovered` milliseconds apart, so the spec can
>   come before the service. A spec with no service stays `PENDING` in `discovered_specs`. Contract applies it when
>   `service.registered`/`service.updated` names the repository, or when a later sync sends the same spec again.
> - **Last spec seen.** Contract records the last spec seen for each `(tenant_id, repository_full_name, file_path)`:
>   the `discovered_specs` row with `is_latest` (a partial unique index). A spec is skipped only when its
>   `content_sha256` equals that row and the service of that row is still live. If that row is `SKIPPED`, or its
>   service is tombstoned, Contract inserts a `PENDING` copy as the new latest row and resolves the repository
>   again, so a deleted and registered-again service gets the spec. A different spec gets a new row, also a revert
>   to an older content: A, then B, then A again publishes A as a new version. An unchanged spec that comes again is
>   skipped, so a manual `POST /versions` between two syncs is not overwritten on each sync.
> - **No stale spec.** A new row moves an older `PENDING` row of the same repository and path to `SUPERSEDED`. Only
>   the latest spec of a path can be `PENDING`, so `service.registered` never applies an old spec over a newer one.
> - **Statuses.** A new hash with the same canonical spec as the latest version gives `UNCHANGED` and no version. An
>   invalid spec gives `REJECTED` with the parser errors and is not dead-lettered. A service that Registry resolves
>   but that the projection has tombstoned gives `SKIPPED`.
> - **Size cap.** Ingestion sends no event for a spec over 512 KiB or an empty one (GitHub gives empty content
>   over 1 MB). It counts the skip in `cartogra.ingestion.spec.skipped` (tag `reason`). Kafka keeps its default
>   1 MB `max.request.size`.
> - **Versions** that discovery stores have `source = discovery` and link to their `discovered_specs` row. The
>   actor of the audit is the system actor.

> **Amended 2026-11-11.** The original key `(tenant_id, file_path, content_sha256)` collided across repositories that keep the spec at the same path (every Acme repo has `openapi.yaml`), and the payload carried no way to map a file to a service. Added `repository_full_name` to the key and `service_id` to the payload (roadmap 5.8).

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

- Task 3.29a (this ADR), 3.30 (the implementation it unblocks) — now roadmap 5.8
- ADR-0017 — Audit events transport (same Kafka-over-REST reasoning)
- [docs/architecture/kafka-topics.md](../architecture/kafka-topics.md)
