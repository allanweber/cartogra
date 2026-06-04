# Cartogra — Execution Checklist

This file is the working checklist for shipping Cartogra. Use [plan.md](c:/Users/allan/projects/cartogra/docs/plan.md) for rationale, scope boundaries, dependency maps, user stories, and phase gates. Use [project-guide.md](c:/Users/allan/projects/cartogra/docs/project-guide.md) and [project-scope.md](c:/Users/allan/projects/cartogra/docs/project-scope.md) as the product and architecture source material when a checklist item needs more context.

## Category Key

- `[CODE]` application code, services, APIs, tests, and shared libraries
- `[INFRA]` Docker, CI, cloud, deployment, secrets, and runtime wiring
- `[UI]` frontend routes, components, UX flows, and visualization work
- `[TEST]` automated verification, fixtures, performance checks, and smoke tests
- `[DOCS]` ADRs, OpenAPI, runbooks, and contributor docs
- `[BIP]` build-in-public artifacts published immediately after the dependent work lands
- `[GATE]` phase-exit checks; do not start the next phase until these pass or are explicitly deferred
- `[DONE]` work completed before this checklist became the authoritative tracker

## Task ID Format

Each task carries an ID of the form `{phase}.{sequence}` (e.g. `0.3`, `1.17`). Sequence is global within a phase, not per-section. Gate rows carry no ID.

## Working Rules

- Each non-BIP task is an end-to-end vertical: schema (if needed) + backend + UI wiring (if user-facing) + integration tests + OpenAPI/diagram updates. Do not split a single feature across multiple tasks.
- Do not introduce abstractions, tables, endpoints, or events that no in-phase task consumes. If it is only used "later," it ships in the later phase.
- Keep this file chronological. Add new tasks where they should be executed, not at the end of a phase.
- Keep user-story scope, rationale, and phase boundaries in [plan.md](c:/Users/allan/projects/cartogra/docs/plan.md). Items explicitly deferred from MVP live in the **Explicitly out of MVP** section of `plan.md`.
- When a task changes product scope or architecture, update the relevant ADR or source doc in the same PR.
- When a task produces a BIP artifact, publish it immediately after the implementation or doc it depends on.
- DONE tasks (e.g. `1.39f` referencing "until 2.35 lands") may contain stale task-ID references because tasks were renumbered. Do not edit DONE tasks; the audit-events feature now lives at `2.12`.

---

<details>

<summary>phase 0</summary>

## Phase 0 — Foundation

### Completed before this checklist

- ~~[DONE] [CODE] S0.1 repository structure and initial multi-module layout bootstrapped~~
- ~~[DONE] [CODE] S0.2 gateway, registry, and ingestion service stubs created with Gradle wiring~~
- ~~[DONE] [INFRA] S0.3 local Docker Compose stack brought up successfully~~

### System design and UX foundation

- [x] 0.1 [DOCS] Create `DESIGN.md` at the repo root with visual design tokens: color palette, typography scale, spacing system, elevation levels, and border-radius values that all frontend phases will share.
- [x] 0.2 [DOCS] Create `PRODUCT.md` at the repo root with the product register: target users, product purpose, personality and tone, anti-references (what Cartogra should never feel like), and per-phase screen inventory.
- [x] 0.3 [UI] Run `/shape app shell` to produce a confirmed design brief for the root layout, navigation sidebar, error boundary, and 404 page before writing any Phase 0 frontend code.

### Repository and governance

- [x] 0.4 [DOCS] Finalize root governance files: `LICENSE`, `README.md`, `CONTRIBUTING.md`, `.editorconfig`, `.gitignore`, `CODEOWNERS`, and branch-protection expectations.
- [x] 0.5 [DOCS] Add GitHub issue templates for bug report, feature request, and architecture discussion plus a PR template with tests, docs, tenancy, and observability checks.
- [x] 0.6 [DOCS] Create the GitHub Project board with columns and milestones for Phases 0 through 5.

### Build and shared modules

- [x] 0.7 [CODE] ~~Scaffold `shared:contracts` Gradle module with the protobuf plugin~~ — module removed 2026-05-18; gRPC deferred to Phase 6 research. `shared:common` and `shared:test-support` remain.
- [x] 0.8 [CODE] Finish `shared:common` with the Kafka event envelope, shared IDs and value objects, and a Spring-free API error model.
- [x] 0.9 [CODE] Finish `shared:test-support` with reusable Postgres and Kafka test helpers for Testcontainers-based integration tests.
- [x] 0.10 [CODE] Harden `services:registry` for Spring Data JDBC, explicit Flyway, virtual threads, and health readiness wiring.
- [x] 0.11 [CODE] Harden `services:gateway` for Spring Cloud Gateway, OTel tracing, `traceparent` propagation, `X-Trace-Id` response header handling, and virtual threads.
- [x] 0.12 [CODE] Harden `services:ingestion` for Spring Data JDBC, explicit Flyway, virtual threads, and a health-only Phase 0 stub.
- [x] 0.13 [CODE] Add one sample endpoint that proves the HTTP response envelope and returns `{ "data": ..., "traceId": "..." }` plus the matching `X-Trace-Id` header.
- Note 2026-05-05: `services:registry` and `services:ingestion` now include `org.flywaydb:flyway-database-postgresql`, which fixes Flyway startup against PostgreSQL 16.13 while the broader hardening work in 0.10 and 0.12 remains open.
- Note 2026-05-05: `services:registry` and `services:ingestion` now use separate Flyway history tables in the shared local Postgres database so each service can keep its own `V001+` migration sequence without checksum collisions.

### Database baseline

- [x] 0.14 [INFRA] Add registry Flyway `V001__create_tenants.sql` with UUID PKs, `TIMESTAMPTZ`, soft delete, and RLS.
- [x] 0.15 [INFRA] Add registry Flyway `V002__create_teams.sql` with tenant isolation, indexes, soft delete, and RLS.
- [x] 0.16 [INFRA] Add registry Flyway `V003__create_users.sql` with tenant isolation, indexes, soft delete, and RLS.
- [x] 0.17 [INFRA] Add registry Flyway `V004__create_scm_connections.sql` with tenant isolation, indexes, soft delete, and RLS.
- [x] 0.18 [TEST] Add a Testcontainers smoke test that boots Postgres, applies registry migrations cleanly, and fails on any ordering or checksum issue.

### Local runtime and containers

- [x] 0.19 [INFRA] Finalize `infra/docker-compose/docker-compose.yml` with PostgreSQL 16, Redis-compatible cache, Kafka-compatible broker, healthchecks, and named volumes.
- [x] 0.20 [INFRA] Finalize `infra/docker-compose/otel-collector.yml` to receive OTLP and export traces→Tempo, metrics→Prometheus, logs→Loki (LGTM stack; supersedes Jaeger — see ADR-0008).
- [x] 0.21 [INFRA] Finalize `infra/docker-compose/docker-compose.dev.yml` with LGTM observability stack (Tempo, Loki, Prometheus, Grafana) and dev helpers (Kafka UI, Valkey UI, pgAdmin).
- Note 2026-05-06: LGTM stack replaces Jaeger; Grafana on port 3001, full three-signal correlation (traces + logs + metrics).
- [x] 0.22 [INFRA] Add multi-stage Dockerfiles for registry, gateway, and ingestion with Temurin 25, non-root user, and `MaxRAMPercentage=75`.
- [x] 0.23 [INFRA] Add a frontend Dockerfile stub or document why frontend image creation is intentionally deferred beyond Phase 0.
- Note 2026-05-07: Frontend Dockerfile stub added at `infra/docker/frontend/Dockerfile` (Phase 0, non-production hardening).

### Observability and CI

- [x] 0.25 [CODE] Enable structured JSON logging in gateway, registry, and ingestion with the same trace ID that appears in OTel spans and HTTP responses.
- [x] 0.26 [TEST] Verify one traced request end-to-end: incoming `traceparent`, service logs, response body `traceId`, and `X-Trace-Id` must all match.
- [x] 0.27 [INFRA] Finalize `.github/workflows/ci.yml` for Java 25, `./gradlew build`, tests, container build checks, and Trivy failure on `HIGH` or `CRITICAL` findings.
- [x] 0.28 [BIP] Publish the launch thread once the stack compiles cleanly and the core services boot together.
- [x] 0.29 [BIP] Publish the data-model and Kafka sketch thread immediately after the baseline migrations and architecture diagrams land.

### Frontend shell

- [x] 0.30 [UI] Initialize TanStack Start with TypeScript strict mode, ESLint (flat config), Prettier, and Tailwind CSS configured from `DESIGN.md` tokens.
- [x] 0.31 [UI] Run `npx shadcn@latest init` and install the base component set: Button, Card, Alert, AlertDescription, Skeleton, Input, Badge, Separator, Sheet, and Tooltip.
- [x] 0.32 [UI] Set up TanStack Router file-based routing with placeholder route files for Catalog (`/catalog`), Graph (`/graph`), Contracts (`/contracts`), Intelligence (`/intelligence`), and Operations (`/ops`).
- [x] 0.33 [UI] Implement `apiFetch<T>` in `frontend/src/lib/api.ts` with Cartogra envelope parsing, `ApiError` class carrying `code`, `message`, and `traceId`, and `X-Trace-Id` header extraction.
- [x] 0.34 [UI] Set up Zustand store skeleton in `frontend/src/stores/` with a tenant-scoped slice pattern; do not use Context for global state.
- [x] 0.35 [UI] Configure vitest with happy-dom and React Testing Library; add `npm run test` and `npm run typecheck` scripts.
- [x] 0.36 [UI] Create `frontend/.env.local` with `VITE_API_BASE_URL=http://localhost:8080`; add `frontend/.env.local.example` to the repo and `.env.local` to `.gitignore`.
- [x] 0.37 [UI] Craft the app shell, `AppLayout` component with sidebar navigation, root error boundary, and 404 page using `/impeccable craft app shell`. All outputs must use named exports, shadcn/ui primitives, and Tailwind for layout.
- [x] 0.38 [UI] Run `/impeccable audit` on all Phase 0 screens before the phase gate; address any blocking accessibility or responsiveness findings.
- [x] 0.39 [INFRA] Add frontend CI tasks: `pnpm install --frozen-lockfile`, ESLint check, TypeScript type-check, and `vitest --passWithNoTests`.

### Core documentation

- [x] 0.40 [DOCS] Finalize `docs/adr/TEMPLATE.md` and `docs/adr/README.md` as the ADR entry point.
- [x] 0.41 [DOCS] Finalize `docs/architecture/system-overview.md`, `docs/architecture/data-model.md`, and `docs/architecture/kafka-topics.md` with current Phase 0 status markers.
- [x] 0.42 [DOCS] Finalize `docs/api/gateway.openapi.yaml` and `docs/api/registry.openapi.yaml` with shared response-envelope components, error schema, and `X-Trace-Id` header documentation.
- [x] 0.43 [DOCS] Keep `docs/api/topology.openapi.yaml`, `docs/api/contract.openapi.yaml`, and `docs/api/intelligence.openapi.yaml` as explicit forward-looking stubs with Phase ownership notes.
- [x] 0.44 [DOCS] Create initial `docs/runbooks/local-development.md`, `docs/runbooks/deployment.md`, and `docs/runbooks/incident-response.md` stubs.
- [x] 0.45 [BIP] Publish the blog post on why service catalogs drift after the docs stubs and architecture framing are reviewable.
- [x] 0.46 [BIP] Publish the ADRs/build-in-public article right after the ADR template and index merge.
- [x] 0.47 [BIP] Publish a GitHub or blog update showing the README diagram and project board once both are visible.
- [x] 0.48 [BIP] Record the optional short problem-framing video if it does not slow the phase gate.

### Phase 0 Gate

- [x] [GATE] `DESIGN.md` and `PRODUCT.md` exist and contain enough detail to guide Phase 1 screen crafting.
- [x] [GATE] `./gradlew build` and CI are green, and the Trivy failure policy is documented.
- [x] [GATE] `docker compose up` succeeds with healthy gateway, registry, ingestion, database, cache, broker, and observability dependencies.
- [x] [GATE] Flyway clean migrate passes from a blank database in automation.
- [x] [GATE] At least one endpoint proves the response envelope and `X-Trace-Id` contract.
- [x] [GATE] Frontend installs and runs locally; CI lint, type-check, and test jobs pass.
- [x] [GATE] Minimum BIP set shipped: launch thread, data-model/Kafka thread, service-catalog blog, and ADR article.

</details>

---

## Phase 1 — Gateway MVP auth + Registry

### System design and UX

- [x] 1.1 [UI] Run `/shape login` and `/shape register` to produce confirmed design briefs for Login, Register, Forgot password, and Verify email screens before writing any auth UI code.
- [x] 1.2 [UI] Run `/shape catalog home` and `/shape scm connections` to produce confirmed design briefs for the Catalog list, Catalog detail, and SCM connections screens.

### Registry domain and APIs

- [x] 1.3 [CODE] Establish hexagonal package boundaries for registry: `api`, `application`, `domain`, `infrastructure`, and `config`.
- [x] 1.4 [INFRA] Add registry Flyway `V005__create_services.sql` with tenant isolation, soft delete, JSONB metadata, and the required indexes.
- [x] 1.5 [INFRA] Add registry Flyway `V006__create_services_history.sql` for temporal snapshots.
- [x] 1.6 [INFRA] Add registry Flyway `V007__create_scm_webhooks.sql` with tenant isolation and lifecycle fields.
- [x] 1.7 [CODE] Implement JDBC repositories for services, teams, and SCM connections using explicit SQL for filtering, search, and history access.
- [x] 1.8 [CODE] Implement service CRUD, owner assignment, health summary, orphan detection, and point-in-time history use cases.
- [x] 1.9 [CODE] Persist a `services_history` snapshot on every material service change.
- [x] 1.10 [CODE] Add REST controllers and `@RestControllerAdvice` handlers that always emit the documented envelope and stable error codes.
- [x] 1.11 [TEST] Add Testcontainers integration coverage for CRUD, history queries, orphan detection, and envelope/header behavior.
- [x] 1.12 [DOCS] Write the ADR for PostgreSQL plus recursive CTEs over a graph database.
- [x] 1.13 [BIP] Publish the PostgreSQL-vs-graph-DB ADR after it merges.
- [x] 1.14 [DOCS] Write the ADR or design note for Spring Data JDBC as the default persistence model.
- [x] 1.15 [BIP] Publish the Spring Data JDBC rationale immediately after it merges.

### Gateway authentication, authorization, and proxying

- [x] 1.16 [INFRA] Extend the users schema for password hash, email verification token and expiry, `auth_provider`, `auth_subject`, and any session or token metadata needed for MVP auth.
- [x] 1.17 [CODE] Implement Spring Security 7 on the gateway with local email/password registration, OTP verification, login, refresh, logout, and `userinfo` endpoints.
- [x] 1.18 [CODE] Add the Resend client with environment-based API key management and a test-mode strategy that suppresses real sends in CI.
- [x] 1.19 [CODE] Implement Google and GitHub OAuth start/callback flows through the gateway.
- [x] 1.20 [CODE] Implement tenant OIDC configuration storage plus an admin API; keep UI optional if the phase needs to defer it.
- [x] 1.21 [CODE] Issue secure httpOnly JWT cookies for browsers and Bearer tokens for non-browser clients; reject unverified users from accessing tenant data.
- [x] 1.22 [CODE] Enforce RBAC for `viewer`, `member`, and `admin` routes using `@EnableMethodSecurity` and `@PreAuthorize`; verify that tenant boundaries are derived from the authenticated principal, never inbound headers.
- [x] 1.23 [CODE] Strip client-supplied `X-Tenant-Id` from all inbound requests; inject gateway-derived tenant and principal headers downstream.
- [x] 1.24 [CODE] Proxy registry REST routes through the gateway using Spring Cloud Gateway; forward `traceparent` and set `X-Trace-Id` on all proxied responses.
- [x] 1.25 [CODE] Add Redis-backed rate limiting on all routes, including stricter token buckets for `/auth/*` and other expensive endpoints.
- [x] 1.26 [TEST] Add end-to-end auth tests for register → OTP → verify → login, cookie flows, Bearer flows, rate-limiting (assert 429), and cross-service trace propagation.
- [x] 1.27 [DOCS] Update `docs/api/gateway.openapi.yaml` to cover every implemented `/auth/*` route, cookie/Bearer behavior, and any deferred tenant-OIDC surface.

### SCM SPI and ingestion workers

- [x] 1.28 [CODE] Define the Spring-free `ScmProvider` SPI under ingestion application code.
- [x] 1.29 [CODE] Implement `GitHubProvider` using connection config stored as JSONB and WireMock-backed tests.
- [x] 1.30 [CODE] Implement `AzureDevOpsProvider` using PAT or service-principal config and WireMock-backed tests.
- [x] 1.31 [INFRA] Add ingestion Flyway `V001__create_sync_jobs.sql` with tenant isolation, status tracking, soft delete, indexes, and RLS.
- [x] 1.32 [CODE] Implement GitHub and Azure DevOps sync workers that consume sync commands, update `sync_jobs`, publish results, and propagate trace headers.
- [x] 1.33 [CODE] Add the Kubernetes worker behind `ENABLE_K8S_WORKER=true` with a documented kind/mock fallback for local development.
- [x] 1.34 [TEST] Add integration tests for both SCM providers plus sync job state transitions.
- [x] 1.35 [DOCS] Write the ADR for the SCM provider abstraction and update deployment docs with PAT/service-principal setup.
- [x] 1.36 [BIP] Publish the SCM SPI ADR after the abstraction and both provider paths are coded.
- [x] 1.37 [BIP] Publish the provider-comparison thread after both provider adapters have passing tests.

### Events, catalog UI, and demo access

- [x] 1.38 [CODE] Publish registry service lifecycle events with the shared Kafka envelope and `traceparent` propagation.
- [x] 1.38a [TEST] Replace `@MockitoBean ServiceLifecycleEventProducer` in `ServiceCrudIT` with `@EmbeddedKafka` + a real consumer that round-trips one envelope per CRUD operation; assert `eventType`, `entityId`, `tenantId`, `traceparent` header, and that `KafkaJsonSerializer` (Jackson 3) deserialises cleanly into `EventEnvelope<ServiceRegisteredPayload>` on the consumer side. This is the only end-to-end validation that the custom serializer works before any downstream consumer (2.18) is built.
- [x] 1.39 [CODE] Consume sync commands in ingestion with an idempotency strategy documented in code or ADR notes.
- [x] 1.39a [CODE] Add a stale-job reaper as a `@Scheduled` task in ingestion: every minute, find `sync_jobs` rows where `status='RUNNING'` and `updated_at < now() - ingestion.sync.stale-timeout` (default `PT30M`), flip them to `FAILED` with `error_message='reaper: timed out'`, and publish a `sync.completed` failure envelope so the registry consumer (1.67) updates `scm_connections.last_sync_status`. Update ADR-0014 with a "Stale RUNNING jobs and the reaper" addendum.
- [x] 1.39b [TEST] Add `StaleJobReaperIT`: insert a `RUNNING` row with `updated_at` 31 minutes ago, invoke the reaper method directly, assert the row is `FAILED` and a `cartogra.ingestion.sync.completed` envelope with `status='FAILED'` is published to `@EmbeddedKafka`.
- [x] 1.39c [DOCS] Write ADR-0015 — CODEOWNERS persistence shape. Decide between (a) auto-assigning `services.team_id` when `OwnershipMap.ownerTeams` resolves to exactly one existing tenant team, vs (b) a dedicated `service_codeowners` path-level table. Recommend (a) with an audit-event fallback for ambiguous matches; document the rationale. Mark ADR `Accepted` only after Allan signs off.
- [x] 1.39d [INFRA] Add the registry Flyway migration required by ADR-0015 (if option (b) is accepted, `V0NN__create_service_codeowners.sql`; if option (a), this task becomes a no-op and is closed with a note). — **No-op: ADR-0015 chose option (a), no new migration required.**
- [x] 1.39e [CODE] Publish `cartogra.ingestion.ownership.resolved` from ingestion: after `listRepositories` in `ExecuteSyncUseCaseImpl`, call `provider.resolveOwnership(...)` for each non-archived repo and publish one envelope per repo with payload `{tenantId, connectionId, repositoryFullName, ownerTeams, pathOwners}`. Reuse the existing `KafkaTemplate` and `traceparent` propagation pattern. Stop discarding the return value at the current call site.
- [x] 1.39f [CODE] Registry consumer for `cartogra.ingestion.ownership.resolved`: look up the tenant's `Team` by name, and apply the rule decided in ADR-0015. On ambiguous or unmatched ownership data, write an `audit_events` row with `action='codeowners.unmatched'` once 2.35 lands; until then, log at WARN with the connection + repo identity.
- [x] 1.39g [TEST] Add `CodeownersFlowIT` covering WireMock GitHub repo with a `CODEOWNERS` file → sync → ownership envelope published → registry consumes → service `teamId` is set (or `service_codeowners` row exists, depending on the ADR-0015 outcome).
- [X] 1.40 [CODE] Decide whether guest demo access is enabled in Phase 1; if enabled, enforce read-only behavior and keep gateway rate limits active.
- [X] 1.40 (duplicated) [CODE] Decide whether guest demo access is enabled in Phase 1; if enabled, enforce read-only behavior and keep gateway rate limits active.
- [X] 1.41 [UI] Craft Login, Register, Verify email, and OAuth handoff screens using `/impeccable craft login` and `/impeccable craft register`; all auth state must use httpOnly cookies — never `localStorage`.
- [X] 1.42 [UI] Craft the Catalog list and detail flows using `/impeccable craft catalog home`; include filters for team, health, tech stack, SCM provider, and search, plus orphan highlighting.
- [X] 1.43 [UI] Centralize envelope parsing in the frontend API client; surface `traceId` in all error states via the `ApiError` class established in Phase 0.
- [X] 1.44 [UI] Run `/impeccable audit` on all Phase 1 screens before the phase gate; address any blocking findings.
- [x] 1.45 [TEST] Add contract tests that verify gateway and registry responses match the OpenAPI envelope and `X-Trace-Id` contract.
- [x] 1.46 [DOCS] Update `docs/api/registry.openapi.yaml` to reflect the implemented registry surface.
- [x] 1.47 [BIP] Publish the envelope-and-trace-ID thread after gateway proxying works end to end.
- [x] 1.48 [BIP] Publish the Gateway-auth rationale thread after local auth and at least one OAuth provider work end to end.
- [x] 1.49 [BIP] Publish the self-healing-registry article after the catalog and sync story are demonstrable.
- [x] 1.50 [BIP] Publish the multi-tenant plus temporal-versioning article after history and ownership flows work.
- [x] 1.51 [BIP] Publish the enterprise-SCM angle on LinkedIn after both providers are visible in the catalog.
- [x] 1.52 [BIP] Publish screenshots plus OpenAPI links after the catalog UI is stable enough to demo.
- [x] 1.53 [BIP] Publish the dual-mode auth article (httpOnly cookies + Bearer tokens in a Spring Security 7 reactive gateway) after JWT issuance is stable end-to-end.
- [x] 1.54 [BIP] Publish the Redis rate-limiting article (token buckets, per-tenant key isolation, 429 behavior) after rate limiting is tested and observable.

### Service auto-discovery (E2E)

- [x] 1.55 [CODE] Service auto-discovery from SCM + Kubernetes. Define `cartogra.ingestion.service.discovered` envelope (`tenantId`, `connectionId`, `source ∈ {github,azure_devops,kubernetes}`, `externalId`, `name`, `description`, `repositoryUrl`, `repositoryRef`, `k8sCluster`, `k8sNamespace`, `k8sDeployment`, `techStack[]`, `healthStatus`, `healthEndpoint`, `lastCommitAt`, `lastCommitSha`). Extend `ExecuteSyncUseCaseImpl` so each non-archived repo: detects tech stack (presence of `pom.xml` / `build.gradle*` / `package.json` / `go.mod` / `Cargo.toml` / `requirements.txt`, plus Dockerfile `FROM` line via `provider.getFileContents`), pulls `lastCommitAt` + `lastCommitSha` via the SCM API, publishes the envelope before resolving ownership. Extend `KubernetesWorker` so on `Service` ADDED/MODIFIED in namespaces labeled `cartogra.io/tenant-id` it reads the pod/deployment Ready status (HEALTHY/DEGRADED/UNHEALTHY/UNKNOWN) and publishes the envelope with K8s metadata; namespace label resolves `tenantId`, configured synthetic connection UUID (`ingestion.workers.k8s.connection-id`) used as `connectionId`. Add `RegistryServiceDiscoveryConsumer` keyed on `(tenantId, externalId)` that upserts `services` (creating new rows when missing, updating tech stack / health / commit metadata when present), writes a `services_history` snapshot, and lets the existing `OwnershipResolvedConsumer` finish team assignment. ITs: `ServiceDiscoveryFlowIT` (WireMock GitHub repo with `pom.xml` + Dockerfile → `service.discovered` published → service appears in registry with `techStack=['java','spring-boot']`), `KubernetesWorkerIT` (`io.fabric8:kubernetes-server-mock` + `@EmbeddedKafka` — add `testImplementation("io.fabric8:kubernetes-server-mock:7.0.0")` to `services/ingestion/build.gradle.kts` — Service ADDED → service appears in registry with `healthStatus=HEALTHY`). Document the namespace label contract and tech-stack detection rules in `docs/runbooks/local-development.md`.
- [ ] 1.56 [CODE] Periodic health probe for non-K8s services. `@Scheduled` task in registry (`registry.health.probe-interval`, default 60 s) iterates services where `health_endpoint IS NOT NULL`, performs a short-timeout HTTP GET, maps status to `HEALTHY` (2xx), `DEGRADED` (4xx/5xx), `UNHEALTHY` (timeout / connection error), updates `health_status` + `health_checked_at`, and snapshots `services_history` on transitions. IT covers all three response classes.

### SCM sync — scheduled and webhook-driven (E2E)

- [ ] 1.57 [CODE] SCM connection lifecycle events + sync feedback loop. (1) Registry publishes `cartogra.registry.scm-connection.created/updated/deleted` envelopes with `traceparent` on every mutating use case. (2) Ingestion Flyway `V003__create_scm_connection_cache.sql` (`id`, `tenant_id`, `provider_type`, `config` JSONB, `sync_interval_minutes`, `last_synced_at`, `deleted_at`, unique on `(tenant_id, id)`, partial index for due connections) and a consumer that upserts the cache. (3) Registry Flyway adds `last_synced_at TIMESTAMPTZ` and `last_sync_status TEXT` to `scm_connections`. (4) Registry consumes the existing `cartogra.ingestion.sync.completed` topic and updates those columns. Canonicalize the topic name in `docs/architecture/kafka-topics.md`. Integration tests cover all four flows.
- [ ] 1.58 [CODE] Periodic SCM sync scheduler. `@Scheduled` task in ingestion (interval `ingestion.sync.poll-interval`, default 15 min) queries `scm_connection_cache` for due connections, acquires a `pg_try_advisory_lock` keyed on connection ID to prevent duplicate runs under horizontal scaling, publishes one `cartogra.registry.sync.command` envelope per due connection with `traceparent`, then atomically updates `last_synced_at`. IT seeds two cached connections (one due, one not) and asserts exactly one command is published.
- [ ] 1.59 [CODE] Webhook-driven SCM sync. Extend `ScmProvider` SPI with `registerWebhook(config, targetUrl) → WebhookRegistration`, `deregisterWebhook(config, externalWebhookId)`, `verifyWebhookSignature(request, rawBody, config) → boolean`, `isRelevantWebhookEvent(request) → boolean` (default no-op for existing providers). Implement fully for `GitHubProvider` (HMAC-SHA256 over raw body, `X-Hub-Signature-256`, org-level hook via `POST /orgs/{org}/hooks`) and `AzureDevOpsProvider` (shared-secret header + `POST /_apis/hooks/subscriptions`). Add `WebhookController` at `POST /webhooks/{providerType}/{connectionId}` excluded from gateway JWT auth and the envelope: read raw body via `ContentCachingRequestWrapper`, dispatch signature verification, on success publish `cartogra.registry.sync.command` and return 202; 401 on bad signature; 404 on unknown provider; 202 with no Kafka on irrelevant events (e.g. GitHub ping). Trigger registration off `scm_connection.created`; store `external_id` + hashed secret in `scm_webhooks`. `WebhookControllerIT` (`@EmbeddedKafka` + WireMock) covers signed/tampered/ping/AzDO/unknown paths.
- [ ] 1.60 [DOCS] Update `docs/api/ingestion.openapi.yaml` for `POST /webhooks/{providerType}/{connectionId}` (no auth, no envelope, 202/401/404). Add "Webhook setup" and "Scheduled sync configuration" sections to `docs/runbooks/deployment.md` (public URL, ngrok/Cloudflare Tunnel for local dev, GitHub org-level hooks, AzDO service hooks, `ingestion.sync.poll-interval`, adding a new provider via `ScmProvider`).

### Auth UX

- [ ] 1.61 [CODE] Password reset end-to-end. Gateway: `POST /v1/auth/forgot-password` issues a time-limited reset token via Resend; `POST /v1/auth/reset-password` validates the token and updates the password hash; both rate-limited like other `/auth/*` endpoints. UI: `/forgot-password` and `/reset-password` routes (excluded from the route guard), TanStack Forms wiring, success/error states surface `traceId`. ITs cover happy path, expired token, and rate limit.
- [ ] 1.62 [UI] Auth route guards + sign out. `beforeLoad` in `__root.tsx` protects every route except `/login`, `/register`, `/verify-email`, `/forgot-password`, `/reset-password`, `/oauth-handoff`; unauth requests redirect to `/login` with a `redirect` search param; after login or OAuth, navigate to `redirect` or `/dashboard`. Add a "Sign out" action in `AppLayout` sidebar bottom that calls `POST /api/auth/logout`, then `useAuthStore.clearAuth()`, then navigates to `/login`. Vitest covers guard logic and the sign-out flow.
- [ ] 1.63 [CODE] User profile management. Gateway: `PUT /v1/auth/userinfo` accepts `{ name, email }`, validates email uniqueness, returns the updated user in the envelope (httpOnly cookie or Bearer required). UI: `/settings/profile` shows name/email/role from `useAuthStore`, updates via TanStack Forms, links "Change password" to `/forgot-password`. `/settings` index lists Profile + SCM Connections.

### Catalog UI wired to real APIs

- [ ] 1.64 [UI] Catalog list + detail wired to registry. Replace `MOCK_SERVICES` with a TanStack Query on `GET /api/registry/v1/services` (passes `team`, `health`, `q` from filter state — client-side `q` until Phase 2 server FTS); detail route loader hits `GET /api/registry/v1/services/{id}`. Render `Skeleton` while loading and `Alert` with `traceId` on error. Remove all `MOCK_SERVICES` imports.
- [ ] 1.65 [CODE] Service profile editing. Registry: `PATCH /v1/services/{id}` accepts `description`, `documentation_url`, `runbook_url`, `sla_target`, `tier`, `tags`, `owner_team_id` (admin role or owning-team member). Validates `tier ∈ {critical, standard, experimental}`. Snapshots `services_history` on every change. UI: edit drawer in Catalog detail with TanStack Forms (tier select, tags chip input, owner team dropdown, free-text URLs). Optimistic update via TanStack Query mutation; rollback on error; surface `traceId`. ITs assert the patch + history snapshot + envelope.
- [ ] 1.66 [UI] Teams page wired to `GET /api/registry/v1/teams`. Replace `MOCK_TEAMS`, add `Skeleton` and `Alert`-with-`traceId` states.
- [ ] 1.67 [UI] SCM Connections management at `/settings/scm-connections`. List via `GET /api/registry/v1/scm-connections`; create connection via drawer form (provider selector, name, PAT/token); delete with confirmation dialog (`DELETE /api/registry/v1/scm-connections/{id}`). Last-synced status + health badge fed by 1.57. TanStack Query mutations for create/delete.

### Production hardening

- [ ] 1.68 [CODE] Per-tenant Redis rate limits. Extend `RateLimitWebFilter` to key on `rate_limit:tenant:<tenantId>:<route>` for authenticated routes (fall back to per-IP only for unauthenticated paths). Per-tenant limits configurable per plan tier from config (Phase 3 wires plan resolution; until then read from `application.yml` defaults). ITs assert 429 + `Retry-After` for both authenticated and unauthenticated paths.
- [ ] 1.69 [INFRA] Graceful shutdown across all JVM services. Set `server.shutdown=graceful` and `spring.lifecycle.timeout-per-shutdown-phase=30s` in every `application.yml`. IT verifies in-flight Kafka consumer commits complete before pod terminates and HTTP requests in flight finish cleanly.
- [ ] 1.70 [CODE] Resilience4j circuit breakers on every gateway `RestClient` (registry, topology, contract, intelligence). Expose breaker state in `/actuator/health`. IT proves the breaker opens on repeated downstream failures and surfaces a domain error mapped to the envelope.
- [ ] 1.71 [UI] Verify the root error boundary claimed in 0.37. If absent, add a React class component with `componentDidCatch`, display `traceId` from the caught `ApiError`, add a Vitest snapshot test. If present, add the `traceId` rendering + test.

### Phase 1 Gate

- [ ] [GATE] Registry CRUD, history, ownership, orphan detection, local auth, at least one OAuth provider, and Bearer auth are working.
- [ ] [GATE] Tenant OIDC is either working or explicitly deferred with a public note that preserves scope discipline.
- [ ] [GATE] Service auto-discovery: SCM and K8s syncs create `services` rows automatically with detected `tech_stack`, repository/commit metadata, K8s metadata, and initial `health_status`. The OwnershipResolvedConsumer no longer warns about missing services on the happy path.
- [ ] [GATE] Health: K8s pods set `health_status` on ADDED/MODIFIED; periodic prober updates `health_status` for services with `health_endpoint`. Catalog `health` filter returns non-empty results.
- [ ] [GATE] K8s, scheduler-driven, and webhook-driven SCM sync all succeed end-to-end; `last_sync_status` is reflected in the UI; Kafka events visible.
- [ ] [GATE] Password reset, route guards, sign out, profile management, Catalog (incl. service profile editing)/Teams/SCM-connections UIs operate against real APIs.
- [ ] [GATE] Per-tenant rate limits (429 + `Retry-After`), graceful shutdown, circuit breakers, and root error boundary all demonstrably in place.
- [ ] [GATE] Gateway proxies registry via Spring Cloud Gateway with trace propagation; gateway/registry/ingestion OpenAPI match the implemented envelope and auth behavior.
- [ ] [GATE] Phase 1 screens have passed `/impeccable audit`.
- [ ] [GATE] Minimum BIP set shipped: two ADR/design notes, two deep-dive writeups, and two short-form technical threads.

---

## Phase 2 — Topology service

### System design and UX — Phase 2

- [ ] 2.1 [UI] Run `/shape dependency graph` to produce a confirmed design brief for the D3 graph view, blast radius panel, SPOF findings, cycle warnings, drift overlay, and Risks page before writing any Phase 2 UI code.

### Topology foundation

- [ ] 2.2 [CODE] Topology service skeleton + persistence. Spring Data JDBC, Flyway, OTel, virtual threads. Migrations: `V001__create_dependencies.sql` (declared + observed edges, tenant isolation, self-edge guard, indexes), `V002__create_dependency_drifts.sql` (resolution fields), `V003__create_dependency_graph_view.sql` (materialized view + indexes + documented refresh strategy with debounce + advisory lock). Repositories for insert/update/delete, observed-edge enrichment, drift persistence. Hexagonal layout matching registry.

### Graph features (each one is a vertical: API + UI + tests + OpenAPI)

- [ ] 2.3 [CODE] Declared dependency CRUD + graph view. `POST/PUT/DELETE /v1/dependencies` + `GET /v1/graph` (nodes + edges, paginated for large tenants). D3 force-directed graph with zoom/pan/declared-vs-observed toggle/node selection (use `/impeccable craft dependency graph`), shadcn chrome around the canvas, TanStack Query for fetch, `Skeleton`/`Alert`-with-`traceId` states. Replace any static fixture data.
- [ ] 2.4 [CODE] Blast radius. Recursive CTE-backed `GET /v1/blast-radius/{serviceId}` with bounded depth + path-explosion guards. Upstream/downstream detail panels (use `/impeccable craft blast radius panel`) and on-graph highlighting. Fixture-based ITs.
- [ ] 2.5 [CODE] Cycle detection + SPOF heuristic + Risks page. `GET /v1/cycles` (normalized + deduplicated), `GET /v1/spofs` (documented fan-in threshold + redundancy assumptions), `GET /v1/risks` aggregating SPOF/cycle/drift/orphan into a paginated risk-summary list (`severity`, `type`, `affectedServices[]`, `title`, `description`). Risks page replaces `MOCK_RISKS` via TanStack Query; `Skeleton`/`Alert` states. Fixture-based ITs.
- [ ] 2.6 [CODE] Drift detection + resolve. `GET /v1/drifts` + `POST /v1/drifts/{id}/resolve`. Drift overlay on the graph + drift list view + resolution workflow wired via TanStack Query mutations. Fixture-based ITs.

### Event-driven graph + observed dependencies

- [ ] 2.7 [CODE] `topology-graph-builder` consumer. Consume `cartogra.registry.service.*` envelopes and update topology state idempotently (UUIDv5 dedupe + `(tenantId, eventId)` dedupe table). ITs prove service-registered creates a node, service-deregistered soft-deletes it, and a replayed envelope is a no-op.
- [ ] 2.8 [CODE] OTel span ingestion → observed edges. OTel Collector exports spans to `cartogra.observability.spans` (configure in `infra/docker-compose/otel-collector.yml`); `OtelSpanWorker` consumes and derives observed edges idempotently. Synthetic span fixtures must work without a live collector. ADR-0016 written and accepted inline in this PR; document the topic in `docs/architecture/kafka-topics.md`.
- [ ] 2.9 [CODE] Publish topology dependency lifecycle events (`dependency.declared/observed/removed/drift-detected`) with the shared Kafka envelope and `traceparent` propagation. Consumer side covered by the Phase 4 intelligence analyzer when it lands.

### Cross-cutting catalog and audit

- [ ] 2.10 [CODE] Full-text search on services + Catalog server-side search. PostgreSQL `to_tsvector` + `plainto_tsquery` on `name`, `description`, `metadata::text` with a GIN index migration on registry. Expose `?q=` on `GET /v1/services`. Catalog wires server-side search (replacing the client-side `q` from 1.63). IT covers ranking + multi-term queries.
- [ ] 2.11 [CODE] Server-side pagination. Pagination on `GET /v1/services`, `GET /v1/teams`, `GET /v1/audit-events` (`page`, `size`, `total` in envelope `meta`). Shadcn-based page controls + page-size selector + total count, wired into Catalog, Teams, and Timeline.
- [ ] 2.12 [CODE] Audit events end-to-end. Registry Flyway migration for `audit_events` (`id`, `tenant_id`, `entity_type`, `entity_id`, `action`, `actor_id`, `payload` JSONB, `created_at`; tenant isolation; RLS; GIN on `payload`). `AuditEventPort` plain-Java interface in `shared:common`. Registry adapter writes directly via JDBC; topology adapter publishes `cartogra.platform.audit.recorded` envelopes consumed by registry (idempotent on `eventId`). Wire the port into all mutating use cases in registry + topology (contract and intelligence wired in 3.2 and 4.2). Admin `GET /v1/audit-events` filterable by `entity_type`, `entity_id`, date range, actor (paginated, admin role). Timeline page wires to it; remove `MOCK_TIMELINE`. ADR-0017 written and accepted inline.

### Dashboard

- [ ] 2.13 [UI] Dashboard wired to real APIs. Service health summary via `GET /api/registry/v1/services` (count + health breakdown); top risks via `GET /api/topology/v1/risks?limit=4&severity=critical`; recent activity via `GET /api/registry/v1/audit-events?limit=5`. Remove all `MOCK_*` imports. Vitest covers loading/error/empty states.

### Observability, perf, docs, audit

- [ ] 2.14 [CODE] Topology observability metrics. Instrument MV refresh duration, consumer lag per group, graph query latency (Micrometer → Prometheus, exposed via `/actuator/prometheus`).
- [ ] 2.15 [TEST] Document graph render perf with the 20-service seed; capture target hardware + any debounce settings in `docs/architecture/topology-performance.md`.
- [ ] 2.16 [DOCS] Topology OpenAPI updated to match the implemented surface. Single ADR covering recursive CTEs + materialized views and the Kafka topic taxonomy + idempotency strategy for graph updates. ADR-0020 — service-discovered-to-graph flow uses event choreography (idempotent Kafka consumers) with DLQ replay (5.9) as the compensation mechanism, not orchestrated sagas with synchronous compensating actions; documents the trade-off vs. project-scope.md §3. ADR-0021 — actual persistence model is hexagonal use cases over Spring Data JDBC + Kafka publish per service, not the textbook CQRS / event-store described in scope §3; supersedes the CQRS framing in that section.
- [ ] 2.17 [UI] Run `/impeccable audit` on all Phase 2 screens before the phase gate.

### BIP — Phase 2

- [ ] 2.18 [BIP] Publish the recursive-CTE article after the graph algorithms have passing fixtures.
- [ ] 2.19 [BIP] Publish the blast-radius SQL thread after the blast-radius endpoint is reviewable.
- [ ] 2.20 [BIP] Publish the declared-vs-observed dependencies article after the toggle and overlay work.
- [ ] 2.21 [BIP] Publish the materialized-view-for-speed thread after the performance story is defensible.
- [ ] 2.22 [BIP] Publish the Kafka topic design article after topology topics and consumers are visible.
- [ ] 2.23 [BIP] Publish the UUIDv5/idempotency thread after the event flow is stable.
- [ ] 2.24 [BIP] Publish the event-naming/partitioning LinkedIn post after topic naming is settled.
- [ ] 2.25 [BIP] Record the optional live-impact-analysis video if it does not block the phase gate.

### Phase 2 Gate

- [ ] [GATE] Graph renders for tenant-sized dataset (≥20 services) without browser freeze on documented target hardware.
- [ ] [GATE] Blast radius, cycles, SPOF, drift endpoints return stable results on known fixtures; observed dependencies producible from a synthetic span path.
- [ ] [GATE] Risks, Timeline, Dashboard, Catalog (server FTS), Teams (paginated) all wired to real APIs.
- [ ] [GATE] Audit events captured across registry + topology mutating operations; admin endpoint paginated.
- [ ] [GATE] Consumer lag, MV refresh, and graph latency visible in Grafana.
- [ ] [GATE] Phase 2 screens have passed `/impeccable audit`.
- [ ] [GATE] Minimum BIP set shipped: one ADR/design note, two deep-dive posts, three short-form technical posts.

---

## Phase 3 — Contract guardian

### System design and UX — Phase 3

- [ ] 3.1 [UI] Run `/shape contract hub` to produce a confirmed design brief for the diff viewer, compatibility matrix heatmap, version timeline, breaking-check queue, API key management, and billing screens before writing any Phase 3 UI code.

### Contract foundation

- [ ] 3.2 [CODE] Contract service skeleton + persistence. JDBC, Flyway, OTel, virtual threads, hexagonal layout. Migrations: `V001__create_api_contracts.sql`, `V002__create_contract_versions.sql`, `V003__create_contract_consumers.sql`, `V004__create_contract_checks.sql`, `V005__create_outbox_events.sql`. Repositories with named-param SQL. Transactional boundary spans contract version write + outbox insert in the same DB transaction. Inject the `AuditEventPort` (defined in 2.12) and call it from every mutating use case (publish contract, publish version, approve/block check, register/revoke consumer); audit events publish to `cartogra.platform.audit.recorded` for the registry consumer to persist.

### Contract lifecycle (E2E)

- [ ] 3.3 [CODE] OpenAPI 3 + AsyncAPI 2 parse/validate/store. `POST /v1/contracts` and `POST /v1/contracts/{id}/versions` accept the spec, validate, canonicalize JSON, persist with `spec_hash`. Reject invalid specs with envelope-compliant errors. ITs cover valid + invalid + canonicalization parity.
- [ ] 3.4 [CODE] Contract list + diff viewer + version timeline. `GET /v1/contracts` (paginated) and `GET /v1/contracts/{id}/versions/{version}/diff` returns structured diff vs previous version. UI: list view replacing `MOCK_CONTRACTS`, side-by-side diff with required/added/removed highlights (use `/impeccable craft contract diff`), version timeline. TanStack Query everywhere; `Skeleton`/`Alert` states.
- [ ] 3.5 [CODE] Breaking-change engine + affected-consumers resolution. Rules: removed fields, type changes, added required fields, enum removals. Diff payload returns `is_breaking`, structured `changes[]`, `affected_consumers[]` resolved against `contract_consumers`. Golden tests cover compatible + breaking pairs across both spec types.
- [ ] 3.6 [CODE] Approve/block workflow + compatibility matrix UI. `POST /v1/checks/{id}/approve` and `/block` (admin role). Compatibility matrix heatmap + breaking-check queue (use `/impeccable craft contract matrix`); statuses update via TanStack Query mutations.

### Outbox + notifications (E2E)

- [ ] 3.7 [CODE] Transactional outbox + relay. Contract version writes + outbox events commit atomically. Relay polls `outbox_events`, publishes to Kafka with `traceparent`, applies exponential backoff and poison-message handling, routes terminal failures to `cartogra.platform.dead-letter`. IT covers contract write → outbox row → relay → Kafka → notification log.
- [ ] 3.8 [CODE] Notification rules + delivery. `notification_rules` + `notification_log` migrations + rule CRUD admin endpoints. Three outbound channels ship fully: Slack webhook, Microsoft Teams webhook (Adaptive Card), and Email via the existing Resend client. Each channel has delivery log entries, retry/backoff, and an IT that proves a breaking-check event reaches the channel mock. UI: notification rules management drawer in `/settings/notifications` (channel selector, target, event-type filter, enable toggle).

### CI surface (E2E)

- [ ] 3.9 [CODE] Tenant API keys. Migration in gateway (hashed at rest, per-key scopes `ci:check`, `webhooks:push`, `catalog:read`, etc.). Gateway admin endpoints to issue/list/revoke keys with scope selection. UI: API key management page (list + create with scope selector + revoke with confirmation). Scope validation enforced at every API-key-authenticated endpoint. ITs cover happy + insufficient-scope paths.
- [ ] 3.10 [CODE] `POST /ci/check`. API-key-only auth via `X-Cartogra-Api-Key`, envelope responses, documented blocking/passing semantics, scope `ci:check` required. Runs the breaking-change engine from 3.5. ITs cover compatible + breaking inputs and missing/expired/wrong-scope keys.
- [ ] 3.11 [CODE] GitHub Action + Azure Pipelines task against `/ci/check`. Both extensions live under `ci-extensions/`, accept tenant API key from the platform secret store, block merges on breaking changes, pass on compatible ones. Smoke tests run against example repos in both ecosystems.

### Spec discovery (E2E)

- [ ] 3.12 [CODE] Spec discovery end-to-end. Ingestion publishes `cartogra.ingestion.spec.discovered` envelopes (`{tenant_id, repo_full_name, file_path, content_sha256, content_b64}`) after each successful sync. Contract consumes idempotently keyed on `(tenant_id, file_path, content_sha256)` and feeds discovered specs through the parse/validate/store pipeline from 3.3. ADR-0018 written and accepted inline. Document the topic in `docs/architecture/kafka-topics.md`.
- [ ] 3.12a [CODE] Consumer relationship discovery (observed + manual). Extend `OtelSpanWorker` (2.8): when an observed edge resolves to a service that produces a known contract (match span peer URL against `api_contracts.spec_content` servers + base paths), insert/update `contract_consumers` with `evidence_type='observed'`, populate `consumed_version` from the calling service's pinned spec version (heuristic: the latest version known when the span was observed). Add admin `POST /v1/contracts/{id}/consumers` and `DELETE /v1/contracts/{id}/consumers/{consumerId}` for `evidence_type='manual'`. UI: in Contract detail, "Consumers" panel lists all consumers grouped by evidence type with add/remove for manual entries. ITs: trace span creates observed consumer; manual add creates manual consumer; both appear in compatibility matrix (3.6) and feed `affected_consumers` in breaking-change diffs (3.5).
- [ ] 3.12b [CODE] Field-level deprecation impact. `GET /v1/contracts/{id}/impact?field=<jsonpath>` returns the list of consumers (from 3.12a) whose `consumed_version` references the field. UI: in Contract detail diff viewer (3.4), each "removed" or "changed-type" field has a "Plan deprecation" action that opens a panel showing affected consumers and a copy-paste migration note. Backend resolves field references by JSONPath against `contract_versions.spec_content`. ITs: removing `paths./payments.post.requestBody.idempotency_key` returns the consumers that reference v2 (which has the field) and excludes consumers on v3 (which removed it).

### Billing (E2E — built only here because this is where it is used)

- [ ] 3.13 [CODE] Stripe billing backend. Gateway-owned Flyway migrations for `billing_plans` (`id`, `name`, `stripe_price_id`, `limits` JSONB), `tenant_subscriptions` (`tenant_id`, `stripe_customer_id`, `stripe_subscription_id`, `plan_id`, `status`, `current_period_end`), `billing_events` (`id`, `tenant_id`, `stripe_event_id`, `type`, `payload`, `processed_at`). `StripeClient` wraps the Stripe Java SDK with idempotency keys and secret from env. `POST /v1/billing/checkout` returns a Checkout Session URL; `POST /v1/billing/portal` returns a Customer Portal URL; `POST /v1/billing/stripe/webhook` reads raw body, verifies signature, handles `customer.subscription.updated/deleted` and `invoice.payment_failed`, updates `tenant_subscriptions`, returns 200 without an envelope. `PlanEnforcementFilter` reads the tenant plan via Redis cache (60 s TTL), injects `X-Plan-Tier` downstream, returns 402 when a request exceeds plan limits (service count, API key count). ITs cover webhook signature + happy/overage paths.
- [ ] 3.14 [UI] Billing settings page at `/settings/billing`. Current plan name + next renewal date + "Manage billing" button redirecting through `/v1/billing/portal`. Upgrade prompt on free tier. `/settings` index updated to include Billing.

### Lifecycle + GDPR

- [ ] 3.15 [CODE] GDPR tenant deletion. `DELETE /v1/admin/tenants/{id}/gdpr-erase` (admin role + re-authentication required): soft-delete the tenant, anonymize personal data in `users` and `audit_events`, publish a `tenant.erased` envelope, schedule a 30-day hard-delete job, return a signed erasure receipt. IT covers the happy path and the 30-day hard-delete trigger.

### Docs + audit

- [ ] 3.16 [DOCS] Contract OpenAPI updated to match the implemented surface. Single combined ADR set: outbox pattern, tenant lifecycle (trial → active → suspended → erased) with 30-day grace period + data residency + Stripe-cancellation interaction. Add a "Go live with billing" section to `docs/runbooks/deployment.md` (Stripe products/prices, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, webhook registration, Stripe CLI local testing, subscription-lapse handling).
- [ ] 3.17 [UI] Run `/impeccable audit` on all Phase 3 screens before the phase gate.

### BIP — Phase 3

- [ ] 3.18 [BIP] Publish the structured-diff thread after golden tests and diff payloads are stable.
- [ ] 3.19 [BIP] Publish the breaking-change algorithm article after the engine is reviewable.
- [ ] 3.20 [BIP] Publish the outbox ADR after the relay integration test passes.
- [ ] 3.21 [BIP] Publish the dual-write-bug article after the relay is demonstrably reliable.
- [ ] 3.22 [BIP] Publish the dual-marketplace article after both CI extensions run against the same backend.
- [ ] 3.23 [BIP] Publish the one-check-two-CI-systems thread after both extensions pass smoke tests.
- [ ] 3.24 [BIP] Publish the schema-diff UI demo after the Contract Hub is stable enough to show.
- [ ] 3.25 [BIP] Publish the compatibility-matrix story after the matrix is wired to real data.
- [ ] 3.26 [BIP] Publish the marketplace listing (or a documented blocker/timeline) when the packaging outcome is known.

### Phase 3 Gate

- [ ] [GATE] End-to-end contract flow works locally: new spec version → compatibility check → Kafka publication → notification log entry across all three channels (Slack + Teams + Email).
- [ ] [GATE] `POST /ci/check` works with tenant API keys only and returns the global envelope; GitHub Action and Azure Pipelines task demonstrate both failing and passing scenarios.
- [ ] [GATE] Spec discovery roundtrips ingestion → contract idempotently.
- [ ] [GATE] `contract_consumers` populates automatically from OTel observed traffic; manual admin overrides work; consumer list visible in Contract detail; compatibility matrix and `affected_consumers` driven by real data.
- [ ] [GATE] Field-level deprecation impact returns affected consumers for a given JSONPath; UI surfaces it from the diff viewer.
- [ ] [GATE] Stripe Checkout, Customer Portal, webhook receiver, and `PlanEnforcementFilter` work end-to-end.
- [ ] [GATE] GDPR erasure produces a signed receipt and schedules hard-delete.
- [ ] [GATE] Contract + audit (cross-service) flows: every mutating contract use case writes an audit event consumed by the registry.
- [ ] [GATE] Contract OpenAPI + CI-extension docs + Stripe runbook current.
- [ ] [GATE] Phase 3 screens have passed `/impeccable audit`.
- [ ] [GATE] Minimum BIP set shipped: one ADR, three substantive posts, three short-form updates; marketplace publish completed or publicly blocked with explanation.

---

## Phase 4 — Intelligence layer

### System design and UX — Phase 4

- [ ] 4.1 [UI] Run `/shape intelligence panel` to produce a confirmed design brief for the NL query panel, anti-pattern findings feed, health score trend, and weekly digest views before writing any Phase 4 UI code.

### Intelligence foundation

- [ ] 4.2 [CODE] Intelligence service skeleton + Claude client + guardrails. JDBC, Flyway, OTel, virtual threads, hexagonal layout. Migrations: `V001__create_analysis_runs.sql`, `V002__create_anti_pattern_findings.sql`, `V003__create_nl_query_log.sql`. Externalize prompt templates under `resources/prompts/`. Claude client abstraction wired to the cheapest current model, Redis cache + per-tenant rate limits, token/latency tracking persisted on every AI-backed request. Inject the `AuditEventPort` (from 2.12) and call it from every mutating use case (analyze run started/completed, finding acknowledged/resolved, query feedback recorded) so cross-service audits land in the registry's `audit_events` table. ADR — Claude integration, prompt structure, deterministic-evidence rules — written and accepted inline.

### NL query (E2E)

- [ ] 4.3 [CODE] NL query with SQL safety guardrails. Allowlisted sources only, read-only execution, parameter binding, row limits, timeout. `POST /v1/intelligence/query` returns `{answer, data?, generated_sql?, query_id}`. `POST /v1/intelligence/query/{id}/feedback` writes to `nl_query_log`. ITs cover safe + unsafe + prompt-formatting cases. NL query panel (use `/impeccable craft intelligence panel`) renders answers, optional generated-SQL disclosure, and feedback controls — wired via TanStack Query.
- [ ] 4.4 [TEST] NL query evaluation set with a documented pass/fail bar over fixtures or seed data; committed under `services/intelligence/src/test/resources/nl-eval/`.

### Anti-patterns + analysis (E2E)

- [ ] 4.5 [CODE] Deterministic anti-pattern detection. At least circular dependency, god service, and orphaned service. Output structured evidence (`cycle path`, `fan-in count`, `last commit at`). Fixture-based ITs assert deterministic findings on known graph shapes.
- [ ] 4.6 [CODE] LLM narrative layered on top of deterministic evidence — the model writes the explanation, never invents the evidence. `POST /v1/intelligence/analyze` + `GET /v1/intelligence/findings` + acknowledge/resolve mutations. Findings feed UI with severity badges + actions (use `/impeccable craft anti-pattern feed`), wired via TanStack Query.
- [ ] 4.6a [CODE] AI ownership suggestions for orphans. `POST /v1/intelligence/ownership-suggestions/{serviceId}` returns ranked candidate teams for an orphaned service. Deterministic input layer: aggregate git commit authors from the last 90 days (via existing SCM provider `getCommitHistory`), join to `users.team_id`, count per team; surface co-edit patterns with other services owned by each team. LLM layer ranks the candidates and writes a one-sentence rationale per candidate citing the evidence (never inventing names). Persist into `anti_pattern_findings` with `pattern_type='orphaned_service'` and `evidence.suggested_owners[]`. UI: each orphan in the findings feed shows a "Suggest owner" action that opens a panel with the ranked teams and an "Assign" mutation that calls `POST /v1/services/{id}/owner`.
- [ ] 4.6b [CODE] Anomaly detection. Nightly `@Scheduled` job in intelligence: compute per-tenant baselines for (a) dependency-change rate per service per week, (b) deploy cadence per service per week (from `services.last_deploy_at` history). Flag services exceeding ±3σ as `anti_pattern_findings` with `pattern_type='dependency_anomaly'` or `'deploy_anomaly'` and structured evidence (the metric, the baseline, the current value, the timeframe). LLM layer writes the narrative. Findings appear in the same feed as 4.6. ITs use fixture history data to trigger known anomalies.

### Health + digest (E2E)

- [ ] 4.7 [CODE] Health score endpoint + persisted trend. Trend view (use `/impeccable craft health score`) wired via TanStack Query.
- [ ] 4.8 [CODE] Weekly digest endpoint + persisted runs. Latest-digest view wired via TanStack Query.

### Events + docs + audit

- [ ] 4.9 [CODE] Publish intelligence analysis request and result events to Kafka with the shared envelope and `traceparent` propagation.
- [ ] 4.10 [DOCS] Update `docs/api/intelligence.openapi.yaml` for the full implemented surface.
- [ ] 4.11 [UI] Run `/impeccable audit` on all Phase 4 screens before the phase gate.

### BIP — Phase 4

- [ ] 4.12 [BIP] Publish the NL-over-PostgreSQL article after the guarded query flow works.
- [ ] 4.13 [BIP] Publish the token-and-latency tracking thread after those metrics are visible.
- [ ] 4.14 [BIP] Publish the evidence-first anti-pattern thread after deterministic findings are stable.
- [ ] 4.15 [BIP] Publish the trust-model thread after the deterministic-plus-LLM split is documented.
- [ ] 4.16 [BIP] Publish the LLM-for-infrastructure-intelligence article after the UI and API are demoable.
- [ ] 4.17 [BIP] Record the NL query demo video after the query path is stable enough to show live.

### Phase 4 Gate

- [ ] [GATE] NL queries are guarded by documented safety rules and abuse limits; evaluation set passes the bar.
- [ ] [GATE] At least three anti-pattern types produce findings on fixtures or seed data with deterministic evidence + LLM narrative.
- [ ] [GATE] Orphan services produce ranked ownership suggestions with cited evidence; assigning from the suggestion writes back to `services.owner_team_id`.
- [ ] [GATE] Anomaly detection produces findings for dependency and deploy patterns on fixture history.
- [ ] [GATE] Health score + digest available via API and visible in the UI.
- [ ] [GATE] Token usage, latency, and quota behavior observable.
- [ ] [GATE] Phase 4 screens have passed `/impeccable audit`.
- [ ] [GATE] Minimum BIP set shipped: one ADR/design note, two substantive articles, two short-form posts.

---

## Phase 5 — Production and launch

### System design and UX — Phase 5

- [ ] 5.1 [UI] Run `/shape operations view` to produce a confirmed design brief for the Operations view, digest page, and admin/settings screens before writing any Phase 5 UI code.

### Seed + demo

- [ ] 5.2 [INFRA] Acme Fintech seed data + idempotent loader + sandbox cleanup. `seed/seed-data.json` covers 20 services across 5 teams sourced from both GitHub and Azure DevOps and includes 3 orphans, 2 cycles, 1 god service (12 dependents), declared-vs-observed drift, and 1 pending breaking change. Loader exercises the real public APIs idempotently. Sandbox cleanup automation reaps demo tenants per a configured TTL.
- [ ] 5.3 [CODE] Guest read-only mode. Gateway issues a special short-lived JWT with `roles=["guest"]` and `tid=<acme-tenant-id>`; no anonymous auth path. Code audit verifies `@PreAuthorize` (member or admin) is present on every mutating endpoint across registry, topology, contract, intelligence — fix any gaps. IT proves a guest token can read demo data and is rejected on every write. ADR-0019 written and accepted inline.

### Deployment packaging

- [ ] 5.4 [INFRA] Helm umbrella chart + per-service charts. Required probes, security context, resources, non-root defaults; staging + production values committed.
- [ ] 5.5 [INFRA] Terraform modules + at least one concrete staging environment + remote state (S3 backend, DynamoDB lock table, encryption, bucket versioning).
- [ ] 5.6 [INFRA] Staging deploy CI + post-deploy smoke tests. Standalone Gradle task (`:smoke:test`) runs after every staging deploy: hits `/actuator/health/ready` on all services, executes one authenticated request per service, asserts envelope conformance. CI gates on this suite.
- [ ] 5.7 [CODE] K8s NetworkPolicy + gateway service-token validation. NetworkPolicy restricts registry/topology/contract/intelligence to gateway-namespace ingress only. Gateway signs a short-lived `X-Gateway-Token` (HS256, 30 s TTL, separate secret from user JWTs); each downstream service rejects requests missing or invalid tokens via a shared filter in `shared:common`. IT proves the bypass attempt is rejected.

### Observability + DLQ

- [ ] 5.8 [INFRA] Staging OTel/Tempo/Loki/Prometheus/Grafana stack via Helm (`infra/k8s/infra/helm/`, `install.sh`). Grafana dashboard provisioning JSON templates under `infra/docker-compose/grafana/dashboards/` — one per service (request rate, error rate, p95 latency, JVM heap) plus a platform overview (Kafka consumer lag, Postgres connections, Redis hit rate). Auto-load on first `docker compose up`. Alerts for consumer lag, elevated error rate, and critical-path failures.
- [ ] 5.9 [CODE] DLQ topic + replay admin API + audit logging. Failed messages land in `cartogra.platform.dead-letter` with original-topic/partition/offset/error metadata. Admin endpoint replays selected DLQ messages and writes `audit_events` rows. IT proves a poison message routes to DLQ and replay succeeds.

### Operations + extensions

- [ ] 5.10 [UI] Operations view (use `/impeccable craft operations view`). Connector health, recent platform events, lag visibility, observability links. Wired to real APIs.
- [ ] 5.11 [UI] Run `/impeccable audit` on all Phase 5 screens before the phase gate.
- [ ] 5.12 [CODE] Batch service creation. `POST /v1/services/batch` accepts an array of service definitions, creates all-or-nothing in a single transaction, returns per-item status. IT covers happy + partial-failure rollback.
- [ ] 5.13 [CODE] Tenant export/import. `GET /v1/tenants/{id}/export` returns a ZIP of services + teams + dependencies + contracts. `POST /v1/tenants/{id}/import` accepts the ZIP, supports `dryRun=true`, is idempotent on re-import. IT covers round-trip parity.

### E2E + performance

- [ ] 5.14 [TEST] Playwright E2E for guest browse, graph exploration, contract matrix, and one NL query path. Runs in CI against staging.
- [ ] 5.15 [TEST] k6 scripts + thresholds for graph reads, impact analysis, and `/ci/check`; tune indexes, MV refresh behavior, and critical-path performance based on results; commit before/after numbers under `perf/results/`.

### Docs + domain + launch

- [ ] 5.16 [DOCS] Docusaurus docs site (ADR index, onboarding guide, API references, cross-linked runbooks); expand deployment + incident runbooks for staging, alerts, DLQ replay, and operator workflows.
- [ ] 5.17 [INFRA] Finalize `cartogra.dev` domain, ingress, TLS, launch checklist.

### BIP — Phase 5

- [ ] 5.18 [BIP] Publish the observability-stack article after dashboards and alerts are reviewable.
- [ ] 5.19 [BIP] Publish the Flyway-in-a-multi-service-monorepo article after the deployment story is settled.
- [ ] 5.20 [BIP] Publish the full data-architecture retrospective after the final shape is stable.
- [ ] 5.21 [BIP] Publish the ADR-index/build-in-public reflection after the docs site is live.
- [ ] 5.22 [BIP] Publish the ship-retrospective thread near launch.
- [ ] 5.23 [BIP] Publish the real-incident DLQ thread only if it is true and useful.
- [ ] 5.24 [BIP] Record the architecture walkthrough video if it adds value without delaying launch.
- [ ] 5.25 [BIP] Publish the public launch post with the live demo and how-to-try-it path.

### Phase 5 Gate

- [ ] [GATE] Staging is reproducible from docs; seed loader populates the Acme demo cleanly.
- [ ] [GATE] Guest demo scenarios match the documented Acme Fintech story; mutating endpoints reject guest tokens.
- [ ] [GATE] Observability answers "what is broken" and "which consumer is lagging" within a few minutes.
- [ ] [GATE] Playwright + k6 gates pass in CI with documented thresholds.
- [ ] [GATE] NetworkPolicy + gateway service-token validation verified end-to-end.
- [ ] [GATE] DLQ replay flow audited end-to-end.
- [ ] [GATE] Phase 5 screens have passed `/impeccable audit`.
- [ ] [GATE] Minimum BIP set shipped: observability writeup, data-architecture retrospective, launch post, and at least one retrospective artifact.

---

## Phase 6 — Future Research

> **Status:** Placeholder — not scheduled. Work in this phase will be evaluated after production launch when real bottlenecks can be measured.

This phase captures research topics deferred from earlier phases. Nothing here blocks Phases 1–5. Each item will be evaluated on its merits before any implementation is committed.

### gRPC for internal service communication

The current architecture uses REST (`RestClient` via Spring Cloud Gateway) for all synchronous inter-service calls. gRPC was originally designed in and removed before Phase 1 because the REST approach is simpler, already proven, and sufficient at current scale.

Potential benefits to evaluate:

- Compile-time contract enforcement via `.proto` files in a `shared:contracts` module
- Binary protocol efficiency on high-throughput internal paths (e.g., topology graph traversal)
- Server-side streaming for watch/push APIs without SSE
- Strong typing enforced at the infrastructure boundary

Research tasks (if we decide to pursue):

- [ ] 6.1 [DOCS] Benchmark REST vs gRPC latency on the topology graph-read and registry list hot paths under k6 load; document results and whether REST overhead is measurable.
- [ ] 6.2 [DOCS] Evaluate `spring-grpc 1.x` maturity, community support, and Spring Boot 4 compatibility as of evaluation date.
- [ ] 6.3 [CODE] If benchmarks justify it, prototype `RegistryGrpcService` + `RegistryGrpcClient` on a spike branch; write ADR before merging anything.
- [ ] 6.4 [DOCS] Decision gate: adopt gRPC only if REST latency or throughput cannot be solved by caching, query optimization, or connection pooling alone.

### Other potential research areas

- [ ] 6.5 [DOCS] Evaluate Avro schemas + Confluent Schema Registry for Kafka payloads if JSON overhead becomes measurable.
- [ ] 6.6 [DOCS] Evaluate SSE or WebSocket for real-time graph updates if Kafka-push to browser becomes a requirement.
- [ ] 6.7 [DOCS] Evaluate multi-region active-active if the platform grows beyond a single-region deployment.

### Phase 6 Gate

- [ ] [GATE] Each research item produces either a rejected ADR (with documented reasoning) or an accepted ADR with a concrete implementation plan for a future phase.
