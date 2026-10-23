ALTER TABLE unresolved_span_services ADD COLUMN resolution VARCHAR(20);
ALTER TABLE unresolved_span_services ADD COLUMN resolved_by UUID;

UPDATE unresolved_span_services SET resolution = 'dismissed' WHERE deleted_at IS NOT NULL;

ALTER TABLE unresolved_span_services ADD CONSTRAINT unresolved_span_services_resolution_check
    CHECK (resolution IN ('dismissed', 'registered'));

ALTER TABLE unresolved_span_services ADD CONSTRAINT unresolved_span_services_resolution_deleted_check
    CHECK ((deleted_at IS NULL) = (resolution IS NULL));

-- A dismissed name is never re-recorded from spans.
CREATE INDEX ON unresolved_span_services (tenant_id, lower(name)) WHERE resolution = 'dismissed';

CREATE INDEX ON unresolved_span_services (tenant_id, seen_count DESC, last_seen_at DESC) WHERE deleted_at IS NULL;
