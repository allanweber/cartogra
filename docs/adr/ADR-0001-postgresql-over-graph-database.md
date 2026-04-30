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
|--------|----------------|
| Neo4j (self-hosted) | Second persistence system, JVM resource contention, licensing complexity for open-source |
| Amazon Neptune | Cloud-provider lock-in, unavailable in local Docker Compose, cost at MVP stage |
| Apache AGE (PostgreSQL extension) | Immature ecosystem, limited Kubernetes operator support, adds binary extension dependency |

## References

- [PostgreSQL Recursive Queries](https://www.postgresql.org/docs/current/queries-with.html)
- [project-scope.md §3 — Graph queries](../project-scope.md)
