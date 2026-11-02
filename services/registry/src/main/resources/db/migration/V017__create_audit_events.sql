-- Append-only audit trail for every service (ADR-0017). Kept forever; GDPR erasure anonymises rows instead.
CREATE TABLE audit_events (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    event_id    UUID        NOT NULL,
    tenant_id   UUID        NOT NULL,
    entity_type TEXT        NOT NULL,
    entity_id   UUID        NOT NULL,
    action      TEXT        NOT NULL,
    actor_kind  TEXT        NOT NULL,
    actor_id    UUID,
    payload     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_audit_events_event_id UNIQUE (event_id),
    CONSTRAINT ck_audit_events_actor CHECK (
        actor_kind IN ('user', 'system') AND (actor_kind = 'user' OR actor_id IS NULL))
);

CREATE INDEX ON audit_events (tenant_id, occurred_at DESC);
CREATE INDEX ON audit_events (tenant_id, entity_type, entity_id, occurred_at DESC);
CREATE INDEX ON audit_events USING GIN (payload);

ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_events
    USING (tenant_id = current_setting('app.current_tenant_id', true)::UUID);
