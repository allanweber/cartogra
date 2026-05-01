CREATE TABLE scm_connections (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    provider    TEXT        NOT NULL,
    config      JSONB       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

CREATE INDEX ON scm_connections (tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX ON scm_connections USING GIN (config);

ALTER TABLE scm_connections ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON scm_connections
    USING (tenant_id = current_setting('app.current_tenant_id', true)::UUID);
