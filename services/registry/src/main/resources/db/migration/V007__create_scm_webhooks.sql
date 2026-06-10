CREATE TABLE scm_webhooks (
    id                UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id         UUID          NOT NULL,
    scm_connection_id UUID          NOT NULL,
    provider          VARCHAR(50)   NOT NULL,
    external_id       VARCHAR(255),
    target_url        VARCHAR(2048) NOT NULL,
    secret_hash       VARCHAR(64),
    events            VARCHAR(100)[] NOT NULL DEFAULT '{}',
    status            VARCHAR(50)   NOT NULL DEFAULT 'active',
    last_received_at  TIMESTAMPTZ,
    error_message     VARCHAR(2000),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ
);

CREATE INDEX ON scm_webhooks (tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX ON scm_webhooks (scm_connection_id) WHERE deleted_at IS NULL;

ALTER TABLE scm_webhooks ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON scm_webhooks
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
