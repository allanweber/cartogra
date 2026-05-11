CREATE TABLE services (
    id               UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    name             TEXT        NOT NULL,
    description      TEXT,
    team_id          UUID,
    repository_url   TEXT,
    tech_stack       JSONB,
    metadata         JSONB,
    health_status    TEXT        NOT NULL DEFAULT 'unknown',
    last_deployed_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ
);

CREATE UNIQUE INDEX ON services (tenant_id, lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX ON services (tenant_id, team_id) WHERE deleted_at IS NULL;
CREATE INDEX ON services USING GIN (tech_stack) WHERE tech_stack IS NOT NULL;
CREATE INDEX ON services USING GIN (metadata) WHERE metadata IS NOT NULL;
CREATE INDEX ON services USING GIN (to_tsvector('english', coalesce(name, '') || ' ' || coalesce(description, '')));

ALTER TABLE services ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON services
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
