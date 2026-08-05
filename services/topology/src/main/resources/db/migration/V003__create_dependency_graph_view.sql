-- Flattened snapshot of active (non-deleted) dependency edges, used by recursive-CTE
-- graph queries (blast radius, cycle detection, SPOF scoring — Phase 2 features 2.4/2.5)
-- so those traversals never have to re-filter `deleted_at` or re-join across the base
-- table's write path.
--
-- Refresh strategy (implemented in DependencyGraphViewRefreshScheduler, not SQL):
--   - Every dependency/drift mutation calls DependencyGraphViewRefreshScheduler#markDirty(),
--     setting an in-process dirty flag rather than refreshing inline — this debounces
--     bursts of writes (e.g. a batch of observed-edge upserts) into a single refresh.
--   - A fixed-delay scheduled tick checks the dirty flag; if set, it acquires the
--     Postgres advisory lock `AdvisoryLockRepository.lockKey("dependency-graph-view", tenantId is
--     not scoped here — the lock key is global, "dependency-graph-view:refresh")` so that only one
--     instance refreshes at a time when the service is scaled horizontally, then runs
--     `REFRESH MATERIALIZED VIEW CONCURRENTLY dependency_graph_edges` and clears the flag.
--   - CONCURRENTLY requires the unique index below (lets readers keep querying the
--     view uninterrupted while it refreshes).
CREATE MATERIALIZED VIEW dependency_graph_edges AS
SELECT tenant_id, source_service_id, target_service_id, dependency_type, protocol
FROM dependencies
WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX ON dependency_graph_edges (tenant_id, source_service_id, target_service_id, dependency_type, protocol);
CREATE INDEX ON dependency_graph_edges (tenant_id, source_service_id);
CREATE INDEX ON dependency_graph_edges (tenant_id, target_service_id);
