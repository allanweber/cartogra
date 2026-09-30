-- Per-tenant topology configuration. A tenant with no row here uses the application default
-- (SpofService.DEFAULT_THRESHOLD) — read-only in this pass, no write endpoint exists yet.
CREATE TABLE tenant_topology_settings (
    tenant_id             UUID        NOT NULL PRIMARY KEY,
    spof_fan_in_threshold INT         NOT NULL DEFAULT 5,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE tenant_topology_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_topology_settings
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
