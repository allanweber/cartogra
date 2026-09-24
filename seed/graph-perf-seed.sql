-- seed/graph-perf-seed.sql
--
-- Seeds a 20-service, 30-edge dependency graph for one tenant, to manually verify
-- the Phase 1 gate criterion (docs/roadmap.md §4, item 1.4):
--   "/graph renders a 20-service, 30-edge tenant without freezing, on hardware
--   named in the doc."
--
-- Writes directly to registry.services and topology.graph_nodes/dependencies,
-- bypassing the registry API and the Kafka graph_nodes sync (GraphNodeEventConsumer)
-- so the seed is fast, deterministic, and doesn't require the event pipeline running.
--
-- Idempotent: rerunning upserts the same 20 services (matched by tenant + name) and
-- the same 30 edges (matched by tenant + source + target + type + protocol) rather
-- than duplicating them.
--
-- HOW TO RUN
-- Plain SQL — paste this whole file into any DBMS client (DBeaver, pgAdmin,
-- TablePlus, DataGrip, ...) connected to the "cartogra" database, edit the UUID on
-- the very next line, and run the whole script as one batch/session:
--
--   No FK to registry.tenants is enforced at the DB layer, so any UUID works —
--   pass an existing tenant's id if you want to view the graph via a real
--   logged-in session at /graph.
--
-- If your client errors on "REFRESH MATERIALIZED VIEW CONCURRENTLY ... cannot run
-- inside a transaction block" (some GUIs wrap script runs in one transaction),
-- run everything above that statement first, then run it by itself afterward.

SET app.current_tenant_id = '00000000-0000-0000-0000-000000000000'; -- <-- replace with your tenant id

-- 20 services ------------------------------------------------------------

CREATE TEMP TABLE seeded_services AS
WITH seeded AS (
    INSERT INTO registry.services (tenant_id, name, description, health_status, source, tier)
    SELECT
        current_setting('app.current_tenant_id')::uuid,
        'Perf Seed Service ' || lpad(n::text, 2, '0'),
        'Synthetic service for the 20-service/30-edge graph-render gate (docs/roadmap.md §4).',
        (ARRAY['HEALTHY', 'HEALTHY', 'HEALTHY', 'DEGRADED', 'UNHEALTHY'])[1 + (n % 5)],
        'seed',
        (ARRAY['CRITICAL', 'STANDARD', 'STANDARD', 'EXPERIMENTAL'])[1 + (n % 4)]
    FROM generate_series(1, 20) AS n
    ON CONFLICT (tenant_id, lower(name)) WHERE deleted_at IS NULL
        DO UPDATE SET
            description = excluded.description,
            health_status = excluded.health_status,
            tier = excluded.tier,
            updated_at = now(),
            deleted_at = NULL
    RETURNING id, tenant_id, name, health_status, tier
)
SELECT * FROM seeded;

-- Mirror into topology's local projection (normally kept in sync by
-- GraphNodeEventConsumer off cartogra.registry.service.* events) --------

INSERT INTO topology.graph_nodes (tenant_id, service_id, name, health_status, tier)
SELECT tenant_id, id, name, health_status, tier
FROM seeded_services
ON CONFLICT (tenant_id, service_id) DO UPDATE SET
    name = excluded.name,
    health_status = excluded.health_status,
    tier = excluded.tier,
    updated_at = now(),
    deleted_at = NULL;

-- 30 declared edges over the 20 services ----------------------------------
-- Two disjoint permutations of the 20 nodes (offsets 1 and 7, both coprime
-- with 20) give 20 + 10 = 30 distinct directed edges: no self-edges, no
-- duplicate (source, target) pairs.

WITH ordered AS (
    SELECT id, row_number() OVER (ORDER BY name) - 1 AS idx
    FROM seeded_services
),
edges AS (
    SELECT a.id AS source_id, b.id AS target_id,
           (ARRAY['http', 'grpc', 'kafka', 'db'])[1 + ((a.idx + b.idx) % 4)] AS protocol
    FROM ordered a
    JOIN ordered b ON b.idx = (a.idx + 1) % 20
    UNION ALL
    SELECT a.id, b.id,
           (ARRAY['http', 'grpc', 'kafka', 'db'])[1 + ((a.idx + b.idx) % 4)]
    FROM ordered a
    JOIN ordered b ON b.idx = (a.idx + 7) % 20
    WHERE a.idx < 10
)
INSERT INTO topology.dependencies (tenant_id, source_service_id, target_service_id, dependency_type, protocol)
SELECT current_setting('app.current_tenant_id')::uuid, source_id, target_id, 'declared', protocol
FROM edges
ON CONFLICT (tenant_id, source_service_id, target_service_id, dependency_type, protocol)
    WHERE deleted_at IS NULL
    DO NOTHING;

-- The materialized view is normally refreshed by
-- DependencyGraphViewRefreshScheduler's dirty-flag tick; refresh it inline here so
-- GET /graph reflects the seed immediately instead of waiting on the next tick.
REFRESH MATERIALIZED VIEW CONCURRENTLY topology.dependency_graph_edges;

-- Sanity check
SELECT
    (SELECT count(*) FROM seeded_services) AS services_seeded,
    (SELECT count(*) FROM topology.dependencies
     WHERE tenant_id = current_setting('app.current_tenant_id')::uuid AND deleted_at IS NULL) AS edges_for_tenant;
