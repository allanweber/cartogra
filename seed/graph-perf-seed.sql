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
-- Also seeds 3 additional SPOF "hub" services (fan-in at/above the default SPOF
-- threshold) to manually verify SPOF detection and severity escalation on /graph
-- and /risks — see the "SPOF hub cases" section below for details.
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

-- SPOF hub cases -----------------------------------------------------------
-- Adds 3 synthetic "hub" services with fan-in at/above SpofService's
-- DEFAULT_THRESHOLD (5 direct dependents — services/topology/.../SpofService.java)
-- so /graph and /risks exercise all three branches of SpofService#severityOf:
--   - CRITICAL tier                     -> CRITICAL severity (tier escalation)
--   - STANDARD tier, owning team set    -> WARNING severity (no escalation)
--   - STANDARD tier, no owning team     -> CRITICAL severity (orphan escalation)
-- Dependents are drawn from the 20 services seeded above (by name order, idx
-- 0-17) so no extra dependent services are needed. Not part of the Phase 1
-- 20-service/30-edge gate above — this is an additional, independent scenario.
-- Idempotent the same way as the rest of this file (upsert by name / edge tuple).

CREATE TEMP TABLE seeded_spof_hubs AS
WITH seeded AS (
    INSERT INTO registry.services (tenant_id, name, description, health_status, source, tier, team_id)
    VALUES
        (current_setting('app.current_tenant_id')::uuid,
         'Perf Seed SPOF Hub - Critical Tier',
         'Synthetic SPOF: 7 dependents, CRITICAL tier -> CRITICAL severity (tier escalation).',
         'HEALTHY', 'seed', 'CRITICAL', NULL),
        (current_setting('app.current_tenant_id')::uuid,
         'Perf Seed SPOF Hub - Owned Standard Tier',
         'Synthetic SPOF: 5 dependents, STANDARD tier, owning team assigned -> WARNING severity.',
         'HEALTHY', 'seed', 'STANDARD', 'a0000000-0000-0000-0000-00000000000a'),
        (current_setting('app.current_tenant_id')::uuid,
         'Perf Seed SPOF Hub - Unowned Standard Tier',
         'Synthetic SPOF: 6 dependents, STANDARD tier, no owning team -> CRITICAL severity (orphan escalation).',
         'HEALTHY', 'seed', 'STANDARD', NULL)
    ON CONFLICT (tenant_id, lower(name)) WHERE deleted_at IS NULL
        DO UPDATE SET
            description = excluded.description,
            health_status = excluded.health_status,
            tier = excluded.tier,
            team_id = excluded.team_id,
            updated_at = now(),
            deleted_at = NULL
    RETURNING id, tenant_id, name, health_status, tier, team_id
)
SELECT * FROM seeded;

INSERT INTO topology.graph_nodes (tenant_id, service_id, name, health_status, tier, team_id)
SELECT tenant_id, id, name, health_status, tier, team_id
FROM seeded_spof_hubs
ON CONFLICT (tenant_id, service_id) DO UPDATE SET
    name = excluded.name,
    health_status = excluded.health_status,
    tier = excluded.tier,
    team_id = excluded.team_id,
    updated_at = now(),
    deleted_at = NULL;

-- Fan-in edges: idx 0-6 (7 services) -> Critical Tier hub (above threshold),
-- idx 7-11 (5 services) -> Owned Standard Tier hub (exactly at threshold),
-- idx 12-17 (6 services) -> Unowned Standard Tier hub (above threshold).
WITH ordered AS (
    SELECT id, row_number() OVER (ORDER BY name) - 1 AS idx
    FROM seeded_services
),
spof_edges AS (
    SELECT o.id AS source_id, h.id AS target_id
    FROM ordered o, seeded_spof_hubs h
    WHERE o.idx BETWEEN 0 AND 6 AND h.name = 'Perf Seed SPOF Hub - Critical Tier'
    UNION ALL
    SELECT o.id, h.id
    FROM ordered o, seeded_spof_hubs h
    WHERE o.idx BETWEEN 7 AND 11 AND h.name = 'Perf Seed SPOF Hub - Owned Standard Tier'
    UNION ALL
    SELECT o.id, h.id
    FROM ordered o, seeded_spof_hubs h
    WHERE o.idx BETWEEN 12 AND 17 AND h.name = 'Perf Seed SPOF Hub - Unowned Standard Tier'
)
INSERT INTO topology.dependencies (tenant_id, source_service_id, target_service_id, dependency_type, protocol)
SELECT current_setting('app.current_tenant_id')::uuid, source_id, target_id, 'declared', 'http'
FROM spof_edges
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
    (SELECT count(*) FROM seeded_spof_hubs) AS spof_hubs_seeded,
    (SELECT count(*) FROM topology.dependencies
     WHERE tenant_id = current_setting('app.current_tenant_id')::uuid AND deleted_at IS NULL) AS edges_for_tenant,
    (SELECT count(*) FROM topology.dependency_graph_edges dge
     JOIN seeded_spof_hubs h ON h.id = dge.target_service_id
     WHERE dge.tenant_id = current_setting('app.current_tenant_id')::uuid
       AND h.name = 'Perf Seed SPOF Hub - Critical Tier') AS critical_hub_fan_in,
    (SELECT count(*) FROM topology.dependency_graph_edges dge
     JOIN seeded_spof_hubs h ON h.id = dge.target_service_id
     WHERE dge.tenant_id = current_setting('app.current_tenant_id')::uuid
       AND h.name = 'Perf Seed SPOF Hub - Owned Standard Tier') AS owned_hub_fan_in,
    (SELECT count(*) FROM topology.dependency_graph_edges dge
     JOIN seeded_spof_hubs h ON h.id = dge.target_service_id
     WHERE dge.tenant_id = current_setting('app.current_tenant_id')::uuid
       AND h.name = 'Perf Seed SPOF Hub - Unowned Standard Tier') AS unowned_hub_fan_in;
