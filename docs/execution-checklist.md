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

- Keep this file chronological. Add new tasks where they should be executed, not at the end of a phase.
- Keep user-story scope, rationale, and phase boundaries in [plan.md](c:/Users/allan/projects/cartogra/docs/plan.md).
- When a task changes product scope or architecture, update the relevant ADR or source doc in the same PR.
- When a task produces a BIP artifact, publish it immediately after the implementation or doc it depends on.

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
- [ ] 1.51 [BIP] Publish the enterprise-SCM angle on LinkedIn after both providers are visible in the catalog.
- [ ] 1.52 [BIP] Publish screenshots plus OpenAPI links after the catalog UI is stable enough to demo.
- [ ] 1.53 [BIP] Publish the dual-mode auth article (httpOnly cookies + Bearer tokens in a Spring Security 7 reactive gateway) after JWT issuance is stable end-to-end.
- [ ] 1.54 [BIP] Publish the Redis rate-limiting article (token buckets, per-tenant key isolation, 429 behavior) after rate limiting is tested and observable.
- [ ] 1.55 [CODE] Wire `KubernetesWorker` to publish a `cartogra.registry.sync.command` Kafka message (`EventEnvelope` + `traceparent`) whenever a K8s `Service` is ADDED or MODIFIED in a watched namespace. Resolve tenant ID from the service's `cartogra.io/tenant-id` namespace label; use a configured synthetic connection UUID (`ingestion.workers.k8s.connection-id`) as the connection identity. Document the label contract in `docs/runbooks/local-development.md`.
- [ ] 1.56 [TEST] Add `KubernetesWorkerIT` using `io.fabric8:kubernetes-server-mock` (no real cluster or `kind` required) + `@EmbeddedKafka`: simulate a `Service` ADDED event in a watched namespace and assert a `cartogra.registry.sync.command` message is published with the correct `tenant_id`, `traceparent` header, and `providerType = "kubernetes"`. Add `testImplementation("io.fabric8:kubernetes-server-mock:7.0.0")` to `services/ingestion/build.gradle.kts`.

### Automatic sync — periodic scheduler

- [ ] 1.57 [INFRA] Add ingestion Flyway `V003__create_scm_connection_cache.sql` — a local materialized table of SCM connection records (`id`, `tenant_id`, `provider_type`, `config` JSONB, `sync_interval_minutes`, `last_synced_at`, `deleted_at`) populated by consuming registry lifecycle events. Add a unique index on `(tenant_id, id)` and a partial index on connections due for re-sync (`last_synced_at + sync_interval_minutes * interval '1 min' <= now()`).
- [ ] 1.58 [CODE] Consume `scm_connection.created`, `scm_connection.updated`, and `scm_connection.deleted` registry lifecycle events in ingestion and upsert the local `scm_connection_cache` table. This decouples ingestion from registry REST calls at sync time and is the prerequisite for both the scheduler and webhook registration.
- [ ] 1.59 [CODE] Add a `@Scheduled` task in ingestion (interval configurable via `ingestion.sync.poll-interval`, default 15 min) that queries `scm_connection_cache` for connections due for re-sync, acquires a Postgres advisory lock (`pg_try_advisory_lock`) to prevent duplicate runs under horizontal scaling, then publishes one `cartogra.registry.sync.command` `EventEnvelope` per due connection with `traceparent` propagation. Update `last_synced_at` atomically before publishing.
- [ ] 1.60 [TEST] Integration test for the scheduler: seed two connections in `scm_connection_cache` (one due, one not due), trigger the scheduled method directly, assert exactly one `sync.command` published to `@EmbeddedKafka`.

### Webhook-driven sync

- [ ] 1.61 [CODE] Extend the `ScmProvider` SPI with four webhook methods: `registerWebhook(config, targetUrl)`, `deregisterWebhook(config, externalWebhookId)`, `verifyWebhookSignature(request, rawBody, config) → boolean`, and `isRelevantWebhookEvent(request) → boolean`. Add default no-op implementations so existing providers compile. Implement full webhook registration/deregistration in `GitHubProvider` (org-level hook via `POST /orgs/{org}/hooks`) and `AzureDevOpsProvider` (service hook via `POST /_apis/hooks/subscriptions`). Store the returned `external_id` and a hashed secret in `scm_webhooks` on registration; mark `deleted_at` on deregistration. Trigger registration by consuming the `scm_connection.created` event (task 1.58).
- [ ] 1.62 [CODE] Add a single `WebhookController` in ingestion at `POST /webhooks/{providerType}/{connectionId}`. This route is excluded from gateway JWT auth and the response envelope. Read the raw request body before deserialization via `ContentCachingRequestWrapper`. Load connection config from `scm_connection_cache`, dispatch to `providers.get(providerType).verifyWebhookSignature()` — return 401 on failure with no body. On success, call `isRelevantWebhookEvent()` to skip pings and irrelevant event types, then publish `cartogra.registry.sync.command` and return 202. Update `scm_webhooks.last_received_at` on every successful verification.
- [ ] 1.63 [CODE] Implement `verifyWebhookSignature` in `GitHubProvider` (HMAC-SHA256 over raw body, `X-Hub-Signature-256` header) and `AzureDevOpsProvider` (shared-secret header). Secret is read from `ScmConnectionConfig` — never from env vars. Adding a future provider (GitLab, Bitbucket) requires only a new `ScmProvider` implementation; no changes to the controller or routing.
- [ ] 1.64 [TEST] Add `WebhookControllerIT` using `@EmbeddedKafka` and WireMock: correctly-signed GitHub push payload → assert `sync.command` published; tampered GitHub payload → assert 401 and no Kafka message; valid AzDO payload → assert `sync.command`; unknown `providerType` → assert 404; GitHub ping event → assert 202 but no Kafka message.
- [ ] 1.65 [DOCS] Update `docs/api/ingestion.openapi.yaml` for `POST /webhooks/{providerType}/{connectionId}` (no auth, no envelope, 202 Accepted, 401 on bad signature, 404 on unknown provider). Add a "Webhook setup" section to the deployment runbook covering: required public URL, ngrok/Cloudflare Tunnel for local dev, GitHub org-level vs repo-level hook trade-offs, AzDO service hook setup, and how to add a future provider (implement `ScmProvider`, `verifyWebhookSignature` handles the new format automatically).

### Infrastructure hardening and missing flows

- [ ] 1.66 [CODE] Publish `scm_connection.created`, `scm_connection.updated`, and `scm_connection.deleted` lifecycle events from registry with the shared Kafka envelope and `traceparent` propagation. These events are the prerequisite for tasks 1.58 (connection cache consumer), 1.59 (scheduler), and 1.61 (webhook registration).
- [ ] 1.66a [DOCS] One-line decision: the `sync.completed` topic name. The producer at `SyncResultProducer` already publishes to `cartogra.ingestion.sync.completed`; the original 1.67 wording referenced `cartogra.registry.sync.completed`. Accept the producer's existing name (events belong to the domain that produced them) and update `docs/architecture/kafka-topics.md` to make it canonical. No code change.
- [ ] 1.67 [CODE] Consume `cartogra.ingestion.sync.completed` events in registry (topic name fixed by 1.66a) to update `scm_connections.last_synced_at` and `last_sync_status`. Depends on the prerequisite migration 1.67a that adds those columns to `scm_connections`. This closes the ingestion → registry feedback loop.
- [ ] 1.67a [INFRA] Add registry Flyway migration `V0NN__add_last_sync_to_scm_connections.sql` (zero-padded, next available version) adding `last_synced_at TIMESTAMPTZ` and `last_sync_status TEXT` columns to `scm_connections`. Verify against the existing `V004__create_scm_connections.sql` that these columns are not already present.
- [ ] 1.68 [CODE] Implement the password reset flow in gateway: `POST /auth/forgot-password` sends a time-limited reset token via Resend; `POST /auth/reset-password` validates the token and updates the password hash. Apply the same rate limiting as other `/auth/*` endpoints.
- [ ] 1.69 [CODE] Extend `RateLimitWebFilter` to use per-tenant Redis keys (`rate_limit:tenant:<tenantId>:<route>`) on all authenticated routes, falling back to per-IP only for unauthenticated paths. Per-tenant limits must be configurable per plan tier.
- [ ] 1.70 [INFRA] Configure graceful shutdown for all JVM services: `server.shutdown=graceful` and `spring.lifecycle.timeout-per-shutdown-phase=30s` in each service's `application.yml`. Verify that in-flight Kafka consumer commits complete before the pod terminates.
- [ ] 1.71 [CODE] Add Resilience4j circuit breakers on all gateway `RestClient` calls to downstream services (registry, topology, contract, intelligence). Expose circuit-breaker state in each service's `/actuator/health` response.
- [ ] 1.72 [UI] Verify and implement the root error boundary claimed done in task 0.37. Audit `frontend/src/` for an `ErrorBoundary` component; if absent, implement it as a React class component with `componentDidCatch`, display the `traceId` from the caught `ApiError`, and add a Vitest snapshot test.

### UI–Backend integration

- [ ] 1.73 [UI] Add TanStack Router route guards: protect all routes except `/login`, `/register`, `/verify-email`, `/forgot-password`, `/reset-password`, and `/oauth-handoff`. Use `beforeLoad` in `__root.tsx` (or a route group) to check `useAuthStore.isAuthenticated`; if false after the session-restore effect settles, throw `redirect({ to: '/login', search: { redirect: location.pathname } })`; after successful login/OAuth, navigate to the `redirect` search param or fall back to `/dashboard`.
- [ ] 1.74 [UI] Add a "Sign out" action in `AppLayout` sidebar bottom section (next to the user avatar): calls `POST /api/auth/logout` via `apiFetch`, on success calls `useAuthStore.clearAuth()` and navigates to `/login`. Handle the API error case inline — never leave the user in an inconsistent auth state.
- [ ] 1.75 [UI] Craft the Forgot Password screen at `/forgot-password` and Reset Password screen at `/reset-password`: `POST /api/auth/forgot-password` submits the email; success state shows "check your inbox"; `POST /api/auth/reset-password` accepts `token` (from query param) + `newPassword`; on success navigate to `/login`. Both routes excluded from the auth guard (1.73). Depends on backend task 1.68.
- [ ] 1.76 [UI] Craft the SCM Connections management screen at `/settings/scm-connections`: list connections via `GET /api/registry/v1/scm-connections` (TanStack Query); add a new connection with a drawer/dialog form (provider selector, name, PAT/token field); delete with a confirmation dialog using `DELETE /api/registry/v1/scm-connections/{id}`; show last-synced status and sync health badge. Use TanStack Query mutations for create and delete. Design was shape-briefed in 1.2 but this screen was never built.
- [ ] 1.77 [UI] Wire the Catalog list and detail pages to the real registry API: replace `MOCK_SERVICES` with a TanStack Query `useQuery` on `GET /api/registry/v1/services` (pass `team`, `health`, `q` query params from filter state); replace the detail route loader with `GET /api/registry/v1/services/{id}`; show `Skeleton` loading and `Alert` with `traceId` on error; remove all `MOCK_SERVICES` imports from `catalog.tsx` and `catalog.$serviceId.tsx`. Client-side tech-stack and SCM-provider filtering remains until 2.39 full-text search lands server-side.
- [ ] 1.78 [UI] Wire the Teams page to the real registry API: replace `MOCK_TEAMS` with a TanStack Query `useQuery` on `GET /api/registry/v1/teams`; show `Skeleton` loading and `Alert` with `traceId` on error; remove `MOCK_TEAMS` import from `teams.tsx`.
- [ ] 1.79 [CODE] Add `PUT /v1/auth/userinfo` endpoint in gateway: accepts `{ name, email }`, validates email uniqueness, updates the user record, returns the updated user in the response envelope. Requires authentication (httpOnly cookie or Bearer). This is the backend counterpart to the profile update UI in 1.80.
- [ ] 1.80 [UI] Add a Settings layout with a Profile page at `/settings/profile`: display name, email, and role from `useAuthStore`; allow name/email updates via `PUT /api/auth/userinfo` (1.79) using TanStack Forms; surface a "Change password" link pointing to `/forgot-password`. Create a `/settings` index page that lists available sections (Profile, SCM Connections; Billing added in 3.45). Add `/settings/profile` and `/settings/scm-connections` as file-based sub-routes.

### Phase 1 Gate

- [ ] [GATE] Registry CRUD, history, ownership, orphan detection, sync initiation, local auth, at least one OAuth provider, and Bearer auth are working.
- [ ] [GATE] Tenant OIDC is either working or explicitly deferred with a public note that preserves scope discipline.
- [ ] [GATE] Both SCM sync workers succeed in scripted or runbooked tests; Kafka events are visible.
- [ ] [GATE] 429 behavior is smoke-tested and documented.
- [ ] [GATE] Gateway proxies registry via Spring Cloud Gateway with trace propagation.
- [ ] [GATE] Gateway and registry OpenAPI docs match the implemented envelope and auth behavior.
- [ ] [GATE] Phase 1 screens have passed `/impeccable audit`; no blocking accessibility or envelope-handling regressions.
- [ ] [GATE] Minimum BIP set shipped: two ADR/design notes, two deep-dive writeups, and two short-form technical threads.

---

## Phase 2 — Topology service

### System design and UX — Phase 2

- [ ] 2.1 [UI] Run `/shape dependency graph` to produce a confirmed design brief for the D3 graph view, blast radius panel, SPOF findings, and cycle warnings before writing any graph UI code.

### Schema and persistence

- [ ] 2.2 [CODE] Create the topology module skeleton with Spring Data JDBC, Flyway, OTel, and virtual-thread settings.
- [ ] 2.3 [INFRA] Add topology Flyway `V001__create_dependencies.sql` with declared and observed edge fields, tenant isolation, and the self-edge guard.
- [ ] 2.4 [INFRA] Add topology Flyway `V002__create_dependency_drifts.sql` with tenant isolation and resolution fields.
- [ ] 2.5 [INFRA] Add topology Flyway `V003__create_dependency_graph_view.sql` with indexes and a documented refresh strategy.
- [ ] 2.6 [CODE] Implement repositories for dependency insert/update/delete, observed-edge enrichment, and drift persistence.
- [ ] 2.7 [CODE] Decide and document the materialized-view refresh strategy, including any debounce or advisory-lock behavior.

### Graph algorithms and APIs

- [ ] 2.8 [CODE] Implement the declared-dependency CRUD API and graph read endpoints with the shared response envelope.
- [ ] 2.9 [CODE] Implement recursive CTEs for blast radius with bounded depth and path-explosion guards.
- [ ] 2.10 [CODE] Implement cycle detection with normalized and deduplicated cycle output.
- [ ] 2.11 [CODE] Implement the SPOF heuristic with a documented fan-in threshold and any redundancy assumptions.
- [ ] 2.11a [CODE] Add `GET /api/topology/v1/risks` endpoint: aggregates SPOF findings, cycle detections, drift alerts, and orphan detections into a paginated risk-summary list. Each item has `severity` (critical/warning/info), `type` (spof/cycle/drift/orphan), `affectedServices[]`, `title`, and `description`. This is the data source for the Risks page (2.28b) and the Dashboard risk section (2.28d).
- [ ] 2.12 [CODE] Implement drift detection plus the resolve endpoint.
- [ ] 2.13 [TEST] Add fixed graph fixtures and tests for graph reads, blast radius, cycle detection, SPOF, and drift resolution.
- [ ] 2.14 [DOCS] Update `docs/api/topology.openapi.yaml` to match the implemented graph, drift, and analysis endpoints.
- [ ] 2.15 [DOCS] Write the ADR that documents PostgreSQL recursive CTEs and materialized views for the graph layer.
- [ ] 2.16 [BIP] Publish the recursive-CTE article after the graph algorithms have passing fixtures.
- [ ] 2.17 [BIP] Publish the blast-radius SQL thread after the blast-radius endpoint is reviewable.

### Event-driven graph updates and observed dependencies

- [ ] 2.18 [CODE] Implement the `topology-graph-builder` consumer so registry events update topology state idempotently.
- [ ] 2.19 [CODE] Publish topology dependency events with shared Kafka envelope and trace propagation.
- [ ] 2.19a [DOCS] Write ADR-0016 — `OtelSpanWorker` ingestion path. The current 2.20 wording does not specify how topology receives span data. Recommend: the OTel Collector exports spans to a Kafka topic `cartogra.observability.spans` (configured in `infra/docker-compose/otel-collector.yml`); topology consumes that topic. Document the topic in `docs/architecture/kafka-topics.md`. ADR `Accepted` only after Allan signs off.
- [ ] 2.20 [CODE] Implement `OtelSpanWorker` per ADR-0016: consume `cartogra.observability.spans`, derive observed edges idempotently, and persist them via the topology repositories. Synthetic span fixtures must work without a live collector.
- [ ] 2.21 [TEST] Add fixture-based integration tests that prove a span batch produces observed edges and mode toggling works.
- [ ] 2.22 [CODE] Instrument MV refresh duration, consumer lag, and graph query latency metrics.
- [ ] 2.23 [DOCS] Write the ADR or design note that captures the Kafka topic taxonomy and idempotency strategy for graph updates.
- [ ] 2.24 [BIP] Publish the Kafka topic design article after topology topics and consumers are visible.
- [ ] 2.25 [BIP] Publish the UUIDv5/idempotency thread after the event flow is stable.

### Graph UI

- [ ] 2.26 [UI] Craft the D3 graph with zoom, pan, declared-vs-observed toggle, node selection, and drift overlays using `/impeccable craft dependency graph`; use D3 for the canvas and shadcn/ui for all surrounding chrome.
- [ ] 2.27 [UI] Craft the upstream/downstream detail panels and blast-radius highlighting using `/impeccable craft blast radius panel`.
- [ ] 2.28 [UI] Run `/impeccable audit` on all Phase 2 screens before the phase gate.

### Phase 2 UI–Backend wiring

- [ ] 2.28a [UI] Wire the D3 dependency graph view to the real topology API: replace any static fixture data with TanStack Query calls to `GET /api/topology/v1/graph` (nodes + edges) and `GET /api/topology/v1/blast-radius/{id}`; pass the graph response shape to the D3 renderer from 2.26; show a `Skeleton` overlay during load and `Alert` with `traceId` on error.
- [ ] 2.28b [UI] Wire the Risks page to the real topology risk findings: replace `MOCK_RISKS` with a TanStack Query `useQuery` on `GET /api/topology/v1/risks` (2.11a); show `Skeleton` loading and `Alert` with `traceId` on error; remove `MOCK_RISKS` import from `risks.tsx`.
- [ ] 2.28c [UI] Wire the Timeline page to real audit events: replace `MOCK_TIMELINE` with a TanStack Query `useQuery` on `GET /api/registry/v1/audit-events` (paginated, from 2.37); show `Skeleton` loading and `Alert` with `traceId` on error; remove `MOCK_TIMELINE` import from `timeline.tsx`.
- [ ] 2.28d [UI] Wire the Dashboard to real API data: replace the `MOCK_SERVICES` service health summary with `GET /api/registry/v1/services` (count + health breakdown); replace the risk section with `GET /api/topology/v1/risks?limit=4&severity=critical`; replace recent activity with `GET /api/registry/v1/audit-events?limit=5`; remove all `MOCK_*` imports from `dashboard.tsx`. Depends on 2.28b and 2.28c.

- [ ] 2.29 [TEST] Measure and document graph render behavior for the 20-service seed target and capture the target hardware used.
- [ ] 2.30 [BIP] Publish the declared-vs-observed dependencies article after the toggle and overlay work.
- [ ] 2.31 [BIP] Publish the materialized-view-for-speed thread after the performance story is defensible.
- [ ] 2.32 [BIP] Publish the event-naming/partitioning LinkedIn post after topic naming is settled.
- [ ] 2.33 [BIP] Record the optional live-impact-analysis video if it does not block the phase gate.

### Audit logging, search, and billing foundation

- [ ] 2.33a [DOCS] Write ADR-0017 — Audit events: owning service and `AuditEventPort` shape. The 2.34 wording does not specify which service owns the table; the 2.35 wording places a JDBC writer in `shared:common`, which violates the zero-Spring-deps rule. Recommend: registry owns `audit_events`; `shared:common` defines a plain-Java `AuditEventPort` interface; each service ships its own adapter (registry writes directly via JDBC; topology/contract/intelligence publish a `cartogra.audit.recorded` Kafka envelope consumed by registry). ADR `Accepted` only after Allan signs off.
- [ ] 2.34 [INFRA] Add the registry Flyway migration `V0NN__create_audit_events.sql` (next-available version) per ADR-0017: generic audit table (`id`, `tenant_id`, `entity_type`, `entity_id`, `action`, `actor_id`, `payload` JSONB, `created_at`) with tenant isolation, RLS, and a GIN index on `payload`.
- [ ] 2.35 [CODE] Implement the `AuditEventPort` and adapters per ADR-0017: plain-Java port in `shared:common`; JDBC adapter in registry (direct write); Kafka-publish adapter in topology and contract (publish `cartogra.audit.recorded`). Wire the port into all mutating use cases in registry, topology, and contract (service create/update/delete, dependency change, contract version upload).
- [ ] 2.35a [CODE] Implement the registry consumer for `cartogra.audit.recorded` so cross-service audit envelopes from topology and contract land in the `audit_events` table. Idempotent on `eventId`; tenant filter enforced.
- [ ] 2.36 [DOCS] Write the ADR documenting the generic `audit_events` table pattern versus per-entity `changed_by` columns; cross-reference the GDPR deletion task (3.41).
- [ ] 2.37 [CODE] Add `GET /audit-events` admin endpoint in registry with filtering by `entity_type`, `entity_id`, date range, and actor; paginated; admin role required.
- [ ] 2.38 [CODE] Implement TOTP-based MFA in gateway: `POST /auth/mfa/enable` returns a TOTP secret and QR code URI; `POST /auth/mfa/verify` validates the TOTP code and marks MFA active; `POST /auth/mfa/disable` requires current TOTP code. Enforce TOTP verification on login when MFA is enabled.
- [ ] 2.39 [CODE] Add full-text search on services in registry using PostgreSQL `to_tsvector` + `plainto_tsquery` on `name`, `description`, and `metadata::text`. Add a GIN index on the tsvector column and expose a `?q=` query parameter on the services list endpoint.
- [ ] 2.40 [UI] Implement server-side pagination UI components (page controls, page-size selector, total count) and wire them to the Catalog list and any other list endpoint that returns more than 20 rows.
- [ ] 2.41 [INFRA] Add Flyway migration(s) for Stripe billing tables: `billing_plans` (`id`, `name`, `stripe_price_id`, `limits` JSONB), `tenant_subscriptions` (`tenant_id`, `stripe_customer_id`, `stripe_subscription_id`, `plan_id`, `status`, `current_period_end`), and `billing_events` (`id`, `tenant_id`, `stripe_event_id`, `type`, `payload` JSONB, `processed_at`). Use soft delete and `TIMESTAMPTZ` throughout.
- [ ] 2.42 [CODE] Implement `StripeClient` in gateway infrastructure: wraps the Stripe Java SDK, always passes idempotency keys, reads `STRIPE_SECRET_KEY` from env vars (never hardcoded), and maps Stripe exceptions to domain exceptions at the infrastructure boundary.
- [ ] 2.43 [CODE] Add `POST /billing/stripe/webhook` in gateway: read raw body before deserialization; verify Stripe signature using `STRIPE_WEBHOOK_SECRET` from env; handle `customer.subscription.updated`, `customer.subscription.deleted`, and `invoice.payment_failed` events; update `tenant_subscriptions` accordingly; return 200 with no envelope (Stripe ignores response body).
- [ ] 2.44 [CODE] Add `PlanEnforcementFilter` in gateway that reads the tenant's current plan from `tenant_subscriptions` (via Redis cache, TTL 60 s), injects an `X-Plan-Tier` header downstream, and returns 402 when a request exceeds plan limits (service count, API key count, intelligence query count).
- [ ] 2.45 [DOCS] Write the billing ADR capturing the Stripe + `PlanEnforcementFilter` approach; add a "Stripe setup" section to `docs/runbooks/deployment.md` covering `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, webhook endpoint registration, and local testing with the Stripe CLI.

### Phase 2 Gate

- [ ] [GATE] The graph renders for a tenant-sized dataset without browser freeze on the documented target hardware.
- [ ] [GATE] Blast radius, cycles, SPOF, and drift endpoints return stable results on known fixtures.
- [ ] [GATE] Observed dependencies can be produced from a trace fixture or synthetic span path.
- [ ] [GATE] Consumer lag and topology metrics are visible in observability tooling.
- [ ] [GATE] Phase 2 screens have passed `/impeccable audit`.
- [ ] [GATE] Minimum BIP set shipped: one ADR/design note, two deep-dive posts, and three short-form technical posts.

---

## Phase 3 — Contract guardian

### System design and UX — Phase 3

- [ ] 3.1 [UI] Run `/shape contract hub` to produce a confirmed design brief for the side-by-side diff viewer, compatibility matrix heatmap, version timeline, and CI check detail screens before writing any contract UI code.

### Contract data model and CRUD

- [ ] 3.2 [CODE] Create the contract module skeleton with JDBC, Flyway, and transaction boundaries for contract plus outbox writes.
- [ ] 3.3 [INFRA] Add contract Flyway `V001__create_api_contracts.sql`.
- [ ] 3.4 [INFRA] Add contract Flyway `V002__create_contract_versions.sql`.
- [ ] 3.5 [INFRA] Add contract Flyway `V003__create_contract_consumers.sql`.
- [ ] 3.6 [INFRA] Add contract Flyway `V004__create_contract_checks.sql`.
- [ ] 3.7 [INFRA] Add contract Flyway `V005__create_outbox_events.sql`.
- [ ] 3.8 [CODE] Implement OpenAPI 3 and AsyncAPI 2 parse/validate/store flows with canonical JSON storage.
- [ ] 3.9 [CODE] Implement contract CRUD, version history, consumers, and contract check endpoints with the shared response envelope.
- [ ] 3.10 [TEST] Add valid and invalid spec tests plus versioning and canonicalization coverage.

### Breaking-change engine and compatibility workflows

- [ ] 3.11 [CODE] Implement breaking-change rules for removed fields, type changes, added required fields, and enum removals.
- [ ] 3.12 [CODE] Implement structured `changes` output plus `affected_consumers` resolution.
- [ ] 3.13 [CODE] Implement approve/block workflows for breaking checks.
- [ ] 3.14 [TEST] Add golden tests for compatible and breaking spec pairs.
- [ ] 3.15 [BIP] Publish the structured-diff thread after the golden tests and diff payloads are stable.
- [ ] 3.16 [BIP] Publish the breaking-change algorithm article after the engine is reviewable.

### Outbox, notifications, and CI surface

- [ ] 3.17 [CODE] Implement the transactional outbox so contract version writes and outbox events commit atomically.
- [ ] 3.18 [CODE] Implement the outbox relay with backoff, publish tracking, and poison-message handling.
- [ ] 3.19 [CODE] Publish schema and check events with shared Kafka envelope and trace propagation.
- [ ] 3.20 [CODE] Implement notification rules, delivery logs, and at least one proven outbound channel path (Slack or Teams webhook).
- [ ] 3.21 [TEST] Add integration coverage proving contract write → outbox row → relay → Kafka → notification log.
- [ ] 3.22 [DOCS] Write the ADR for the outbox pattern and cross-link it from deployment or incident docs.
- [ ] 3.23 [BIP] Publish the outbox ADR after the relay integration test passes.
- [ ] 3.24 [BIP] Publish the dual-write-bug article after the relay is demonstrably reliable.
- [ ] 3.24a [DOCS] One-line decision (no full ADR): tenant API keys belong to **gateway** since the gateway is the sole token issuer per ADR-0010. The migration in 3.25 lives in gateway's Flyway dir, and the admin APIs in 3.26 are gateway endpoints proxied to the relevant tenant table. Update 3.25 and 3.26 wording to make this explicit.
- [ ] 3.25 [INFRA] Add the tenant API key migration; store API keys hashed at rest.
- [ ] 3.26 [CODE] Implement admin APIs to issue, list, and revoke tenant API keys.
- [ ] 3.26a [UI] Craft the API key management UI: list active keys with scopes and expiry, create a key with scope selection, and revoke a key with a confirmation dialog. Wire to the admin APIs from 3.26.
- [ ] 3.26b [CODE] Extend the tenant API key model with per-key scopes (e.g. `ci:check`, `webhooks:push`, `catalog:read`). Validate scopes at `POST /ci/check` and any other API-key-authenticated endpoint; reject requests where the key's scopes do not cover the requested operation.
- [ ] 3.27 [CODE] Implement `POST /ci/check` with API-key-only auth (`X-Cartogra-Api-Key`), envelope responses, and documented blocking semantics.
- [ ] 3.28 [CODE] Implement the GitHub Action extension against `/ci/check`.
- [ ] 3.29 [CODE] Implement the Azure Pipelines task against `/ci/check`.
- [ ] 3.29a [DOCS] Write ADR-0018 — Spec discovery transport. Ingestion can read OpenAPI/AsyncAPI files via the existing `ScmProvider.getFileContents(...)`, but the 3.30 wording doesn't say how the discovered spec reaches the contract service. Recommend: Kafka topic `cartogra.ingestion.spec.discovered` with payload `{tenant_id, repo_full_name, file_path, content_sha256, content_b64}`; contract consumes idempotently keyed on `(tenant_id, file_path, content_sha256)`. Document in `docs/architecture/kafka-topics.md`. ADR `Accepted` only after Allan signs off.
- [ ] 3.30 [CODE] Implement spec discovery per ADR-0018: ingestion publishes `cartogra.ingestion.spec.discovered` envelopes after each successful sync; contract consumes idempotently and feeds the discovered spec through the same parse/validate/store pipeline used by 3.8. Idempotency key: `(tenant_id, file_path, content_sha256)`.
- [ ] 3.31 [TEST] Add workflow smoke tests for both CI extensions and idempotency tests for spec discovery.
- [ ] 3.32 [DOCS] Add example GitHub Actions and Azure Pipelines usage to docs and update `docs/api/contract.openapi.yaml`.
- [ ] 3.33 [BIP] Publish the dual-marketplace article after both extension paths run against the same backend contract.
- [ ] 3.34 [BIP] Publish the one-check-two-CI-systems thread after both extensions pass smoke tests.

### Contract Hub UI

- [ ] 3.35 [UI] Craft the side-by-side diff viewer with required, added, and removed highlights using `/impeccable craft contract diff`.
- [ ] 3.36 [UI] Craft the compatibility matrix heatmap, version timeline, and breaking-check queue using `/impeccable craft contract matrix`.
- [ ] 3.37 [UI] Run `/impeccable audit` on all Phase 3 screens before the phase gate.
- [ ] 3.37a [UI] Wire the Contracts page to the real contract API: replace `MOCK_CONTRACTS` with a TanStack Query `useQuery` on `GET /api/contract/v1/contracts`; wire the diff viewer to `GET /api/contract/v1/contracts/{id}/diff`, the version timeline to version history endpoints, and the breaking-check queue to the check endpoints from 3.9; show `Skeleton` loading and `Alert` with `traceId` on error; remove `MOCK_CONTRACTS` import from `contracts.tsx`.
- [ ] 3.38 [BIP] Publish the schema-diff UI demo after the Contract Hub is stable enough to show.
- [ ] 3.39 [BIP] Publish the compatibility-matrix story after the matrix is wired to real data.
- [ ] 3.40 [BIP] Publish the marketplace listing or a documented blocker/timeline immediately when the packaging outcome is known.

### Billing customer flow and GDPR

- [ ] 3.41 [CODE] Implement GDPR tenant deletion (`DELETE /admin/tenants/{id}/gdpr-erase`): soft-delete the tenant, anonymize personal data in users and audit_events, publish a `tenant.erased` Kafka event, schedule a 30-day hard-delete job, and return a signed erasure receipt. Admin role and re-authentication required.
- [ ] 3.42 [DOCS] Write the ADR for tenant lifecycle management — trial → active → suspended → erased — including the 30-day grace period, data residency boundaries, and how Stripe subscription cancellation interacts with the erasure flow.
- [ ] 3.43 [CODE] Implement Stripe Checkout: `POST /billing/checkout` creates a Stripe Checkout Session for the selected plan and returns the session URL; the frontend redirects the user to Stripe-hosted payment. On success, Stripe fires `customer.subscription.created`, which the webhook receiver (2.43) processes.
- [ ] 3.44 [CODE] Implement Stripe Customer Portal passthrough: `POST /billing/portal` creates a Stripe Customer Portal session URL; the frontend redirects the user there for plan upgrades, downgrades, and payment method changes. No custom billing UI is required for Phase 3.
- [ ] 3.45 [UI] Add a Billing settings page at `/settings/billing`: show current plan name, next renewal date, and a "Manage billing" button that triggers the Customer Portal flow (3.44). Show an upgrade prompt when the tenant is on the free tier.
- [ ] 3.46 [DOCS] Add a "Go live with billing" section to `docs/runbooks/deployment.md`: how to create Stripe products and prices, map `stripe_price_id` to `billing_plans`, configure the webhook endpoint in the Stripe dashboard, verify webhook delivery with the Stripe CLI, and what to do when a subscription lapses.

### Phase 3 Gate

- [ ] [GATE] End-to-end contract flow works locally: new spec version, compatibility check, Kafka publication, and notification log entry.
- [ ] [GATE] `POST /ci/check` works with tenant API keys only and returns the global response envelope.
- [ ] [GATE] GitHub Action and Azure Pipelines task both demonstrate failing and passing scenarios.
- [ ] [GATE] Contract OpenAPI and CI-extension docs are current.
- [ ] [GATE] Phase 3 screens have passed `/impeccable audit`.
- [ ] [GATE] Minimum BIP set shipped: one ADR, three substantive posts, and three short-form updates; marketplace publish completed or publicly blocked with explanation.

---

## Phase 4 — Intelligence layer

### System design and UX — Phase 4

- [ ] 4.1 [UI] Run `/shape intelligence panel` to produce a confirmed design brief for the NL query panel, anti-pattern findings feed, health score trend, and weekly digest views before writing any intelligence UI code.

### Service, storage, and guardrails

- [ ] 4.2 [CODE] Create the intelligence module skeleton with JDBC, Flyway, prompt-loading support, and the Claude client abstraction.
- [ ] 4.3 [INFRA] Add intelligence Flyway `V001__create_analysis_runs.sql`.
- [ ] 4.4 [INFRA] Add intelligence Flyway `V002__create_anti_pattern_findings.sql`.
- [ ] 4.5 [INFRA] Add intelligence Flyway `V003__create_nl_query_log.sql`.
- [ ] 4.6 [CODE] Externalize prompt templates for NL query, anti-pattern analysis, and digest generation under `resources/prompts/`.
- [ ] 4.7 [CODE] Add Redis caching and per-tenant rate limits for all AI-backed flows.
- [ ] 4.8 [CODE] Record token counts, latency, and execution status on every AI-backed request.
- [ ] 4.9 [DOCS] Write the ADR or design note for Claude integration, prompt structure, and deterministic-evidence rules.

### Natural-language query flow

- [ ] 4.10 [CODE] Implement SQL safety guardrails: allowlisted sources only, read-only execution, parameter binding, row limits, and timeout handling.
- [ ] 4.11 [CODE] Implement `POST /intelligence/query` returning `answer`, optional `data`, optional `generated_sql`, and a durable `query_id`.
- [ ] 4.12 [CODE] Implement `POST /intelligence/query/{id}/feedback` and store the result in `nl_query_log`.
- [ ] 4.13 [TEST] Add unit and integration coverage for safe vs unsafe query handling and prompt formatting.
- [ ] 4.14 [DOCS] Update `docs/api/intelligence.openapi.yaml` for query and feedback endpoints.
- [ ] 4.15 [BIP] Publish the NL-over-PostgreSQL article after the guarded query flow works.
- [ ] 4.16 [BIP] Publish the token-and-latency tracking thread after those metrics are visible.

### Analysis jobs, findings, and health score

- [ ] 4.17 [CODE] Implement deterministic anti-pattern detection for at least circular dependency, god service, and orphaned service.
- [ ] 4.18 [CODE] Layer LLM narrative on top of deterministic evidence instead of letting the model invent unsupported claims.
- [ ] 4.19 [CODE] Implement `POST /intelligence/analyze`, findings list, acknowledge/resolve flows, health score, and digest retrieval.
- [ ] 4.20 [CODE] Publish intelligence analysis request and result events.
- [ ] 4.21 [TEST] Add fixture coverage proving known anti-patterns produce findings and health-score updates.
- [ ] 4.22 [BIP] Publish the evidence-first anti-pattern thread after deterministic findings are stable.
- [ ] 4.23 [BIP] Publish the trust-model thread after the deterministic-plus-LLM split is documented.

### Intelligence UI and operator visibility

- [ ] 4.24 [UI] Craft the NL query panel with answer rendering, optional generated-SQL disclosure, and feedback controls using `/impeccable craft intelligence panel`.
- [ ] 4.25 [UI] Craft the findings feed with severity badges and acknowledge/resolve actions using `/impeccable craft anti-pattern feed`.
- [ ] 4.26 [UI] Craft the health-score trend and latest digest views using `/impeccable craft health score`.
- [ ] 4.27 [UI] Run `/impeccable audit` on all Phase 4 screens before the phase gate.
- [ ] 4.28 [TEST] Create an evaluation set of NL questions against fixtures or seed data and document the pass/fail bar.
- [ ] 4.29 [DOCS] Finish `docs/api/intelligence.openapi.yaml` for the full implemented surface.
- [ ] 4.30 [BIP] Publish the LLM-for-infrastructure-intelligence article after the UI and API are demoable.
- [ ] 4.31 [BIP] Record the NL query demo video after the query path is stable enough to show live.

### Phase 4 Gate

- [ ] [GATE] NL queries are guarded by documented safety rules and abuse limits.
- [ ] [GATE] At least three anti-pattern types produce findings on fixtures or seed data with deterministic evidence.
- [ ] [GATE] Health score and digest are available via API and visible in the UI.
- [ ] [GATE] Token usage, latency, and quota behavior are observable enough to support demo operations.
- [ ] [GATE] Phase 4 screens have passed `/impeccable audit`.
- [ ] [GATE] Minimum BIP set shipped: one ADR/design note, two substantive articles, and two short-form posts.

---

## Phase 5 — Production and launch

### System design and UX — Phase 5

- [ ] 5.1 [UI] Run `/shape operations view` to produce a confirmed design brief for the Operations view, digest page, and admin/settings screens before writing any Phase 5 UI code.

### Seed data and demo hardening

- [ ] 5.2 [INFRA] Complete `seed/seed-data.json` for the Acme Fintech scenario set, including orphans, cycles, drift, and a pending breaking change.
- [ ] 5.3 [CODE] Finalize the seed loader so it is idempotent and exercises the real public APIs.
- [ ] 5.3a [DOCS] Write ADR-0019 — Guest enforcement mechanism. ADR-0013 defers guest demo access to Phase 5 without specifying the enforcement shape. Recommend: gateway issues a special short-lived JWT with `roles=["guest"]` and `tid=<acme-tenant-id>`; method-level security on every mutating endpoint requires `member` or `admin`; no anonymous auth path. Acceptance for 5.4 then becomes a code audit verifying `@PreAuthorize` is present on every write across registry, topology, contract, intelligence. ADR `Accepted` only after Allan signs off.
- [ ] 5.4 [CODE] Enforce guest read-only mode per ADR-0019: implement the gateway-issued guest JWT path; audit every mutating endpoint across backend services for `@PreAuthorize` requiring at least `member`. Sandbox isolation for authenticated demo users stays in scope.
- [ ] 5.5 [CODE] Add sandbox cleanup automation.

### Deployment packaging and staging

- [ ] 5.6 [INFRA] Build the Helm umbrella chart and per-service charts with required probes, security context, resources, and non-root defaults.
- [ ] 5.7 [INFRA] Add staging and production Helm values.
- [ ] 5.8 [INFRA] Finalize Terraform modules and at least one concrete staging environment.
- [ ] 5.9 [INFRA] Configure remote Terraform state with S3 backend, DynamoDB lock table, encryption, and bucket versioning.
- [ ] 5.10 [INFRA] Add staging deployment CI plus post-deploy smoke tests.
- [ ] 5.10a [TEST] Implement a post-deploy smoke test suite as a standalone Gradle task (`:smoke:test`) that runs after every staging deploy: call `/actuator/health/ready` on all services, execute one authenticated request per service, and assert all responses match the documented envelope. CI must gate on this suite after each deploy.

### Observability, DLQ, and operations

- [ ] 5.11 [INFRA] Finalize the staging OTel, Tempo, Loki, Prometheus, and Grafana path (K8s Helm values in `infra/k8s/infra/helm/`; run `install.sh`).
- [ ] 5.11a [INFRA] Add Grafana dashboard provisioning JSON templates under `infra/docker-compose/grafana/dashboards/`: one per service (request rate, error rate, p95 latency, JVM heap) plus one platform-wide overview (Kafka consumer lag, Postgres connections, Redis hit rate). Dashboards must appear automatically on first `docker compose up` via `grafana.ini` provisioning.
- [ ] 5.12 [INFRA] Add alerts for consumer lag, elevated error rate, and critical-path failures.
- [ ] 5.13 [CODE] Implement the dead-letter topic path and replay admin API with audit logging.
- [ ] 5.14 [UI] Craft the Operations view for connector health, recent platform events, lag visibility, and observability links using `/impeccable craft operations view`.
- [ ] 5.15 [UI] Run `/impeccable audit` on all Phase 5 screens before the phase gate.
- [ ] 5.16 [DOCS] Expand deployment and incident runbooks for staging, alerts, DLQ replay, and operator workflows.
- [ ] 5.16a [CODE] Add a batch service creation endpoint (`POST /services/batch`) that accepts an array of service definitions and creates all or none within a single transaction. Return per-item status in the response. Add a matching integration test.
- [ ] 5.16b [CODE] Add export (`GET /tenants/{id}/export`) and import (`POST /tenants/{id}/import`) endpoints for the full service catalog (services, teams, dependencies, contracts as a ZIP archive). Implement idempotent import with a `dryRun=true` query parameter. Supports disaster recovery and environment cloning.
- [ ] 5.17 [BIP] Publish the observability-stack article after dashboards and alerts are reviewable.
- [ ] 5.18 [BIP] Publish the Flyway-in-a-multi-service-monorepo article after the deployment story is settled.

### Docs site, end-to-end tests, and launch

- [ ] 5.19 [DOCS] Build the Docusaurus docs site with ADR index, onboarding guide, API references, and cross-linked runbooks.
- [ ] 5.20 [TEST] Add Playwright E2E coverage for guest browse, graph exploration, contract matrix, and one NL query path.
- [ ] 5.21 [TEST] Add k6 scripts and thresholds for graph reads, impact analysis, and `/ci/check`.
- [ ] 5.22 [CODE] Tune indexes, MV refresh behavior, and critical-path performance based on k6 results.
- [ ] 5.23 [INFRA] Finalize the `cartogra.dev` domain, ingress, TLS, and launch checklist.
- [ ] 5.24 [BIP] Publish the full data-architecture retrospective after the final shape of the system is stable.
- [ ] 5.25 [BIP] Publish the ADR-index/build-in-public reflection after the docs site is live.
- [ ] 5.26 [BIP] Publish the ship-retrospective thread near launch.
- [ ] 5.27 [BIP] Publish the real-incident DLQ thread only if it is true and useful.
- [ ] 5.28 [BIP] Record the architecture walkthrough video if it adds value without delaying launch.
- [ ] 5.29 [BIP] Publish the public launch post with the live demo and how-to-try-it path.
- [ ] 5.X [INFRA] Add K8s NetworkPolicy rules so registry, topology, contract, and intelligence accept inbound traffic only from the gateway namespace — no direct external access to backend service ports.
- [ ] 5.Y [CODE] Implement gateway service-token validation in all proxied services (registry, topology, contract, intelligence): gateway signs a short-lived X-Gateway-Token (HS256, 30 s TTL, separate secret from user JWTs); each downstream service rejects requests missing or invalid tokens. Write a shared filter in `shared:common` to avoid repeating the validation logic in every service.

### Phase 5 Gate

- [ ] [GATE] Staging is reproducible from docs, and the seed loader populates the Acme demo cleanly.
- [ ] [GATE] Guest demo scenarios match the documented Acme Fintech story.
- [ ] [GATE] Observability answers "what is broken" and "which consumer is lagging" within a few minutes for a maintainer.
- [ ] [GATE] Playwright and k6 gates pass in CI with documented thresholds.
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
