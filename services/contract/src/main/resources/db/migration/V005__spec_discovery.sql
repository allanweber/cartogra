-- Spec discovery (story 5.8). Ingestion sends spec.discovered for each OpenAPI file that a sync finds. Contract
-- keeps a row each time the content at (repository, path) changes. The row with is_latest is the last spec seen
-- there. A spec equal to that row is skipped. A different spec, also a revert to an older content, gets a new row.
-- Contract resolves the repository to a Registry service and applies the spec as a contract version. A spec that
-- comes before its service stays PENDING until service.registered names the repo. A newer spec at the same path
-- moves an older PENDING row to SUPERSEDED, so a stale spec is never applied over a newer one.
CREATE TABLE discovered_specs (
    id                   UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id            UUID        NOT NULL,
    connection_id        UUID,
    repository_full_name TEXT        NOT NULL,
    file_path            TEXT        NOT NULL,
    content_sha256       CHAR(64)    NOT NULL,
    spec_raw             TEXT        NOT NULL,
    status               TEXT        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPLIED', 'UNCHANGED', 'REJECTED', 'SKIPPED', 'SUPERSEDED')),
    is_latest            BOOLEAN     NOT NULL DEFAULT true,
    service_id           UUID,
    contract_version_id  UUID        REFERENCES contract_versions (id),
    errors               JSONB,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at           TIMESTAMPTZ
);

CREATE UNIQUE INDEX discovered_specs_latest_uq ON discovered_specs (tenant_id, repository_full_name, file_path)
    WHERE is_latest;

CREATE INDEX ON discovered_specs (tenant_id, repository_full_name, created_at) WHERE status = 'PENDING';

ALTER TABLE discovered_specs ENABLE ROW LEVEL SECURITY;
ALTER TABLE discovered_specs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON discovered_specs USING (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
    OR current_setting('app.rls_bypass', true) = 'on');

GRANT SELECT, INSERT, UPDATE, DELETE ON discovered_specs TO cartogra_app;

-- The SCM external id (repository full path) of each projected service, from service.registered/service.updated.
ALTER TABLE services ADD COLUMN external_id TEXT;

CREATE UNIQUE INDEX services_live_external_id_uq ON services (tenant_id, external_id)
    WHERE external_id IS NOT NULL AND deleted_at IS NULL;

-- Where each version came from: a manual publish, or a spec that discovery applied.
ALTER TABLE contract_versions
    ADD COLUMN source TEXT NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'discovery')),
    ADD COLUMN discovered_spec_id UUID REFERENCES discovered_specs (id);
