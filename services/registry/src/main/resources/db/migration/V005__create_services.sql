CREATE TABLE services (
    id               UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id        UUID          NOT NULL,
    name             VARCHAR(255)  NOT NULL,
    description      VARCHAR(2000),
    team_id          UUID,
    repository_url   VARCHAR(2048),
    tech_stack       TEXT[],
    metadata         JSONB,
    health_status    VARCHAR(50)   NOT NULL DEFAULT 'unknown',
    last_deployed_at TIMESTAMPTZ,
    external_id      VARCHAR(255),
    connection_id    UUID,
    source           VARCHAR(50),
    repository_ref   VARCHAR(255),
    k8s_cluster      VARCHAR(253),
    k8s_namespace    VARCHAR(63),
    k8s_deployment   VARCHAR(253),
    health_endpoint  VARCHAR(2048),
    last_commit_at   TIMESTAMPTZ,
    last_commit_sha  VARCHAR(64),
    health_checked_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ
);

CREATE UNIQUE INDEX ON services (tenant_id, lower(name)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ON services (tenant_id, external_id)
    WHERE external_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX ON services (tenant_id, team_id) WHERE deleted_at IS NULL;
CREATE INDEX ON services USING GIN (tech_stack) WHERE tech_stack IS NOT NULL;
CREATE INDEX ON services USING GIN (metadata) WHERE metadata IS NOT NULL;
CREATE INDEX ON services USING GIN (to_tsvector('english', coalesce(name, '') || ' ' || coalesce(description, '')));
CREATE INDEX ON services (tenant_id)
    WHERE health_endpoint IS NOT NULL
      AND source IS DISTINCT FROM 'kubernetes'
      AND deleted_at IS NULL;

ALTER TABLE services ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON services
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
