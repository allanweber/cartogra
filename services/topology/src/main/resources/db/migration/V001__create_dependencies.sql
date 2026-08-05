CREATE TABLE dependencies (
    id                UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id         UUID          NOT NULL,
    source_service_id UUID          NOT NULL,
    target_service_id UUID          NOT NULL,
    dependency_type   VARCHAR(20)   NOT NULL, -- declared | observed
    protocol          VARCHAR(10)   NOT NULL, -- http | grpc | kafka | db
    metadata          JSONB,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,

    CONSTRAINT dependencies_no_self_edge CHECK (source_service_id != target_service_id),
    CONSTRAINT dependencies_type_check CHECK (dependency_type IN ('declared', 'observed')),
    CONSTRAINT dependencies_protocol_check CHECK (protocol IN ('http', 'grpc', 'kafka', 'db'))
);

-- Edge identity used by observed-edge upserts (JdbcDependencyRepository#findByEdgeIdentity):
-- same tenant/source/target/type/protocol is one edge, never duplicated while active.
CREATE UNIQUE INDEX ON dependencies (tenant_id, source_service_id, target_service_id, dependency_type, protocol)
    WHERE deleted_at IS NULL;

CREATE INDEX ON dependencies (tenant_id, source_service_id) WHERE deleted_at IS NULL;
CREATE INDEX ON dependencies (tenant_id, target_service_id) WHERE deleted_at IS NULL;
CREATE INDEX ON dependencies USING GIN (metadata) WHERE metadata IS NOT NULL;

ALTER TABLE dependencies ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON dependencies
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
