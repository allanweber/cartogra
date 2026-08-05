CREATE TABLE dependency_drifts (
    id                UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id         UUID          NOT NULL,
    source_service_id UUID          NOT NULL,
    target_service_id UUID          NOT NULL,
    drift_type        VARCHAR(20)   NOT NULL, -- undeclared | missing
    detected_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ,
    resolved_by       UUID,

    CONSTRAINT dependency_drifts_type_check CHECK (drift_type IN ('undeclared', 'missing'))
);

-- One active (unresolved) drift record per edge + drift type — resolving and re-detecting
-- creates a fresh row rather than reopening a historical one.
CREATE UNIQUE INDEX ON dependency_drifts (tenant_id, source_service_id, target_service_id, drift_type)
    WHERE resolved_at IS NULL;

CREATE INDEX ON dependency_drifts (tenant_id) WHERE resolved_at IS NULL;
CREATE INDEX ON dependency_drifts (tenant_id, source_service_id) WHERE resolved_at IS NULL;
CREATE INDEX ON dependency_drifts (tenant_id, target_service_id) WHERE resolved_at IS NULL;

ALTER TABLE dependency_drifts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON dependency_drifts
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
