# ADR-0001 — PostgreSQL over a dedicated graph database

**Date:** 2026-04-30
**Status:** Accepted
**Deciders:** Platform team

---

## Context

Cartogra's Topology service must store and query a directed dependency graph: nodes are services, edges are declared or observed dependencies. Common operations include:

- Shortest-path / reachability queries (blast radius)
- Cycle detection
- Transitive fan-in / fan-out counts (SPOF scoring)
- Point-in-time graph snapshots (temporal diffing)

Dedicated graph databases (Neo4j, Amazon Neptune, TigerGraph) are purpose-built for these patterns. However, the project already mandates PostgreSQL for relational data across all other services. Introducing a second persistence technology increases operational surface area and adds cognitive load for a team building an MVP.

PostgreSQL 14+ supports **recursive CTEs** (`WITH RECURSIVE`) which are sufficient to express all graph traversal patterns this system requires within a single SQL query.

## Decision

Use **PostgreSQL with recursive CTEs** for all dependency graph storage and traversal in the Topology service. Graph data lives in the `dependencies` table (source, target, type, metadata) with appropriate indexes. No graph database will be introduced.

## Consequences

### Positive

- Single persistence engine across all services — one operational concern, one backup strategy, one skill set.
- Recursive CTEs cover blast radius, cycle detection, and ancestry queries with predictable performance at expected scale (tens of thousands of services, millions of edges).
- Flyway migrations own schema evolution alongside all other domain tables.
- Temporal snapshots are handled via the existing soft-delete + `created_at` / `deleted_at` pattern.

### Negative / Trade-offs

- Deeply recursive traversals (depth > 20) on graphs with millions of edges will be slower than a native graph engine. Acceptable for current scale; revisit at 100k+ edges.
- No built-in graph algorithms (PageRank, community detection). Custom SQL or a lightweight library required if needed.
- Schema design requires discipline — adjacency list only, no property graph flexibility.

### Neutral

- Recursive CTE syntax is non-trivial; graph queries will be well-documented in `docs/architecture/kafka-topics.md` and inline comments.

## Alternatives Considered

| Option | Reason rejected |
| ------ | --------------- |
| Neo4j (self-hosted) | Second persistence system, JVM resource contention, licensing complexity for open-source |
| Amazon Neptune | Cloud-provider lock-in, unavailable in local Docker Compose, cost at MVP stage |
| Apache AGE (PostgreSQL extension) | Immature ecosystem, limited Kubernetes operator support, adds binary extension dependency |

## CTE Query Strategy (addendum — 2026-05-11)

All graph traversal queries in the Topology service follow three rules enforced by code review:

1. **Depth guard** — every `WITH RECURSIVE` must include a `WHERE depth < N` clause (default 10) to prevent unbounded traversal on cyclic or deeply nested graphs.
2. **Index-backed joins** — `dependencies(source_id)` and `dependencies(target_id)` are both indexed; each CTE step must join on an indexed column.
3. **Tenant isolation inside the CTE** — the anchor query must include `AND tenant_id = :tenantId`; the recursive step joins back to the `dependencies` table which also carries `tenant_id`. Row-level security provides a safety net but is not a substitute for explicit filtering.

Example canonical blast-radius CTE:

```sql
WITH RECURSIVE blast_radius AS (
    SELECT target_id AS service_id, 1 AS depth
    FROM   dependencies
    WHERE  source_id  = :serviceId
      AND  tenant_id  = :tenantId
      AND  deleted_at IS NULL
    UNION ALL
    SELECT d.target_id, br.depth + 1
    FROM   dependencies d
    JOIN   blast_radius  br ON br.service_id = d.source_id
    WHERE  d.tenant_id  = :tenantId
      AND  d.deleted_at IS NULL
      AND  br.depth     < 10
)
SELECT DISTINCT service_id FROM blast_radius;
```

The same pattern applies to cycle detection (add a `visited` array and check `NOT (target_id = ANY(visited))`) and ancestor queries (swap `source_id`/`target_id`).

## Dependency Graph Materialized View (addendum — 2026-09-25)

`dependency_graph_edges` (introduced in `V003__create_dependency_graph_view.sql`) is a
materialized view over `dependencies` — a flattened, pre-filtered (`deleted_at IS NULL`)
snapshot that graph traversal queries (blast radius, cycle detection, SPOF scoring) read
from instead of the base table, so those queries never re-filter soft deletes or contend
with the base table's write path.

**Refresh strategy:** writes never refresh the view inline. Every mutation to `dependencies`
calls `DependencyGraphViewRefreshScheduler#markDirty()`, setting an in-process flag; a
fixed-delay scheduled tick (default `PT5S`) checks the flag, takes a global Postgres advisory
lock so only one horizontally-scaled instance refreshes at a time, and runs
`REFRESH MATERIALIZED VIEW CONCURRENTLY`. This debounces bursts of writes into a single
refresh and keeps the view queryable throughout.

**Schema-parity risk:** the view is a hand-maintained `SELECT` projection, not a generated
mirror — it currently projects `tenant_id, source_service_id, target_service_id,
dependency_type, protocol, metadata` and omits `id, created_at, updated_at, deleted_at`.
Postgres itself prevents dropping or renaming a `dependencies` column the view depends on,
but nothing prevents a *new* `dependencies` column from being added and silently never
projected into the view. `DependencyGraphViewSchemaParityIT` (in
`services/topology/src/test/java/io/cartogra/topology/infrastructure/jdbc/`) closes this
gap: it fails the build if a `dependencies` column is neither in the view nor in that test's
documented exclusion list, forcing an explicit decision on every new column instead of
letting the two drift apart silently.

## References

- [PostgreSQL Recursive Queries](https://www.postgresql.org/docs/current/queries-with.html)
- [project-scope.md §3 — Graph queries](../project-scope.md)
