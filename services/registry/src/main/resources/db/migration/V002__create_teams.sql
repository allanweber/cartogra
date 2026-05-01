CREATE TABLE teams (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    name        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT uq_team_tenant_name UNIQUE (tenant_id, name)
);

CREATE INDEX ON teams (tenant_id) WHERE deleted_at IS NULL;

ALTER TABLE teams ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON teams
    USING (tenant_id = current_setting('app.current_tenant_id', true)::UUID);
