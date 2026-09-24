DROP MATERIALIZED VIEW dependency_graph_edges;

CREATE MATERIALIZED VIEW dependency_graph_edges AS
SELECT tenant_id, source_service_id, target_service_id, dependency_type, protocol, metadata
FROM dependencies
WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX ON dependency_graph_edges (tenant_id, source_service_id, target_service_id, dependency_type, protocol);
CREATE INDEX ON dependency_graph_edges (tenant_id, source_service_id);
CREATE INDEX ON dependency_graph_edges (tenant_id, target_service_id);
