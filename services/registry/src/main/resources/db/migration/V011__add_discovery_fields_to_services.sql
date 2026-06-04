ALTER TABLE services
    ADD COLUMN external_id      TEXT,
    ADD COLUMN connection_id    UUID,
    ADD COLUMN source           TEXT,
    ADD COLUMN repository_ref   TEXT,
    ADD COLUMN k8s_cluster      TEXT,
    ADD COLUMN k8s_namespace    TEXT,
    ADD COLUMN k8s_deployment   TEXT,
    ADD COLUMN health_endpoint  TEXT,
    ADD COLUMN last_commit_at   TIMESTAMPTZ,
    ADD COLUMN last_commit_sha  TEXT;

CREATE UNIQUE INDEX ON services (tenant_id, external_id)
    WHERE external_id IS NOT NULL AND deleted_at IS NULL;
