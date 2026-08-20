# ADR-0021 — Service Discovery Upsert Keying Strategy

## Context

Services can be discovered from multiple sources (GitHub, Azure DevOps, Kubernetes). The ingestion
service publishes a `service.discovered` event for every non-archived repo and every K8s Service.
The registry must upsert service rows idempotently: re-syncing the same repo must not create duplicate
rows.

The `services` table already has a unique index on `(tenant_id, lower(name)) WHERE deleted_at IS NULL`,
but service names in SCM do not always match the human-assigned registry name, and K8s service names
are not guaranteed to match either.

## Decision

Key discovery upserts on `(tenantId, externalId)` where `externalId` is the source system's stable
opaque identifier:

- SCM sources: `externalId = repo.fullPath` (e.g., `acme-corp/payments-service`)
- Kubernetes: `externalId = "{namespace}/{k8s-service-name}"`

A partial unique index `(tenant_id, external_id) WHERE external_id IS NOT NULL AND deleted_at IS NULL`
enforces uniqueness. The upsert implementation uses find-then-update-or-insert rather than
`ON CONFLICT` to avoid PostgreSQL restrictions with partial unique indexes in upsert clauses.

## Consequences

**Positive**
- Idempotent: replaying the same event is safe — second call updates the row rather than creating a duplicate.
- Source-system renames (GitHub repo transfer) produce a new row rather than silently overwriting.
- Manually-created services (no `externalId`) are unaffected.

**Negative**
- Repository renames in SCM break the key: the old `externalId` becomes orphaned; the new name creates a new row.
- A service discovered from both SCM and K8s gets two separate rows (different `source` values).
- find-then-update-or-insert introduces a short race window under concurrent consumers; acceptable for a single consumer group.

**Neutral**
- `externalId` is opaque to users and not exposed in the public API response.
