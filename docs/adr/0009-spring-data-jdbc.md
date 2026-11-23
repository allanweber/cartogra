# ADR-0009 — Spring Data JDBC over JPA/Hibernate

**Date:** 2026-05-11
**Status:** Accepted
**Deciders:** Platform team

---

## Context

Cartogra is a multi-tenant SaaS platform where every domain table carries a `tenant_id` column and every query must filter by it. The system is audit-heavy: mutating operations write a JSONB snapshot to `services_history`, meaning the persistence layer must be transparent enough to compose mutations and history inserts in a single transaction. Services also use soft deletes (`deleted_at`) and JSONB columns for flexible metadata and tech-stack detection.

JPA/Hibernate is the de-facto standard for Spring persistence, but it introduces a set of trade-offs that conflict with these requirements:

- **Hidden SQL generation** — Hibernate generates queries at runtime from mapping metadata. Auditing or tuning the actual SQL requires enabling SQL logging and decoding the output, which is error-prone under JSONB operators and CTEs.
- **Lazy loading / session management** — Multi-tenant REST APIs that pass entities across transaction boundaries are prone to `LazyInitializationException`. Working around this requires careful `@Transactional` scoping or DTO projections — complexity that scales with the domain.
- **L1/L2 cache** — Hibernate's identity map and second-level cache interact poorly with row-level security and cross-tenant queries if misconfigured.
- **JPA ≠ Spring Boot 4 / Jakarta EE 11** — Spring Boot 4 targets Jakarta Persistence 3.2. Migration from `javax.persistence` adds churn with no domain benefit.

Spring Data JDBC is a lighter alternative built on the same `spring-data-commons` abstractions. It uses explicit SQL (`@Query` or `NamedParameterJdbcTemplate`), has no session concept, and makes every interaction with the database visible and testable.

## Decision

Use **Spring Data JDBC** (`spring-boot-starter-data-jdbc`) for all persistence across all Cartogra services. JPA/Hibernate (`spring-boot-starter-data-jpa`) will never be added. Custom JDBC queries use `NamedParameterJdbcTemplate` with named parameters (`:param`) — never string concatenation.

Domain entities are Java records mapped via `RowMapper` lambdas in the JDBC repository implementations. Aggregate-root discipline is enforced: child entities (e.g., `ServiceSnapshot`) are saved through use cases that call the appropriate repository, not via cascading.

## Consequences

### Positive

- All SQL is explicit, version-controlled, and reviewable in PRs.
- No lazy loading — domain records are immutable value objects fetched in full. No `LazyInitializationException` ever.
- No L1/L2 cache to invalidate when a different service mutates the same row (e.g., tenant isolation bypass risk eliminated).
- `NamedParameterJdbcTemplate` composes naturally with JSONB operators (`@>`, `?`, `@@`) and recursive CTEs that Hibernate would require `@Formula` or native query hacks to express.
- No `EntityManager` lifecycle to manage — Spring's `@Transactional` boundaries are the only unit of work.
- Spring Boot 4 compatibility is guaranteed — Spring Data JDBC 4.x tracks the same release train.

### Negative / Trade-offs

- No automatic dirty tracking — updates require an explicit `save()` call even for small field changes.
- No built-in optimistic locking via `@Version` — must be implemented with a `WHERE updated_at = :prev` clause if needed (not currently required).
- More boilerplate for complex queries compared to JPQL — each filter combination needs an explicit SQL branch or dynamic query builder.
- No schema generation from entity annotations — Flyway owns DDL exclusively (this is already a project-wide rule, so not an added cost).

### Neutral

- `RowMapper` lambdas are concise for record types and tested independently from Spring context.
- Pagination is handled by explicit `LIMIT/OFFSET` in SQL rather than Spring Data's `Pageable` abstraction — consistent with the project's `PageResult<T>` envelope.

## Alternatives Considered

| Option | Reason rejected |
| ------ | --------------- |
| JPA / Hibernate | Hidden SQL, lazy loading hazards, L2 cache complexity, `LazyInitializationException` in REST context |
| jOOQ | Type-safe and excellent for complex SQL; adds code-generation step and per-dialect license cost — disproportionate for current scale |
| Plain `JdbcTemplate` | Missing named-parameter support out of the box; `NamedParameterJdbcTemplate` is a thin wrapper over it and is already bundled |
| MyBatis | XML mapper files add a second file per query; annotation mode sacrifices refactoring safety |

## References

- [Spring Data JDBC Reference](https://docs.spring.io/spring-data/jdbc/docs/current/reference/html/)
- [Spring Boot 4 — Data JDBC migration notes](https://spring.io/blog/2025/02/01/spring-boot-4-0-0-m1-available-now)
- [CLAUDE.md — Tech Stack: Persistence](../../CLAUDE.md)
