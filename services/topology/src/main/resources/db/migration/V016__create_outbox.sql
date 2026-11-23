-- Transactional outbox (shared/web OutboxWriter + OutboxRelay). A row is written in the same transaction as the
-- change that caused it and deleted after the broker accepts it. id is the send order, so it is a sequence, not a
-- UUID. Rows are transport, not domain data, so the relay hard-deletes them.
CREATE TABLE outbox (
    id          BIGSERIAL   PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    topic       TEXT        NOT NULL,
    record_key  TEXT        NOT NULL,
    payload     TEXT        NOT NULL,
    headers     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts    INT         NOT NULL DEFAULT 0,
    last_error  TEXT
);

ALTER TABLE outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON outbox USING (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
    OR current_setting('app.rls_bypass', true) = 'on');
