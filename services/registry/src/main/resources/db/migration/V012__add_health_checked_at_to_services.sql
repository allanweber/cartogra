ALTER TABLE services
    ADD COLUMN health_checked_at TIMESTAMPTZ;

-- Accelerates the scheduler's cross-tenant query for probeable (non-K8s) services
CREATE INDEX ON services (tenant_id)
    WHERE health_endpoint IS NOT NULL
      AND source IS DISTINCT FROM 'kubernetes'
      AND deleted_at IS NULL;
