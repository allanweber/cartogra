-- Registry services projected from cartogra.registry.service.changed (names for the contract list). A row with deleted_at set is a tombstone: no contract can be created
-- or versioned for that service. Replaces deleted_services.
CREATE TABLE services (
    tenant_id   UUID        NOT NULL,
    service_id  UUID        NOT NULL,
    name        TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, service_id)
);

ALTER TABLE services ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON services
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);

INSERT INTO services (tenant_id, service_id, updated_at, deleted_at)
SELECT tenant_id, service_id, deleted_at, deleted_at FROM deleted_services;

DROP TABLE deleted_services;

CREATE INDEX ON api_contracts (tenant_id, updated_at DESC) WHERE deleted_at IS NULL;
