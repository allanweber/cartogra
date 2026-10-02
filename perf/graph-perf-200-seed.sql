-- perf/graph-perf-200-seed.sql
--
-- Seeds a 130-service, 200-edge dependency graph for one tenant, to gather the
-- Phase 2 performance evidence required by docs/roadmap.md §5, item 2.6 /
-- GitHub issue #118:
--   "200-edge fixture; record graph read, blast radius p95, and MV refresh
--   duration in docs/architecture/topology-performance.md with the hardware
--   named."
--
-- Same recipe as seed/graph-perf-seed.sql (Phase 1, 20 services / 30 edges),
-- scaled up:
--   - 130 services, full permutation at offset 1 (coprime with 130) -> 130
--     backbone edges (one complete cycle, no self-edges, no duplicates).
--   - Second permutation at offset 7 (coprime with 130), applied to the first
--     52 services only -> 52 more backbone edges. 1 and 7 never coincide mod
--     130, so the two permutations can't produce the same (source, target)
--     pair. 130 + 52 = 182 backbone edges.
--   - Same 3 synthetic SPOF "hub" services as the Phase 1 seed (fan-in at/
--     above SpofService's DEFAULT_THRESHOLD), drawing dependents from the
--     same idx 0-17 slice of the service pool -> 7 + 5 + 6 = 18 edges.
--   - 182 + 18 = 200 edges total.
--
-- Writes directly to registry.services and topology.graph_nodes/dependencies,
-- bypassing the registry API and the Kafka graph_nodes sync
-- (GraphNodeEventConsumer), so the seed is fast, deterministic, and doesn't
-- require the event pipeline running.
--
-- Idempotent: rerunning upserts the same 130 services (matched by tenant +
-- name) and the same edges (matched by tenant + source + target + type +
-- protocol) rather than duplicating them.
--
-- HOW TO RUN
-- psql only (the tenant id is a psql variable, and the REFRESH ... CONCURRENTLY at
-- the end can't run inside a transaction block). Use a throwaway tenant UUID, never a
-- real one — the seed writes straight into the shared dev database:
--
--   docker exec -i cartogra-postgres psql -U cartogra -d cartogra \
--       -v tenant_id=<uuid> < perf/graph-perf-200-seed.sql
--
-- Then measure with `TENANT_ID=<uuid> perf/bench.sh` (bench.sh does not seed), and
-- remove the fixture afterwards with perf/graph-perf-200-teardown.sql.

\if :{?tenant_id}
\else
    \echo 'error: pass -v tenant_id=<uuid> (a throwaway tenant, not a real one)'
    \quit
\endif

SET app.current_tenant_id = :'tenant_id';

-- 130 services ------------------------------------------------------------

CREATE TEMP TABLE seeded_services AS
WITH seeded AS (
    INSERT INTO registry.services (tenant_id, name, description, health_status, source, tier)
    SELECT
        current_setting('app.current_tenant_id')::uuid,
        'Perf200 Seed Service ' || lpad(n::text, 3, '0'),
        'Synthetic service for the 200-edge performance fixture (docs/roadmap.md §5, item 2.6).',
        (ARRAY['HEALTHY', 'HEALTHY', 'HEALTHY', 'DEGRADED', 'UNHEALTHY'])[1 + (n % 5)],
        'seed',
        (ARRAY['CRITICAL', 'STANDARD', 'STANDARD', 'EXPERIMENTAL'])[1 + (n % 4)]
    FROM generate_series(1, 130) AS n
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

-- 182 declared backbone edges over the 130 services ------------------------
-- Full permutation at offset 1 (130 edges) plus a partial permutation at
-- offset 7 over the first 52 services (52 edges). Both offsets are coprime
-- with 130 and never coincide mod 130, so every (source, target) pair is
-- distinct and no self-edges occur.

WITH ordered AS (
    SELECT id, row_number() OVER (ORDER BY name) - 1 AS idx
    FROM seeded_services
),
edges AS (
    SELECT a.id AS source_id, b.id AS target_id,
           (ARRAY['http', 'grpc', 'kafka', 'db'])[1 + ((a.idx + b.idx) % 4)] AS protocol
    FROM ordered a
    JOIN ordered b ON b.idx = (a.idx + 1) % 130
    UNION ALL
    SELECT a.id, b.id,
           (ARRAY['http', 'grpc', 'kafka', 'db'])[1 + ((a.idx + b.idx) % 4)]
    FROM ordered a
    JOIN ordered b ON b.idx = (a.idx + 7) % 130
    WHERE a.idx < 52
)
INSERT INTO topology.dependencies (tenant_id, source_service_id, target_service_id, dependency_type, protocol)
SELECT current_setting('app.current_tenant_id')::uuid, source_id, target_id, 'declared', protocol
FROM edges
ON CONFLICT (tenant_id, source_service_id, target_service_id, dependency_type, protocol)
    WHERE deleted_at IS NULL
    DO NOTHING;

-- SPOF hub cases, same recipe as seed/graph-perf-seed.sql -------------------
-- Adds 3 synthetic "hub" services with fan-in at/above SpofService's
-- DEFAULT_THRESHOLD, drawing dependents from idx 0-17 of the 130-service
-- pool, for 18 more edges (182 + 18 = 200 total). Not load-bearing for the
-- performance measurement itself (SPOF detection is already covered by
-- issues #116/#117) — kept only so this fixture's shape matches the Phase 1
-- seed's precedent rather than being a bare backbone cycle.

CREATE TEMP TABLE seeded_spof_hubs AS
WITH seeded AS (
    INSERT INTO registry.services (tenant_id, name, description, health_status, source, tier, team_id)
    VALUES
        (current_setting('app.current_tenant_id')::uuid,
         'Perf200 Seed SPOF Hub - Critical Tier',
         'Synthetic SPOF: 7 dependents, CRITICAL tier -> CRITICAL severity (tier escalation).',
         'HEALTHY', 'seed', 'CRITICAL', NULL),
        (current_setting('app.current_tenant_id')::uuid,
         'Perf200 Seed SPOF Hub - Owned Standard Tier',
         'Synthetic SPOF: 5 dependents, STANDARD tier, owning team assigned -> WARNING severity.',
         'HEALTHY', 'seed', 'STANDARD', 'a0000000-0000-0000-0000-00000000000a'),
        (current_setting('app.current_tenant_id')::uuid,
         'Perf200 Seed SPOF Hub - Unowned Standard Tier',
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

-- Fan-in edges: idx 0-6 (7 services) -> Critical Tier hub,
-- idx 7-11 (5 services) -> Owned Standard Tier hub,
-- idx 12-17 (6 services) -> Unowned Standard Tier hub.
WITH ordered AS (
    SELECT id, row_number() OVER (ORDER BY name) - 1 AS idx
    FROM seeded_services
),
spof_edges AS (
    SELECT o.id AS source_id, h.id AS target_id
    FROM ordered o, seeded_spof_hubs h
    WHERE o.idx BETWEEN 0 AND 6 AND h.name = 'Perf200 Seed SPOF Hub - Critical Tier'
    UNION ALL
    SELECT o.id, h.id
    FROM ordered o, seeded_spof_hubs h
    WHERE o.idx BETWEEN 7 AND 11 AND h.name = 'Perf200 Seed SPOF Hub - Owned Standard Tier'
    UNION ALL
    SELECT o.id, h.id
    FROM ordered o, seeded_spof_hubs h
    WHERE o.idx BETWEEN 12 AND 17 AND h.name = 'Perf200 Seed SPOF Hub - Unowned Standard Tier'
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
     WHERE tenant_id = current_setting('app.current_tenant_id')::uuid AND deleted_at IS NULL) AS edges_for_tenant;
