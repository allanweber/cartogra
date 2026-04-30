# Cartogra — Data Model

Each service owns its schema entirely. No cross-service joins. Cross-aggregate references store IDs only.

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

---

## Contract Service (`services/contract`)

### `api_contracts`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `service_id` | UUID NOT NULL | ID ref to registry.services |
| `spec_type` | TEXT | `openapi` \| `asyncapi` |
| `created_at` | TIMESTAMPTZ | |
| `deleted_at` | TIMESTAMPTZ | |

### `contract_versions`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `contract_id` | UUID NOT NULL | |
| `tenant_id` | UUID NOT NULL | |
| `version` | TEXT NOT NULL | Semver or SHA |
| `spec_content` | TEXT NOT NULL | Raw YAML/JSON |
| `spec_hash` | TEXT NOT NULL | SHA-256 for dedup |
| `created_at` | TIMESTAMPTZ | |

### `contract_consumers`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `contract_id` | UUID NOT NULL | |
| `consumer_service_id` | UUID NOT NULL | |
| `created_at` | TIMESTAMPTZ | |
| `deleted_at` | TIMESTAMPTZ | |

### `contract_checks`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `contract_id` | UUID NOT NULL | |
| `baseline_version_id` | UUID | |
| `candidate_version_id` | UUID | |
| `result` | TEXT | `compatible` \| `breaking` \| `warning` |
| `findings` | JSONB | Array of breaking change descriptions |
| `checked_at` | TIMESTAMPTZ | |

### `outbox_events`
| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `event_type` | TEXT NOT NULL | |
| `payload` | JSONB NOT NULL | |
| `published_at` | TIMESTAMPTZ | NULL = unpublished |
| `created_at` | TIMESTAMPTZ | |

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
- [ADR-0001 — PostgreSQL over graph database](../adr/ADR-0001-postgresql-over-graph-database.md)
