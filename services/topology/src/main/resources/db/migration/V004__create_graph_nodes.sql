-- Local projection of Registry services, kept in sync via
-- cartogra.registry.service.{registered,updated,deleted} (GraphNodeEventConsumer) so
-- graph queries never call Registry synchronously. One row per (tenant_id, service_id);
-- service.deleted soft-deletes the row rather than removing it.
CREATE TABLE graph_nodes (
    id            UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    service_id    UUID        NOT NULL,
    name          TEXT        NOT NULL,
    team_id       UUID,
    tier          TEXT,
    health_status TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);

CREATE UNIQUE INDEX ON graph_nodes (tenant_id, service_id);
CREATE INDEX ON graph_nodes (tenant_id) WHERE deleted_at IS NULL;

ALTER TABLE graph_nodes ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON graph_nodes
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
