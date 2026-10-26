-- Messaging endpoints share the span-link buffer: one row per (tenant, destination, kind, service),
-- refreshed by upsert and never deleted on match; the reaper removes rows idle past expires_at.
ALTER TABLE pending_span_links ALTER COLUMN trace_id DROP NOT NULL;
ALTER TABLE pending_span_links ALTER COLUMN span_id DROP NOT NULL;
ALTER TABLE pending_span_links ADD COLUMN destination TEXT;

ALTER TABLE pending_span_links DROP CONSTRAINT pending_span_links_kind_check;
ALTER TABLE pending_span_links ADD CONSTRAINT pending_span_links_kind_check
    CHECK (kind IN ('client', 'server', 'producer', 'consumer'));
ALTER TABLE pending_span_links ADD CONSTRAINT pending_span_links_shape_check
    CHECK ((kind IN ('client', 'server') AND trace_id IS NOT NULL AND span_id IS NOT NULL)
        OR (kind IN ('producer', 'consumer') AND destination IS NOT NULL));

CREATE UNIQUE INDEX pending_span_links_messaging_endpoint
    ON pending_span_links (tenant_id, destination, kind, lower(service_name))
    WHERE destination IS NOT NULL;
