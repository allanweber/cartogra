-- Topology edges projected from cartogra.topology.dependency.changed, keyed by topology's edge id. The source of a
-- live edge into a contract's service is a Consumer. removed_at is final: topology never reuses an edge id, so a
-- replayed or late event cannot bring a removed edge back.
CREATE TABLE inbound_edges (
    tenant_id          UUID        NOT NULL,
    edge_id            UUID        NOT NULL,
    source_service_id  UUID        NOT NULL,
    target_service_id  UUID        NOT NULL,
    dependency_type    TEXT        NOT NULL,
    protocol           TEXT        NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at         TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, edge_id)
);

CREATE INDEX ON inbound_edges (tenant_id, target_service_id) WHERE removed_at IS NULL;

ALTER TABLE inbound_edges ENABLE ROW LEVEL SECURITY;
ALTER TABLE inbound_edges FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inbound_edges USING (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
    OR current_setting('app.rls_bypass', true) = 'on');
