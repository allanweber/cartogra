ALTER TABLE dependency_drifts ADD COLUMN resolution VARCHAR(20);

UPDATE dependency_drifts SET resolution = 'auto' WHERE resolved_at IS NOT NULL;

ALTER TABLE dependency_drifts ADD CONSTRAINT dependency_drifts_resolution_check
    CHECK (resolution IN ('accepted', 'removed', 'dismissed', 'auto'));

ALTER TABLE dependency_drifts ADD CONSTRAINT dependency_drifts_resolution_resolved_check
    CHECK ((resolved_at IS NULL) = (resolution IS NULL));

-- Dismissed drift suppresses re-detection of the same edge + type.
CREATE INDEX ON dependency_drifts (tenant_id, source_service_id, target_service_id, drift_type)
    WHERE resolution = 'dismissed';
