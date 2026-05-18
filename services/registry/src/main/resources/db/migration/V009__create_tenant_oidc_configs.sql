CREATE TABLE tenant_oidc_configs (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id       UUID        NOT NULL UNIQUE,
    discovery_uri   TEXT        NOT NULL,
    client_id       TEXT        NOT NULL,
    client_secret   TEXT        NOT NULL,
    enabled         BOOLEAN     NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX ON tenant_oidc_configs (tenant_id) WHERE deleted_at IS NULL;

ALTER TABLE tenant_oidc_configs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_oidc_configs
    USING (tenant_id = current_setting('app.current_tenant_id', true)::UUID);
