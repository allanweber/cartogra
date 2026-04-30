# Architecture Decision Records

This directory captures significant architectural decisions made during the development of Cartogra. Each ADR is immutable once accepted — superseded decisions are marked with a reference to the superseding record rather than deleted.

## Index

| ID | Title | Status | Date |
|----|-------|--------|------|
| [ADR-0001](ADR-0001-postgresql-over-graph-database.md) | PostgreSQL over a dedicated graph database | Accepted | 2026-04-30 |
| [ADR-0002](ADR-0002-scm-provider-abstraction.md) | SCM provider abstraction via SPI | Accepted | 2026-04-30 |

## Process

1. Copy `TEMPLATE.md` and name it `ADR-NNNN-<kebab-title>.md`.
2. Fill in context, decision, consequences, and alternatives.
3. Open a PR with **`docs:`** prefix; tag relevant deciders as reviewers.
4. On merge to `main` the status becomes **Accepted**.
5. To supersede: change the old ADR status to *Superseded by ADR-NNNN* and create the new record.

## Statuses

| Status | Meaning |
|--------|---------|
| Proposed | Under review — do not implement yet |
| Accepted | Approved and in effect |
| Deprecated | Still in use but being phased out |
| Superseded | Replaced by a later decision |
