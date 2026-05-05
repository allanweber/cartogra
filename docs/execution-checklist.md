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

- [ ] 0.4 [DOCS] Finalize root governance files: `LICENSE`, `README.md`, `CONTRIBUTING.md`, `.editorconfig`, `.gitignore`, `CODEOWNERS`, and branch-protection expectations.
- [ ] 0.5 [DOCS] Add GitHub issue templates for bug report, feature request, and architecture discussion plus a PR template with tests, docs, tenancy, and observability checks.
- [ ] 0.6 [DOCS] Create the GitHub Project board with columns and milestones for Phases 0 through 5.

### Build and shared modules

- [ ] 0.7 [CODE] Scaffold `shared:contracts` Gradle module; configure the protobuf plugin and gRPC code generation so all `.proto` files in this module compile to Java stubs that downstream services consume. This module is a prerequisite for any gRPC wiring in Phase 1+.
- [ ] 0.8 [CODE] Finish `shared:common` with the Kafka event envelope, shared IDs and value objects, and a Spring-free API error model.
- [ ] 0.9 [CODE] Finish `shared:test-support` with reusable Postgres and Kafka test helpers for Testcontainers-based integration tests.
- [ ] 0.10 [CODE] Harden `services:registry` for Spring Data JDBC, explicit Flyway, virtual threads, and health readiness wiring.
- [ ] 0.11 [CODE] Harden `services:gateway` for Spring Cloud Gateway, OTel tracing, `traceparent` propagation, `X-Trace-Id` response header handling, and virtual threads.
- [ ] 0.12 [CODE] Harden `services:ingestion` for Spring Data JDBC, explicit Flyway, virtual threads, and a health-only Phase 0 stub.
- [ ] 0.13 [CODE] Add one sample endpoint that proves the HTTP response envelope and returns `{ "data": ..., "traceId": "..." }` plus the matching `X-Trace-Id` header.

### Database baseline

- [ ] 0.14 [INFRA] Add registry Flyway `V001__create_tenants.sql` with UUID PKs, `TIMESTAMPTZ`, soft delete, and RLS.
- [ ] 0.15 [INFRA] Add registry Flyway `V002__create_teams.sql` with tenant isolation, indexes, soft delete, and RLS.
- [ ] 0.16 [INFRA] Add registry Flyway `V003__create_users.sql` with tenant isolation, indexes, soft delete, and RLS.
- [ ] 0.17 [INFRA] Add registry Flyway `V004__create_scm_connections.sql` with tenant isolation, indexes, soft delete, and RLS.
- [ ] 0.18 [TEST] Add a Testcontainers smoke test that boots Postgres, applies registry migrations cleanly, and fails on any ordering or checksum issue.

### Local runtime and containers

- [ ] 0.19 [INFRA] Finalize `infra/docker-compose/docker-compose.yml` with PostgreSQL 16, Redis-compatible cache, Kafka-compatible broker, OTel collector, Jaeger, healthchecks, and named volumes.
- [ ] 0.20 [INFRA] Finalize `infra/docker-compose/otel-collector.yml` to receive OTLP and export to Jaeger.
- [ ] 0.21 [INFRA] Finalize `infra/docker-compose/docker-compose.dev.yml` with local observability helpers such as a Kafka/Redpanda console.
- [ ] 0.22 [INFRA] Add a root `.env.example` covering local ports, DB credentials, Kafka, Redis, and OTel settings.
- [ ] 0.23 [INFRA] Add multi-stage Dockerfiles for registry, gateway, and ingestion with Temurin 25, non-root user, and `MaxRAMPercentage=75`.
- [ ] 0.24 [INFRA] Add a frontend Dockerfile stub or document why frontend image creation is intentionally deferred beyond Phase 0.

### Observability and CI

- [ ] 0.25 [CODE] Enable structured JSON logging in gateway, registry, and ingestion with the same trace ID that appears in OTel spans and HTTP responses.
- [ ] 0.26 [TEST] Verify one traced request end-to-end: incoming `traceparent`, service logs, response body `traceId`, and `X-Trace-Id` must all match.
- [ ] 0.27 [INFRA] Finalize `.github/workflows/ci.yml` for Java 25, `./gradlew build`, tests, container build checks, and Trivy failure on `HIGH` or `CRITICAL` findings.
- [ ] 0.28 [BIP] Publish the launch thread once the stack compiles cleanly and the core services boot together.
- [ ] 0.29 [BIP] Publish the data-model and Kafka sketch thread immediately after the baseline migrations and architecture diagrams land.

### Frontend shell

- [x] 0.30 [UI] Initialize TanStack Start with TypeScript strict mode, ESLint (flat config), Prettier, and Tailwind CSS configured from `DESIGN.md` tokens.
- [x] 0.31 [UI] Run `npx shadcn@latest init` and install the base component set: Button, Card, Alert, AlertDescription, Skeleton, Input, Badge, Separator, Sheet, and Tooltip.
- [x] 0.32 [UI] Set up TanStack Router file-based routing with placeholder route files for Catalog (`/catalog`), Graph (`/graph`), Contracts (`/contracts`), Intelligence (`/intelligence`), and Operations (`/ops`).
- [x] 0.33 [UI] Implement `apiFetch<T>` in `frontend/src/lib/api.ts` with Cartogra envelope parsing, `ApiError` class carrying `code`, `message`, and `traceId`, and `X-Trace-Id` header extraction.
- [x] 0.34 [UI] Set up Zustand store skeleton in `frontend/src/stores/` with a tenant-scoped slice pattern; do not use Context for global state.
- [x] 0.35 [UI] Configure vitest with happy-dom and React Testing Library; add `npm run test` and `npm run typecheck` scripts.
- [x] 0.36 [UI] Create `frontend/.env.local` with `VITE_API_BASE_URL=http://localhost:8080`; add `frontend/.env.local.example` to the repo and `.env.local` to `.gitignore`.
- [x] 0.37 [UI] Craft the app shell, `AppLayout` component with sidebar navigation, root error boundary, and 404 page using `/impeccable craft app shell`. All outputs must use named exports, shadcn/ui primitives, and Tailwind for layout.
- [ ] 0.38 [UI] Run `/impeccable audit` on all Phase 0 screens before the phase gate; address any blocking accessibility or responsiveness findings.
- [x] 0.39 [INFRA] Add frontend CI tasks: `pnpm install --frozen-lockfile`, ESLint check, TypeScript type-check, and `vitest --passWithNoTests`.

### Core documentation

- [ ] 0.40 [DOCS] Finalize `docs/adr/TEMPLATE.md` and `docs/adr/README.md` as the ADR entry point.
- [ ] 0.41 [DOCS] Finalize `docs/architecture/system-overview.md`, `docs/architecture/data-model.md`, and `docs/architecture/kafka-topics.md` with current Phase 0 status markers.
- [ ] 0.42 [DOCS] Finalize `docs/api/gateway.openapi.yaml` and `docs/api/registry.openapi.yaml` with shared response-envelope components, error schema, and `X-Trace-Id` header documentation.
- [ ] 0.43 [DOCS] Keep `docs/api/topology.openapi.yaml`, `docs/api/contract.openapi.yaml`, and `docs/api/intelligence.openapi.yaml` as explicit forward-looking stubs with Phase ownership notes.
- [ ] 0.44 [DOCS] Create initial `docs/runbooks/local-development.md`, `docs/runbooks/deployment.md`, and `docs/runbooks/incident-response.md` stubs.
- [ ] 0.45 [BIP] Publish the blog post on why service catalogs drift after the docs stubs and architecture framing are reviewable.
- [ ] 0.46 [BIP] Publish the ADRs/build-in-public article right after the ADR template and index merge.
- [ ] 0.47 [BIP] Publish a GitHub or blog update showing the README diagram and project board once both are visible.
- [ ] 0.48 [BIP] Record the optional short problem-framing video if it does not slow the phase gate.

### Phase 0 Gate

- [ ] [GATE] `DESIGN.md` and `PRODUCT.md` exist and contain enough detail to guide Phase 1 screen crafting.
- [ ] [GATE] `shared:contracts` Gradle module compiles with the protobuf plugin wired; no proto files inside service modules.
- [ ] [GATE] `./gradlew build` and CI are green, and the Trivy failure policy is documented.
- [ ] [GATE] `docker compose up` succeeds with healthy gateway, registry, ingestion, database, cache, broker, and observability dependencies.
- [ ] [GATE] Flyway clean migrate passes from a blank database in automation.
- [ ] [GATE] At least one endpoint proves the response envelope and `X-Trace-Id` contract.
- [ ] [GATE] Frontend installs and runs locally; CI lint, type-check, and test jobs pass.
- [ ] [GATE] Minimum BIP set shipped: launch thread, data-model/Kafka thread, service-catalog blog, and ADR article.

---

## Phase 1 — Gateway MVP auth + Registry

### System design and UX

- [ ] 1.1 [UI] Run `/shape login` and `/shape register` to produce confirmed design briefs for Login, Register, Forgot password, and Verify email screens before writing any auth UI code.
- [ ] 1.2 [UI] Run `/shape catalog home` and `/shape scm connections` to produce confirmed design briefs for the Catalog list, Catalog detail, and SCM connections screens.

### Registry domain and APIs

- [ ] 1.3 [CODE] Establish hexagonal package boundaries for registry: `api`, `application`, `domain`, `infrastructure`, and `config`.
- [ ] 1.4 [INFRA] Add registry Flyway `V005__create_services.sql` with tenant isolation, soft delete, JSONB metadata, and the required indexes.
- [ ] 1.5 [INFRA] Add registry Flyway `V006__create_services_history.sql` for temporal snapshots.
- [ ] 1.6 [INFRA] Add registry Flyway `V007__create_scm_webhooks.sql` with tenant isolation and lifecycle fields.
- [ ] 1.7 [CODE] Implement JDBC repositories for services, teams, and SCM connections using explicit SQL for filtering, search, and history access.
- [ ] 1.8 [CODE] Implement service CRUD, owner assignment, health summary, orphan detection, and point-in-time history use cases.
- [ ] 1.9 [CODE] Persist a `services_history` snapshot on every material service change.
- [ ] 1.10 [CODE] Add REST controllers and `@RestControllerAdvice` handlers that always emit the documented envelope and stable error codes.
- [ ] 1.11 [TEST] Add Testcontainers integration coverage for CRUD, history queries, orphan detection, and envelope/header behavior.
- [ ] 1.12 [DOCS] Write the ADR for PostgreSQL plus recursive CTEs over a graph database.
- [ ] 1.13 [BIP] Publish the PostgreSQL-vs-graph-DB ADR after it merges.
- [ ] 1.14 [DOCS] Write the ADR or design note for Spring Data JDBC as the default persistence model.
- [ ] 1.15 [BIP] Publish the Spring Data JDBC rationale immediately after it merges.

### Gateway authentication, authorization, and proxying

- [ ] 1.16 [INFRA] Extend the users schema for password hash, email verification token and expiry, `auth_provider`, `auth_subject`, and any session or token metadata needed for MVP auth.
- [ ] 1.17 [CODE] Implement Spring Security 7 on the gateway with local email/password registration, OTP verification, login, refresh, logout, and `userinfo` endpoints.
- [ ] 1.18 [CODE] Add the Resend client with environment-based API key management and a test-mode strategy that suppresses real sends in CI.
- [ ] 1.19 [CODE] Implement Google and GitHub OAuth start/callback flows through the gateway.
- [ ] 1.20 [CODE] Implement tenant OIDC configuration storage plus an admin API; keep UI optional if the phase needs to defer it.
- [ ] 1.21 [CODE] Issue secure httpOnly JWT cookies for browsers and Bearer tokens for non-browser clients; reject unverified users from accessing tenant data.
- [ ] 1.22 [CODE] Enforce RBAC for `viewer`, `member`, and `admin` routes using `@EnableMethodSecurity` and `@PreAuthorize`; verify that tenant boundaries are derived from the authenticated principal, never inbound headers.
- [ ] 1.23 [CODE] Strip client-supplied `X-Tenant-Id` from all inbound requests; inject gateway-derived tenant and principal headers downstream.
- [ ] 1.24 [CODE] Proxy registry REST routes through the gateway; forward `traceparent` and set `X-Trace-Id` on all proxied responses.
- [ ] 1.25 [CODE] Add Redis-backed rate limiting on all routes, including stricter token buckets for `/auth/*` and other expensive endpoints.
- [ ] 1.26 [TEST] Add end-to-end auth tests for register → OTP → verify → login, cookie flows, Bearer flows, rate-limiting (assert 429), and cross-service trace propagation.
- [ ] 1.27 [DOCS] Update `docs/api/gateway.openapi.yaml` to cover every implemented `/auth/*` route, cookie/Bearer behavior, and any deferred tenant-OIDC surface.

### gRPC — Gateway → Registry

- [ ] 1.28 [CODE] Define `registry/v1/registry.proto` in `shared:contracts` with `GetService`, `ListServices`, and `WatchServices` RPCs; follow `io.cartogra.registry.v1` package naming and set `java_multiple_files = true`.
- [ ] 1.29 [CODE] Implement `RegistryGrpcService` in `services:registry` annotated with `@GrpcService`, extending the generated `RegistryServiceImplBase`; extract tenant ID from gRPC metadata via the server interceptor, never as a proto field.
- [ ] 1.30 [CODE] Add the gRPC server interceptor in `services:registry` that reads `x-tenant-id` metadata and stores it in a `ScopedValue` for the duration of the call.
- [ ] 1.31 [CODE] Implement `RegistryGrpcClient` in `services:gateway` using `@GrpcClient("registry")`; attach `x-tenant-id` metadata on every outbound call and map `StatusRuntimeException` to domain exceptions at the infrastructure boundary.
- [ ] 1.32 [INFRA] Configure gRPC server port `9091` for registry (separate from REST port `8081`); add `REGISTRY_GRPC_PORT` to `.env.example` and expose the port in `docker-compose.yml`.

### SCM SPI and ingestion workers

- [ ] 1.33 [CODE] Define the Spring-free `ScmProvider` SPI under ingestion application code.
- [ ] 1.34 [CODE] Implement `GitHubProvider` using connection config stored as JSONB and WireMock-backed tests.
- [ ] 1.35 [CODE] Implement `AzureDevOpsProvider` using PAT or service-principal config and WireMock-backed tests.
- [ ] 1.36 [INFRA] Add ingestion Flyway `V001__create_sync_jobs.sql` with tenant isolation, status tracking, soft delete, indexes, and RLS.
- [ ] 1.37 [CODE] Implement GitHub and Azure DevOps sync workers that consume sync commands, update `sync_jobs`, publish results, and propagate trace headers.
- [ ] 1.38 [CODE] Add the Kubernetes worker behind `ENABLE_K8S_WORKER=true` with a documented kind/mock fallback for local development.
- [ ] 1.39 [TEST] Add integration tests for both SCM providers plus sync job state transitions.
- [ ] 1.40 [DOCS] Write the ADR for the SCM provider abstraction and update deployment docs with PAT/service-principal setup.
- [ ] 1.41 [BIP] Publish the SCM SPI ADR after the abstraction and both provider paths are coded.
- [ ] 1.42 [BIP] Publish the provider-comparison thread after both provider adapters have passing tests.

### Events, catalog UI, and demo access

- [ ] 1.43 [CODE] Publish registry service lifecycle events with the shared Kafka envelope and `traceparent` propagation.
- [ ] 1.44 [CODE] Consume sync commands in ingestion with an idempotency strategy documented in code or ADR notes.
- [ ] 1.45 [CODE] Decide whether guest demo access is enabled in Phase 1; if enabled, enforce read-only behavior and keep gateway rate limits active.
- [ ] 1.46 [UI] Craft Login, Register, Verify email, and OAuth handoff screens using `/impeccable craft login` and `/impeccable craft register`; all auth state must use httpOnly cookies — never `localStorage`.
- [ ] 1.47 [UI] Craft the Catalog list and detail flows using `/impeccable craft catalog home`; include filters for team, health, tech stack, SCM provider, and search, plus orphan highlighting.
- [ ] 1.48 [UI] Centralize envelope parsing in the frontend API client; surface `traceId` in all error states via the `ApiError` class established in Phase 0.
- [ ] 1.49 [UI] Run `/impeccable audit` on all Phase 1 screens before the phase gate; address any blocking findings.
- [ ] 1.50 [TEST] Add contract tests that verify gateway and registry responses match the OpenAPI envelope and `X-Trace-Id` contract.
- [ ] 1.51 [DOCS] Update `docs/api/registry.openapi.yaml` to reflect the implemented registry surface.
- [ ] 1.52 [BIP] Publish the envelope-and-trace-ID thread after gateway proxying works end to end.
- [ ] 1.53 [BIP] Publish the Gateway-auth rationale thread after local auth and at least one OAuth provider work end to end.
- [ ] 1.54 [BIP] Publish the self-healing-registry article after the catalog and sync story are demonstrable.
- [ ] 1.55 [BIP] Publish the multi-tenant plus temporal-versioning article after history and ownership flows work.
- [ ] 1.56 [BIP] Publish the enterprise-SCM angle on LinkedIn after both providers are visible in the catalog.
- [ ] 1.57 [BIP] Publish screenshots plus OpenAPI links after the catalog UI is stable enough to demo.

### Phase 1 Gate

- [ ] [GATE] Registry CRUD, history, ownership, orphan detection, sync initiation, local auth, at least one OAuth provider, and Bearer auth are working.
- [ ] [GATE] Tenant OIDC is either working or explicitly deferred with a public note that preserves scope discipline.
- [ ] [GATE] Both SCM sync workers succeed in scripted or runbooked tests; Kafka events are visible.
- [ ] [GATE] 429 behavior is smoke-tested and documented.
- [ ] [GATE] `shared:contracts` contains `registry/v1/registry.proto`; `RegistryGrpcService` is reachable from gateway via gRPC.
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

### gRPC — Gateway → Topology

- [ ] 2.8 [CODE] Define `topology/v1/topology.proto` in `shared:contracts` with `GetGraph`, `GetImpact`, `ListSPOF`, `ListCycles`, and `ListDrifts` RPCs.
- [ ] 2.9 [CODE] Implement `TopologyGrpcService` in `services:topology` annotated with `@GrpcService`; extract tenant from gRPC metadata via the shared server interceptor pattern.
- [ ] 2.10 [CODE] Implement `TopologyGrpcClient` in `services:gateway` using `@GrpcClient("topology")`; map `StatusRuntimeException` to domain exceptions at the infrastructure boundary.
- [ ] 2.11 [INFRA] Configure gRPC server port `9092` for topology; add `TOPOLOGY_GRPC_PORT` to `.env.example` and expose the port in `docker-compose.yml`.

### Graph algorithms and APIs

- [ ] 2.12 [CODE] Implement the declared-dependency CRUD API and graph read endpoints with the shared response envelope.
- [ ] 2.13 [CODE] Implement recursive CTEs for blast radius with bounded depth and path-explosion guards.
- [ ] 2.14 [CODE] Implement cycle detection with normalized and deduplicated cycle output.
- [ ] 2.15 [CODE] Implement the SPOF heuristic with a documented fan-in threshold and any redundancy assumptions.
- [ ] 2.16 [CODE] Implement drift detection plus the resolve endpoint.
- [ ] 2.17 [TEST] Add fixed graph fixtures and tests for graph reads, blast radius, cycle detection, SPOF, and drift resolution.
- [ ] 2.18 [DOCS] Update `docs/api/topology.openapi.yaml` to match the implemented graph, drift, and analysis endpoints.
- [ ] 2.19 [DOCS] Write the ADR that documents PostgreSQL recursive CTEs and materialized views for the graph layer.
- [ ] 2.20 [BIP] Publish the recursive-CTE article after the graph algorithms have passing fixtures.
- [ ] 2.21 [BIP] Publish the blast-radius SQL thread after the blast-radius endpoint is reviewable.

### Event-driven graph updates and observed dependencies

- [ ] 2.22 [CODE] Implement the `topology-graph-builder` consumer so registry events update topology state idempotently.
- [ ] 2.23 [CODE] Publish topology dependency events with shared Kafka envelope and trace propagation.
- [ ] 2.24 [CODE] Implement `OtelSpanWorker` so real or synthetic spans can create observed edges.
- [ ] 2.25 [TEST] Add fixture-based integration tests that prove a span batch produces observed edges and mode toggling works.
- [ ] 2.26 [CODE] Instrument MV refresh duration, consumer lag, and graph query latency metrics.
- [ ] 2.27 [DOCS] Write the ADR or design note that captures the Kafka topic taxonomy and idempotency strategy for graph updates.
- [ ] 2.28 [BIP] Publish the Kafka topic design article after topology topics and consumers are visible.
- [ ] 2.29 [BIP] Publish the UUIDv5/idempotency thread after the event flow is stable.

### Graph UI

- [ ] 2.30 [UI] Craft the D3 graph with zoom, pan, declared-vs-observed toggle, node selection, and drift overlays using `/impeccable craft dependency graph`; use D3 for the canvas and shadcn/ui for all surrounding chrome.
- [ ] 2.31 [UI] Craft the upstream/downstream detail panels and blast-radius highlighting using `/impeccable craft blast radius panel`.
- [ ] 2.32 [UI] Run `/impeccable audit` on all Phase 2 screens before the phase gate.
- [ ] 2.33 [TEST] Measure and document graph render behavior for the 20-service seed target and capture the target hardware used.
- [ ] 2.34 [BIP] Publish the declared-vs-observed dependencies article after the toggle and overlay work.
- [ ] 2.35 [BIP] Publish the materialized-view-for-speed thread after the performance story is defensible.
- [ ] 2.36 [BIP] Publish the event-naming/partitioning LinkedIn post after topic naming is settled.
- [ ] 2.37 [BIP] Record the optional live-impact-analysis video if it does not block the phase gate.

### Phase 2 Gate

- [ ] [GATE] The graph renders for a tenant-sized dataset without browser freeze on the documented target hardware.
- [ ] [GATE] Blast radius, cycles, SPOF, and drift endpoints return stable results on known fixtures.
- [ ] [GATE] Observed dependencies can be produced from a trace fixture or synthetic span path.
- [ ] [GATE] `shared:contracts` contains `topology/v1/topology.proto`; `TopologyGrpcService` is reachable from gateway via gRPC.
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

### gRPC — Gateway → Contract

- [ ] 3.11 [CODE] Define `contract/v1/contract.proto` in `shared:contracts` with `CheckCompatibility`, `GetMatrix`, and `GetContractCheck` RPCs.
- [ ] 3.12 [CODE] Implement `ContractGrpcService` in `services:contract` annotated with `@GrpcService`; extract tenant from gRPC metadata.
- [ ] 3.13 [CODE] Implement `ContractGrpcClient` in `services:gateway` using `@GrpcClient("contract")`; map `StatusRuntimeException` to domain exceptions.
- [ ] 3.14 [INFRA] Configure gRPC server port `9093` for contract; add `CONTRACT_GRPC_PORT` to `.env.example` and expose the port in `docker-compose.yml`.

### Breaking-change engine and compatibility workflows

- [ ] 3.15 [CODE] Implement breaking-change rules for removed fields, type changes, added required fields, and enum removals.
- [ ] 3.16 [CODE] Implement structured `changes` output plus `affected_consumers` resolution.
- [ ] 3.17 [CODE] Implement approve/block workflows for breaking checks.
- [ ] 3.18 [TEST] Add golden tests for compatible and breaking spec pairs.
- [ ] 3.19 [BIP] Publish the structured-diff thread after the golden tests and diff payloads are stable.
- [ ] 3.20 [BIP] Publish the breaking-change algorithm article after the engine is reviewable.

### Outbox, notifications, and CI surface

- [ ] 3.21 [CODE] Implement the transactional outbox so contract version writes and outbox events commit atomically.
- [ ] 3.22 [CODE] Implement the outbox relay with backoff, publish tracking, and poison-message handling.
- [ ] 3.23 [CODE] Publish schema and check events with shared Kafka envelope and trace propagation.
- [ ] 3.24 [CODE] Implement notification rules, delivery logs, and at least one proven outbound channel path (Slack or Teams webhook).
- [ ] 3.25 [TEST] Add integration coverage proving contract write → outbox row → relay → Kafka → notification log.
- [ ] 3.26 [DOCS] Write the ADR for the outbox pattern and cross-link it from deployment or incident docs.
- [ ] 3.27 [BIP] Publish the outbox ADR after the relay integration test passes.
- [ ] 3.28 [BIP] Publish the dual-write-bug article after the relay is demonstrably reliable.
- [ ] 3.29 [INFRA] Add the tenant API key migration; store API keys hashed at rest.
- [ ] 3.30 [CODE] Implement admin APIs to issue, list, and revoke tenant API keys.
- [ ] 3.31 [CODE] Implement `POST /ci/check` with API-key-only auth (`X-Cartogra-Api-Key`), envelope responses, and documented blocking semantics.
- [ ] 3.32 [CODE] Implement the GitHub Action extension against `/ci/check`.
- [ ] 3.33 [CODE] Implement the Azure Pipelines task against `/ci/check`.
- [ ] 3.34 [CODE] Implement spec discovery from ingestion so discovered OpenAPI/AsyncAPI files are processed idempotently.
- [ ] 3.35 [TEST] Add workflow smoke tests for both CI extensions and idempotency tests for spec discovery.
- [ ] 3.36 [DOCS] Add example GitHub Actions and Azure Pipelines usage to docs and update `docs/api/contract.openapi.yaml`.
- [ ] 3.37 [BIP] Publish the dual-marketplace article after both extension paths run against the same backend contract.
- [ ] 3.38 [BIP] Publish the one-check-two-CI-systems thread after both extensions pass smoke tests.

### Contract Hub UI

- [ ] 3.39 [UI] Craft the side-by-side diff viewer with required, added, and removed highlights using `/impeccable craft contract diff`.
- [ ] 3.40 [UI] Craft the compatibility matrix heatmap, version timeline, and breaking-check queue using `/impeccable craft contract matrix`.
- [ ] 3.41 [UI] Run `/impeccable audit` on all Phase 3 screens before the phase gate.
- [ ] 3.42 [BIP] Publish the schema-diff UI demo after the Contract Hub is stable enough to show.
- [ ] 3.43 [BIP] Publish the compatibility-matrix story after the matrix is wired to real data.
- [ ] 3.44 [BIP] Publish the marketplace listing or a documented blocker/timeline immediately when the packaging outcome is known.

### Phase 3 Gate

- [ ] [GATE] End-to-end contract flow works locally: new spec version, compatibility check, Kafka publication, and notification log entry.
- [ ] [GATE] `POST /ci/check` works with tenant API keys only and returns the global response envelope.
- [ ] [GATE] GitHub Action and Azure Pipelines task both demonstrate failing and passing scenarios.
- [ ] [GATE] `shared:contracts` contains `contract/v1/contract.proto`; `ContractGrpcService` is reachable from gateway via gRPC.
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

### gRPC — Gateway → Intelligence

- [ ] 4.10 [CODE] Define `intelligence/v1/intelligence.proto` in `shared:contracts` with `Query`, `GetQueryFeedback`, `Analyze`, `ListFindings`, and `GetHealthScore` RPCs.
- [ ] 4.11 [CODE] Implement `IntelligenceGrpcService` in `services:intelligence` annotated with `@GrpcService`; extract tenant from gRPC metadata.
- [ ] 4.12 [CODE] Implement `IntelligenceGrpcClient` in `services:gateway` using `@GrpcClient("intelligence")`; map `StatusRuntimeException` to domain exceptions.
- [ ] 4.13 [INFRA] Configure gRPC server port `9094` for intelligence; add `INTELLIGENCE_GRPC_PORT` to `.env.example` and expose the port in `docker-compose.yml`.

### Natural-language query flow

- [ ] 4.14 [CODE] Implement SQL safety guardrails: allowlisted sources only, read-only execution, parameter binding, row limits, and timeout handling.
- [ ] 4.15 [CODE] Implement `POST /intelligence/query` returning `answer`, optional `data`, optional `generated_sql`, and a durable `query_id`.
- [ ] 4.16 [CODE] Implement `POST /intelligence/query/{id}/feedback` and store the result in `nl_query_log`.
- [ ] 4.17 [TEST] Add unit and integration coverage for safe vs unsafe query handling and prompt formatting.
- [ ] 4.18 [DOCS] Update `docs/api/intelligence.openapi.yaml` for query and feedback endpoints.
- [ ] 4.19 [BIP] Publish the NL-over-PostgreSQL article after the guarded query flow works.
- [ ] 4.20 [BIP] Publish the token-and-latency tracking thread after those metrics are visible.

### Analysis jobs, findings, and health score

- [ ] 4.21 [CODE] Implement deterministic anti-pattern detection for at least circular dependency, god service, and orphaned service.
- [ ] 4.22 [CODE] Layer LLM narrative on top of deterministic evidence instead of letting the model invent unsupported claims.
- [ ] 4.23 [CODE] Implement `POST /intelligence/analyze`, findings list, acknowledge/resolve flows, health score, and digest retrieval.
- [ ] 4.24 [CODE] Publish intelligence analysis request and result events.
- [ ] 4.25 [TEST] Add fixture coverage proving known anti-patterns produce findings and health-score updates.
- [ ] 4.26 [BIP] Publish the evidence-first anti-pattern thread after deterministic findings are stable.
- [ ] 4.27 [BIP] Publish the trust-model thread after the deterministic-plus-LLM split is documented.

### Intelligence UI and operator visibility

- [ ] 4.28 [UI] Craft the NL query panel with answer rendering, optional generated-SQL disclosure, and feedback controls using `/impeccable craft intelligence panel`.
- [ ] 4.29 [UI] Craft the findings feed with severity badges and acknowledge/resolve actions using `/impeccable craft anti-pattern feed`.
- [ ] 4.30 [UI] Craft the health-score trend and latest digest views using `/impeccable craft health score`.
- [ ] 4.31 [UI] Run `/impeccable audit` on all Phase 4 screens before the phase gate.
- [ ] 4.32 [TEST] Create an evaluation set of NL questions against fixtures or seed data and document the pass/fail bar.
- [ ] 4.33 [DOCS] Finish `docs/api/intelligence.openapi.yaml` for the full implemented surface.
- [ ] 4.34 [BIP] Publish the LLM-for-infrastructure-intelligence article after the UI and API are demoable.
- [ ] 4.35 [BIP] Record the NL query demo video after the query path is stable enough to show live.

### Phase 4 Gate

- [ ] [GATE] NL queries are guarded by documented safety rules and abuse limits.
- [ ] [GATE] At least three anti-pattern types produce findings on fixtures or seed data with deterministic evidence.
- [ ] [GATE] Health score and digest are available via API and visible in the UI.
- [ ] [GATE] `shared:contracts` contains `intelligence/v1/intelligence.proto`; `IntelligenceGrpcService` is reachable from gateway via gRPC.
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
- [ ] 5.4 [CODE] Enforce guest read-only mode for the Acme tenant and sandbox isolation for authenticated demo users.
- [ ] 5.5 [CODE] Add sandbox cleanup automation.

### Deployment packaging and staging

- [ ] 5.6 [INFRA] Build the Helm umbrella chart and per-service charts with required probes, security context, resources, and non-root defaults.
- [ ] 5.7 [INFRA] Add staging and production Helm values.
- [ ] 5.8 [INFRA] Finalize Terraform modules and at least one concrete staging environment.
- [ ] 5.9 [INFRA] Configure remote Terraform state with S3 backend, DynamoDB lock table, encryption, and bucket versioning.
- [ ] 5.10 [INFRA] Add staging deployment CI plus post-deploy smoke tests.

### Observability, DLQ, and operations

- [ ] 5.11 [INFRA] Finalize the staging OTel, Jaeger, Prometheus, and Grafana path.
- [ ] 5.12 [INFRA] Add alerts for consumer lag, elevated error rate, and critical-path failures.
- [ ] 5.13 [CODE] Implement the dead-letter topic path and replay admin API with audit logging.
- [ ] 5.14 [UI] Craft the Operations view for connector health, recent platform events, lag visibility, and observability links using `/impeccable craft operations view`.
- [ ] 5.15 [UI] Run `/impeccable audit` on all Phase 5 screens before the phase gate.
- [ ] 5.16 [DOCS] Expand deployment and incident runbooks for staging, alerts, DLQ replay, and operator workflows.
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

### Phase 5 Gate

- [ ] [GATE] Staging is reproducible from docs, and the seed loader populates the Acme demo cleanly.
- [ ] [GATE] Guest demo scenarios match the documented Acme Fintech story.
- [ ] [GATE] Observability answers "what is broken" and "which consumer is lagging" within a few minutes for a maintainer.
- [ ] [GATE] Playwright and k6 gates pass in CI with documented thresholds.
- [ ] [GATE] Phase 5 screens have passed `/impeccable audit`.
- [ ] [GATE] Minimum BIP set shipped: observability writeup, data-architecture retrospective, launch post, and at least one retrospective artifact.
