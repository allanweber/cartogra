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
| [ADR-0010](ADR-0010-gateway-sole-token-issuer.md) | Gateway as the sole JWT issuer | Accepted | 2026-05-11 |
| [ADR-0011](ADR-0011-httponlycookie-bearer-dual-auth.md) | HttpOnly cookie + Bearer token dual auth | Accepted | 2026-05-11 |
| [ADR-0012](ADR-0012-gateway-servlet-stack.md) | Gateway on Spring MVC servlet stack | Accepted | 2026-05-18 |
| [ADR-0013](ADR-0013-guest-demo-access-deferred.md) | Guest demo access deferred to Phase 5 | Accepted | 2026-05-20 |
| [ADR-0014](ADR-0014-sync-command-idempotency.md) | Sync command idempotency: concurrent-execution guard | Accepted | 2026-05-20 |
| [ADR-0015](ADR-0015-codeowners-persistence-shape.md) | CODEOWNERS persistence shape (auto-assign on `services.team_id`) | Proposed | 2026-05-20 |
| [ADR-0016](ADR-0016-otel-span-worker-ingestion.md) | OtelSpanWorker ingestion path (Kafka topic from OTel Collector) | Proposed | 2026-05-20 |
| [ADR-0017](ADR-0017-audit-events-ownership-port.md) | Audit events: registry-owned table + `AuditEventPort` in shared:common | Proposed | 2026-05-20 |
| [ADR-0018](ADR-0018-spec-discovery-transport.md) | Spec discovery transport (Kafka topic from ingestion) | Proposed | 2026-05-20 |
| [ADR-0019](ADR-0019-guest-enforcement-mechanism.md) | Guest enforcement via special JWT role (no anonymous path) | Proposed | 2026-05-20 |
| [ADR-0020](ADR-0020-shared-web-module.md) | New `shared:web` Gradle module for cross-service web filters | Proposed | 2026-05-20 |
| [ADR-0021](ADR-0021-service-discovery-upsert-keying.md) | Service Discovery Upsert Keying Strategy | Accepted | 2026-06-04 |
| [0022](0022-team-owner-role-tenant-wide-enforcement.md) | TEAM_OWNER role enforced tenant-wide until team membership exists | Superseded by ADR-0025 | 2026-07-01 |
| [0023](0023-kafka-manual-ack-preserves-failure-semantics.md) | Kafka consumers move to manual ack, but still ack on failure | Accepted | 2026-07-08 |
| [0024](0024-gateway-circuit-breaking-via-route-filter-not-restclient.md) | Gateway circuit breaking is a Resilience4j route filter, not a per-service RestClient wrapper | Accepted | 2026-07-08 |
| [0025](0025-team-membership-replaces-team-owner-role.md) | Team membership (live DB check) replaces the TEAM_OWNER role | Accepted | 2026-07-13 |
| [0026](0026-accept-alpha-opentelemetry-logback-appender.md) | Accept `opentelemetry-logback-appender-1.0` as a permanently-alpha dependency | Accepted | 2026-07-17 |
| [0027](0027-topology-consumes-ownership-events-for-orphan-risk.md) | Topology consumes a Registry ownership event to compute orphan risk | Proposed | 2026-08-05 |

## Process

1. Copy `TEMPLATE.md` and name it `ADR-NNNN-<kebab-title>.md`.
2. Fill in context, decision, consequences, and alternatives.
3. Open a PR with **`docs:`** prefix; tag relevant deciders as reviewers.
4. On merge to `main` the status becomes **Accepted**.
5. To supersede: change the old ADR status to *Superseded by ADR-NNNN* and create the new record.

> Files from ADR-0022 onward dropped the `ADR-` filename prefix (e.g. `0028-my-decision.md`,
> not `ADR-0028-my-decision.md`) — match the newer, unprefixed convention for any new ADR.

## Statuses

| Status | Meaning |
| ------ | ------- |
| Proposed | Under review — do not implement yet |
| Accepted | Approved and in effect |
| Deprecated | Still in use but being phased out |
| Superseded | Replaced by a later decision |
