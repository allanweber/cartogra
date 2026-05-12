CREATE TABLE services_history (
    id         UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    service_id UUID        NOT NULL,
    tenant_id  UUID        NOT NULL,
    snapshot   JSONB       NOT NULL,
    changed_by UUID,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ON services_history (service_id, changed_at DESC);
CREATE INDEX ON services_history (tenant_id, changed_at DESC);

ALTER TABLE services_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON services_history
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
