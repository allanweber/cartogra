ALTER TABLE dependencies ADD COLUMN last_seen_at TIMESTAMPTZ;

CREATE INDEX ON graph_nodes (tenant_id, lower(name)) WHERE deleted_at IS NULL;

-- Transient join buffer for span halves awaiting their partner (CLIENT <-> SERVER by span id).
-- Hard deletes are intentional: rows are not domain data and live at most a few minutes.
CREATE TABLE pending_span_links (
    id             UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    trace_id       TEXT        NOT NULL,
    span_id        TEXT        NOT NULL,
    parent_span_id TEXT,
    kind           TEXT        NOT NULL CHECK (kind IN ('client', 'server')),
    service_name   TEXT        NOT NULL,
    protocol       TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX ON pending_span_links (tenant_id, trace_id, span_id);
CREATE INDEX ON pending_span_links (tenant_id, trace_id, parent_span_id);
CREATE INDEX ON pending_span_links (expires_at);

ALTER TABLE pending_span_links ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pending_span_links
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);

CREATE TABLE unresolved_span_services (
    id            UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    name          TEXT        NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL,
    seen_count    BIGINT      NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);

CREATE UNIQUE INDEX ON unresolved_span_services (tenant_id, lower(name)) WHERE deleted_at IS NULL;

ALTER TABLE unresolved_span_services ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON unresolved_span_services
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
