# Architecture Decision Records

This directory captures significant architectural decisions made during the development of Cartogra. Each ADR is immutable once accepted — superseded decisions are marked with a reference to the superseding record rather than deleted.

## Index

| ID | Title | Status | Date |
| -- | ----- | ------ | ---- |
| [ADR-0001](ADR-0001-postgresql-over-graph-database.md) | PostgreSQL over a dedicated graph database (includes CTE query strategy) | Accepted | 2026-04-30 |
| [ADR-0002](ADR-0002-scm-provider-abstraction.md) | SCM provider abstraction via SPI | Accepted | 2026-04-30 |
| ADR-0003 | gRPC for internal service-to-service communication | Superseded | 2026-05-18 — dropped in favour of REST via RestClient; gRPC deferred to Phase 6 research |
| [ADR-0007](ADR-0007-local-dev-infrastructure.md) | Local development infrastructure | Accepted | 2026-04-30 |
| [ADR-0008](ADR-0008-lgtm-observability-stack.md) | LGTM observability stack | Accepted | 2026-04-30 |
| [ADR-0009](ADR-0009-spring-data-jdbc.md) | Spring Data JDBC over JPA/Hibernate | Accepted | 2026-05-11 |

## Process

1. Copy `TEMPLATE.md` and name it `ADR-NNNN-<kebab-title>.md`.
2. Fill in context, decision, consequences, and alternatives.
3. Open a PR with **`docs:`** prefix; tag relevant deciders as reviewers.
4. On merge to `main` the status becomes **Accepted**.
5. To supersede: change the old ADR status to *Superseded by ADR-NNNN* and create the new record.

## Statuses

| Status | Meaning |
| ------ | ------- |
| Proposed | Under review — do not implement yet |
| Accepted | Approved and in effect |
| Deprecated | Still in use but being phased out |
| Superseded | Replaced by a later decision |
