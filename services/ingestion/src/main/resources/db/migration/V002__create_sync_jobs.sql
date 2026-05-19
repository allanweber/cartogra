CREATE TABLE sync_jobs (
    id             UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    connection_id  UUID        NOT NULL,
    provider_type  TEXT        NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'PENDING'
                               CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    started_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    error_message  TEXT,
    repositories_synced INT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ
);

CREATE INDEX ON sync_jobs (tenant_id)       WHERE deleted_at IS NULL;
CREATE INDEX ON sync_jobs (connection_id)   WHERE deleted_at IS NULL;
CREATE INDEX ON sync_jobs (status)          WHERE deleted_at IS NULL;
CREATE INDEX ON sync_jobs (tenant_id, status) WHERE deleted_at IS NULL;

ALTER TABLE sync_jobs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sync_jobs
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
