-- Notification Rules and their Delivery Attempts. Webhooks and email addresses stay inside the backend; the
-- boundary masks them in every response, log row, and audit event.
CREATE TABLE notification_rules (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id           UUID        NOT NULL,
    service_id          UUID,
    enabled             BOOLEAN     NOT NULL DEFAULT true,
    slack_webhook_url   TEXT,
    teams_webhook_url   TEXT,
    email_addresses     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ
);

CREATE UNIQUE INDEX notification_rules_one_per_scope ON notification_rules (tenant_id, service_id)
    NULLS NOT DISTINCT WHERE deleted_at IS NULL;

ALTER TABLE notification_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_rules USING (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
    OR current_setting('app.rls_bypass', true) = 'on');

CREATE TABLE notification_log (
    id               UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    rule_id          UUID        NOT NULL REFERENCES notification_rules (id),
    check_id         UUID        REFERENCES contract_checks (id),
    attempt          INTEGER     NOT NULL CHECK (attempt BETWEEN 1 AND 3),
    channel          TEXT        NOT NULL CHECK (channel IN ('slack', 'teams', 'email')),
    destination_hint TEXT,
    wait_ms          INTEGER     NOT NULL DEFAULT 0,
    status_code      INTEGER,
    error_code       TEXT,
    outcome          TEXT        NOT NULL CHECK (outcome IN ('pending', 'sent', 'failed')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ
);

CREATE INDEX notification_log_rule_created ON notification_log (tenant_id, rule_id, created_at DESC);

ALTER TABLE notification_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_log USING (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
    OR current_setting('app.rls_bypass', true) = 'on');
