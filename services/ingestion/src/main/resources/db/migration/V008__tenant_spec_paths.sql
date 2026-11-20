-- The custom OpenAPI probe paths of each tenant (story 5.8). Ingestion adds these paths to the built-in
-- defaults: it probes the custom paths first, then the defaults. A tenant without a row uses only the defaults.
CREATE TABLE tenant_spec_paths (
    tenant_id  UUID        NOT NULL PRIMARY KEY,
    paths      TEXT[]      NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

ALTER TABLE tenant_spec_paths ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_spec_paths FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_spec_paths USING (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
    OR current_setting('app.rls_bypass', true) = 'on');

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_spec_paths TO cartogra_app;
