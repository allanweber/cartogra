-- perf/graph-perf-200-teardown.sql
--
-- Removes everything perf/graph-perf-200-seed.sql created for one tenant. The fixture is
-- synthetic (names prefixed "Perf200 Seed"), so these are hard deletes on purpose — soft
-- deletes would leave 133 fake services in the dev database forever.
--
--   docker exec -i cartogra-postgres psql -U cartogra -d cartogra \
--       -v tenant_id=<uuid> < perf/graph-perf-200-teardown.sql

\if :{?tenant_id}
\else
    \echo 'error: pass -v tenant_id=<uuid> (the tenant the seed was run for)'
    \quit
\endif

SET app.current_tenant_id = :'tenant_id';

DELETE FROM topology.dependencies d
USING registry.services s
WHERE d.tenant_id = :'tenant_id'::uuid
  AND s.tenant_id = d.tenant_id
  AND s.name LIKE 'Perf200 Seed%'
  AND (d.source_service_id = s.id OR d.target_service_id = s.id);

DELETE FROM topology.graph_nodes
WHERE tenant_id = :'tenant_id'::uuid AND name LIKE 'Perf200 Seed%';

DELETE FROM registry.services
WHERE tenant_id = :'tenant_id'::uuid AND name LIKE 'Perf200 Seed%';

REFRESH MATERIALIZED VIEW CONCURRENTLY topology.dependency_graph_edges;

SELECT
    (SELECT count(*) FROM registry.services WHERE tenant_id = :'tenant_id'::uuid) AS services_left,
    (SELECT count(*) FROM topology.dependencies WHERE tenant_id = :'tenant_id'::uuid) AS edges_left;
