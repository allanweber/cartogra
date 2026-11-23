# Cartogra — Data Model

Each service owns its schema entirely. No cross-service joins. Cross-aggregate references store IDs only.

## Implementation Status

| Schema group | Phase | Status |
|---|---|---|
| Registry — tenants, teams, users, scm_connections | Phase 0 | Implemented |
| Registry — services, services_history, tenant_api_keys | Phase 1 | Planned |
| Topology — dependencies, dependency_drifts | Phase 2 | Planned |
| Contract — api_contracts, contract_versions, deleted_services | Phase 5 (5.1) | Implemented |
| Contract — discovered_specs, services.external_id, contract_versions.source | Phase 5 (5.8) | Implemented |
| Ingestion — tenant_spec_paths | Phase 5 (5.8) | Implemented |
| Contract — inbound_edges (5.4); Topology — outbox (5.4) | Phase 5 | Implemented |
| Contract — contract_checks (5.5) | Phase 5 | Planned |
| Intelligence — nl_query_logs, health_scores | Phase 4 | Planned |

---

All tables follow these conventions unless noted:

- `id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY`
- `tenant_id UUID NOT NULL`
- Timestamps: `TIMESTAMPTZ` (never bare `TIMESTAMP`)
- Soft delete: `deleted_at TIMESTAMPTZ` (no hard `DELETE` on domain rows)
- `snake_case` names

---

## Registry Service (`services/registry`)

### `tenants`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `name` | TEXT NOT NULL | Display name |
| `slug` | TEXT NOT NULL UNIQUE | URL-safe identifier |
| `plan` | TEXT NOT NULL | `free` \| `pro` \| `enterprise` |
| `created_at` | TIMESTAMPTZ | |
| `deleted_at` | TIMESTAMPTZ | Soft delete |

### `teams`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | FK → tenants |
| `name` | TEXT NOT NULL | |
| `slack_channel` | TEXT | |
| `created_at` | TIMESTAMPTZ | |
| `deleted_at` | TIMESTAMPTZ | |

### `users`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `email` | TEXT NOT NULL UNIQUE | |
| `password_hash` | TEXT | NULL for OAuth-only accounts |
| `otp_secret` | TEXT | Encrypted |
| `roles` | TEXT[] | `admin`, `member`, `viewer` |
| `created_at` | TIMESTAMPTZ | |
| `deleted_at` | TIMESTAMPTZ | |

### `scm_connections`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `provider` | TEXT NOT NULL | `github` \| `azure_devops` |
| `installation_id` | TEXT | GitHub App installation |
| `credentials` | JSONB | Encrypted; PAT/SP for Azure, App key for GitHub |
| `created_at` | TIMESTAMPTZ | |
| `deleted_at` | TIMESTAMPTZ | |

### `services`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `name` | TEXT NOT NULL | |
| `description` | TEXT | |
| `team_id` | UUID | FK → teams (ID only) |
| `repository_url` | TEXT | |
| `tech_stack` | JSONB | Detected build files |
| `metadata` | JSONB | Flexible properties + GIN index |
| `health_status` | TEXT | `healthy` \| `degraded` \| `unknown` |
| `last_deployed_at` | TIMESTAMPTZ | |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |
| `deleted_at` | TIMESTAMPTZ | |

### `services_history`
Append-only audit log — one row per version.

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `service_id` | UUID NOT NULL | |
| `tenant_id` | UUID NOT NULL | |
| `snapshot` | JSONB NOT NULL | Full service row at time of change |
| `changed_by` | UUID | user_id |
| `changed_at` | TIMESTAMPTZ | |

### `tenant_api_keys`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `key_hash` | TEXT NOT NULL | SHA-256 of raw key; raw key never stored |
| `name` | TEXT | Human label |
| `last_used_at` | TIMESTAMPTZ | |
| `expires_at` | TIMESTAMPTZ | |
| `created_at` | TIMESTAMPTZ | |
| `revoked_at` | TIMESTAMPTZ | Soft revoke |

---

## Topology Service (`services/topology`)

### `dependencies`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `source_service_id` | UUID NOT NULL | ID ref to registry.services |
| `target_service_id` | UUID NOT NULL | ID ref to registry.services |
| `dependency_type` | TEXT | `declared` \| `observed` |
| `protocol` | TEXT | `http` \| `grpc` \| `kafka` \| `db` |
| `metadata` | JSONB | GIN index |
| `created_at` | TIMESTAMPTZ | |
| `deleted_at` | TIMESTAMPTZ | |

*Graph traversal via recursive CTEs. See ADR-0001.*

### `dependency_drifts`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `source_service_id` | UUID | |
| `target_service_id` | UUID | |
| `drift_type` | TEXT | `undeclared` \| `missing` |
| `detected_at` | TIMESTAMPTZ | |
| `resolved_at` | TIMESTAMPTZ | |
| `resolved_by` | UUID | Null when closed automatically |
| `resolution` | TEXT | `accepted` \| `removed` \| `dismissed` \| `auto`; set iff `resolved_at` is set. `dismissed` suppresses re-detection |

### `outbox` (5.4)
Transactional outbox for `cartogra.topology.dependency.changed` (shared/web `OutboxWriter` + `OutboxRelay`). A row is written in the transaction of the edge change and hard-deleted after the broker accepts it; it is transport, not domain data. RLS like every tenant table; the relay reads in system scope.

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGSERIAL PK | Send order (not a UUID: the relay needs an order) |
| `tenant_id` | UUID NOT NULL | |
| `topic` | TEXT NOT NULL | |
| `record_key` | TEXT NOT NULL | Edge id |
| `payload` | TEXT NOT NULL | `EventEnvelope` JSON |
| `headers` | JSONB NOT NULL | `traceparent` of the write |
| `created_at` | TIMESTAMPTZ NOT NULL | |
| `attempts` | INT NOT NULL | Failed sends |
| `last_error` | TEXT | Last send failure |

---

## Contract Service (`services/contract`)

RLS policy `tenant_isolation` on all three tables (5.1).

### `api_contracts`
One live contract per service: unique `(tenant_id, service_id) WHERE deleted_at IS NULL`.

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `service_id` | UUID NOT NULL | ID ref to registry.services; not validated against Registry |
| `spec_type` | TEXT NOT NULL | `openapi` only (CHECK) |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | Bumped when a version is published |
| `deleted_at` | TIMESTAMPTZ | Set on `service.deleted` |

### `contract_versions`
Immutable. `UNIQUE (contract_id, version_number)`; numbered under `SELECT … FOR UPDATE` on the contract row.

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `contract_id` | UUID NOT NULL | FK `api_contracts` |
| `version_number` | INT NOT NULL | 1, 2, 3 per contract, server-assigned |
| `label` | TEXT | Spec `info.version`, display only |
| `spec_raw` | TEXT NOT NULL | As submitted (YAML or JSON) |
| `spec_canonical` | TEXT NOT NULL | Keys sorted recursively, arrays in order, compact JSON; `$ref` unresolved |
| `spec_hash` | CHAR(64) NOT NULL | SHA-256 hex of `spec_canonical`; same as latest = no new version |
| `is_breaking` | BOOLEAN | 5.3 (V004): breaking-change engine verdict against the previous latest version, set at publish; v1 = `false`; NULL = unchecked (stored before 5.3, no backfill) |
| `created_at` | TIMESTAMPTZ | |
| `deleted_at` | TIMESTAMPTZ | Set with the contract's |
| `source` | TEXT NOT NULL | `manual` (default) \| `discovery` (5.8) |
| `discovered_spec_id` | UUID | FK `discovered_specs`; set for a `discovery` version |

### `discovered_specs` _(5.8)_
One row each time the content of a discovered OpenAPI file changes at its repository and path. The row with `is_latest` is the last spec seen there (partial unique index on `(tenant_id, repository_full_name, file_path) WHERE is_latest`). A spec equal to it is skipped while its service is live; a different spec, also a revert, gets a new row. RLS `tenant_isolation`.

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `connection_id` | UUID | SCM connection of the sync, ID ref to ingestion |
| `repository_full_name` | TEXT NOT NULL | The SCM external id of the service |
| `file_path` | TEXT NOT NULL | The probe path that matched |
| `content_sha256` | CHAR(64) NOT NULL | SHA-256 of the file bytes, from ingestion |
| `spec_raw` | TEXT NOT NULL | The file content, kept so a PENDING spec can be applied later |
| `status` | TEXT NOT NULL | `PENDING` \| `APPLIED` \| `UNCHANGED` \| `REJECTED` \| `SKIPPED` \| `SUPERSEDED` (a newer spec came before this one was applied) |
| `is_latest` | BOOLEAN NOT NULL | True for the last spec seen at the repository and path |
| `service_id` | UUID | The resolved service |
| `contract_version_id` | UUID | FK `contract_versions`: the version stored, or the latest for `UNCHANGED` |
| `errors` | JSONB | Parser errors of a `REJECTED` spec |
| `created_at` / `updated_at` | TIMESTAMPTZ | |
| `deleted_at` | TIMESTAMPTZ | |

`services` (the Registry projection, 5.2) also has `external_id TEXT` (5.8): unique per tenant among live services with an external id.

### `deleted_services`
Tombstone of services Registry deleted; create/publish for one returns 404. PK `(tenant_id, service_id)`.

| Column | Type | Notes |
|--------|------|-------|
| `tenant_id` | UUID NOT NULL | |
| `service_id` | UUID NOT NULL | |
| `deleted_at` | TIMESTAMPTZ NOT NULL | When the event was applied |

### `inbound_edges` (5.4)
Topology edges projected from `cartogra.topology.dependency.changed`. PK `(tenant_id, edge_id)`; partial index `(tenant_id, target_service_id) WHERE removed_at IS NULL`. The source of a live `http`/`grpc` edge into a contract's service is a Consumer.

| Column | Type | Notes |
|--------|------|-------|
| `tenant_id` | UUID NOT NULL | |
| `edge_id` | UUID NOT NULL | Topology `dependencies.id` |
| `source_service_id` | UUID NOT NULL | The consumer |
| `target_service_id` | UUID NOT NULL | The producer |
| `dependency_type` | TEXT NOT NULL | `declared` \| `observed` |
| `protocol` | TEXT NOT NULL | `http` \| `grpc` \| `kafka` \| `db`, all stored |
| `updated_at` | TIMESTAMPTZ NOT NULL | |
| `removed_at` | TIMESTAMPTZ | Final: a later event never clears it |

### `contract_checks` _(planned, 5.5)_
Written by the CI check once the 5.3 engine defines `changes[]`.

---

## Ingestion Service (`services/ingestion`)

### `tenant_spec_paths` _(5.8)_
The custom OpenAPI probe paths of each tenant; a tenant with no row probes only the defaults. RLS `tenant_isolation`.

| Column | Type | Notes |
|--------|------|-------|
| `tenant_id` | UUID PK | |
| `paths` | TEXT[] NOT NULL | Custom paths in order, ≤ 10; probed before the 5 defaults |
| `created_at` / `updated_at` | TIMESTAMPTZ | |
| `deleted_at` | TIMESTAMPTZ | |

---

## Intelligence Service (`services/intelligence`)

### `nl_query_logs`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `user_id` | UUID | |
| `query_text` | TEXT NOT NULL | |
| `response_text` | TEXT | |
| `model` | TEXT | Claude model used |
| `latency_ms` | INTEGER | |
| `feedback` | TEXT | `positive` \| `negative` \| NULL |
| `created_at` | TIMESTAMPTZ | |

### `health_scores`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `service_id` | UUID NOT NULL | |
| `score` | NUMERIC(5,2) | 0–100 |
| `factors` | JSONB | Score breakdown |
| `computed_at` | TIMESTAMPTZ | |

---

## Indexes (key non-PK)

```sql
-- Multi-tenant lookup (applied to every domain table)
CREATE INDEX ON services (tenant_id) WHERE deleted_at IS NULL;

-- Graph traversal
CREATE INDEX ON dependencies (tenant_id, source_service_id) WHERE deleted_at IS NULL;
CREATE INDEX ON dependencies (tenant_id, target_service_id) WHERE deleted_at IS NULL;

-- JSONB metadata
CREATE INDEX ON services USING GIN (metadata) WHERE metadata IS NOT NULL;
CREATE INDEX ON dependencies USING GIN (metadata) WHERE metadata IS NOT NULL;
```

---

## References

- [system-overview.md](system-overview.md)
- [ADR-0001 — PostgreSQL over graph database](../adr/0001-postgresql-over-graph-database.md)
