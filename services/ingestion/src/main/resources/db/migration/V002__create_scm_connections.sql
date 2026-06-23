CREATE TABLE scm_connections (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id             UUID         NOT NULL,
    provider              VARCHAR(50)  NOT NULL,
    config                JSONB        NOT NULL DEFAULT '{}',
    sync_scheduler        BOOLEAN      NOT NULL DEFAULT false,
    poll_interval_minutes INT          NOT NULL DEFAULT 15,
    next_sync_at          TIMESTAMPTZ,
    last_sync_at          TIMESTAMPTZ,
    last_sync_status      TEXT,
    webhook_enabled       BOOLEAN      NOT NULL DEFAULT false,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ
);

CREATE INDEX ON scm_connections (tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX ON scm_connections USING GIN (config);
CREATE INDEX ON scm_connections (next_sync_at)
    WHERE sync_scheduler = true AND deleted_at IS NULL;

ALTER TABLE scm_connections ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON scm_connections
    USING (tenant_id = current_setting('app.current_tenant_id', true)::UUID);
