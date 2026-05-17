# Plan: docs/execution-checklist.md — chronological task + BIP checklist

## Context

`docs/plan.md` is the authoritative reference (phases, specs, user stories, risk register, ADR
rationale) but is too dense for day-to-day use. The user wants a companion file that lists every
task from Phase 0 through Phase 5 in execution order, with BIP (Build in Public) tasks
interleaved at the natural point they should be published — not batched at the end of each phase.
The goal is a file you can open on a Monday morning and know exactly what to do next.

Two files change:
1. **Create** `docs/execution-checklist.md` — the new working checklist
2. **Update** `docs/plan.md` — add a one-line callout pointing to the checklist

---

## Critical files

| File | Action |
|------|--------|
| `docs/execution-checklist.md` | Create (new file) |
| `docs/plan.md` | Update — insert one blockquote callout at line 27 |

---

## Format decisions

| Concern | Decision | Reason |
|---------|----------|--------|
| Category label style | Plain `[CODE]` `[INFRA]` `[TEST]` `[UI]` `[DOCS]` `[BIP]` `[GATE]` | Scannable, grep-able; no extra markdown noise in checkbox lines |
| Already-done items (S0.1–S0.3) | `- ~~[DONE] [CATEGORY] …~~` (strikethrough) | Mirrors plan.md convention; `- [x]` implies this file was authoritative from day one, which it wasn't |
| ADR numbers in checklist | Descriptive only ("Write ADR: X" not "Write ADR-0003") | ADR-0003 is already gRPC; ADR-0007 is local dev infra; hardcoding numbers creates collisions as the sequence fills |
| Week groupings | **None** — tasks listed in execution order under logical sub-headings only | Weeks are arbitrary; sub-headings like "Repository & governance" and "Database" convey what matters without imposing a schedule |
| BIP placement | Inline immediately after the code/docs task they depend on | Makes the "publish after X lands" intent obvious without a calendar reference |
| Phase gate block | `### Phase N Gate` sub-section with `[GATE]` prefix checkboxes | Separates quality verification from delivery; mirrors DoD sections in plan.md |
| Flyway tasks | One checkbox per migration file | Each migration is atomic; batching hides skipped migrations |

---

## docs/plan.md change

Insert the following blockquote at **line 27** (between the italic preamble paragraph on line 26
and the `# Cartogra — Implementation Plan` heading on line 28):

```markdown
> **Working from this plan?** The day-to-day task checklist with checkbox items in execution
> order (Phase → tasks) lives at [`docs/execution-checklist.md`](execution-checklist.md).
> Use that file for your daily work. Use this file for rationale, constraints, user stories,
> and dependency maps.
```

---

## docs/execution-checklist.md content outline

### File header
- One-paragraph orientation (this file = working checklist; plan.md = rationale)
- Category key
- Note: delete `REMOVE_docs-plan-md-plan-step-by-robust-origami.md` from repo root (leftover planning artifact)

---

### Phase 0 — Foundation

Already-done S0.1/S0.2/S0.3 shown as strikethrough `[DONE]` items.

**Repository & governance**
- `[DOCS]` LICENSE, README, CONTRIBUTING, .editorconfig, .gitignore, CODEOWNERS, branch protection
- `[DOCS]` Issue templates (bug / feature / architecture discussion) + PR template
- `[DOCS]` GitHub Project: columns + milestones Phase 0–5

**Build & modules**
- `[CODE]` `shared:common` — EventEnvelope, TenantId, ServiceId, ApiError DTO (plain Java, no Spring)
- `[CODE]` `shared:test-support` — PostgresTestSupport + KafkaTestSupport
- `[CODE]` `services/registry` — data-jdbc, explicit flyway, virtual threads
- `[CODE]` `services/gateway` — Spring Cloud Gateway, OTel, traceparent filter, X-Trace-Id filter, virtual threads
- `[CODE]` `services/ingestion` — data-jdbc, explicit flyway, virtual threads, health stub only
- `[CODE]` One envelope endpoint returning `{"data":…,"traceId":"…"}` + `X-Trace-Id` header

**Database**
- `[INFRA]` Registry Flyway V001 (tenants) — tenant_id, TIMESTAMPTZ, soft-delete, RLS
- `[INFRA]` Registry Flyway V002 (teams) — tenant_id, TIMESTAMPTZ, soft-delete, RLS
- `[INFRA]` Registry Flyway V003 (users) — tenant_id, TIMESTAMPTZ, soft-delete, RLS
- `[INFRA]` Registry Flyway V004 (scm_connections) — tenant_id, TIMESTAMPTZ, soft-delete, RLS
- `[TEST]` Smoke test: Testcontainers Postgres + Flyway clean migrate

**Local runtime**
- `[INFRA]` `infra/docker-compose/docker-compose.yml` — postgres:16-alpine, valkey/valkey:8-alpine, apache/kafka:4.0.0, otel-collector-contrib, jaeger:2.17.0; healthchecks; named volumes
- `[INFRA]` `infra/docker-compose/otel-collector.yml` — OTLP receive → Jaeger export
- `[INFRA]` `infra/docker-compose/docker-compose.dev.yml` — Redpanda Console overlay
- `[INFRA]` `.env.example` at repo root

**Containers**
- `[INFRA]` Multi-stage Dockerfile: registry (eclipse-temurin:25-jdk → jre, non-root user, MaxRAMPercentage=75)
- `[INFRA]` Multi-stage Dockerfile: gateway
- `[INFRA]` Multi-stage Dockerfile: ingestion

**Observability**
- `[CODE]` Structured JSON logging on all three services with OTel traceId; verify traceparent → traceId correlation

**CI**
- `[INFRA]` `ci.yml` — Java 25, `./gradlew build`, tests, Trivy (CRITICAL/HIGH = fail)
- `[BIP]` BIP0.3 — launch thread (problem, gap, what you're building) — publish once stack compiles
- `[BIP]` BIP0.4 — data model / Kafka sketch thread — publish after V001–V004 merged

**Frontend**
- `[UI]` TanStack Start + TS + ESLint + Prettier; Tailwind + shadcn; TanStack Router file-based layout — sidebar: Catalog / Graph / Contracts / Intelligence / Operations (dead links OK)
- `[INFRA]` Frontend CI job: lint + `vitest --passWithNoTests`

**Docs**
- `[DOCS]` `docs/adr/TEMPLATE.md` + `docs/adr/README.md` (empty index)
- `[DOCS]` `docs/architecture/system-overview.md`, `data-model.md`, `kafka-topics.md` (planned topics, status: not yet provisioned)
- `[DOCS]` `docs/api/gateway.openapi.yaml` + `docs/api/registry.openapi.yaml` — envelope components (data + traceId), error schema, X-Trace-Id header; auth paths marked "Phase 1"
- `[DOCS]` `docs/api/topology.openapi.yaml`, `contract.openapi.yaml`, `intelligence.openapi.yaml` — minimal forward-looking stubs
- `[DOCS]` `docs/runbooks/local-development.md`, `deployment.md`, `incident-response.md` — initial stubs
- `[BIP]` BIP0.1 — blog: "Why your service catalog is always wrong" — publish after docs stubs exist
- `[BIP]` BIP0.2 — blog: "ADRs to think in public" + link to TEMPLATE.md — publish after ADR template merged
- `[BIP]` BIP0.5 — GitHub: README diagram + Project board screenshot
- `[BIP]` BIP0.6 — optional 5-min problem framing video

### Phase 0 Gate
- `[GATE]` `./gradlew build` + CI green; Trivy policy documented
- `[GATE]` `docker compose up` succeeds; Flyway clean migrate passes; gateway + ingestion health 200
- `[GATE]` One endpoint returns `{data, traceId}` + `X-Trace-Id` header
- `[GATE]` Frontend installs + runs; CI lint passes
- `[GATE]` BIP0.1–BIP0.4 published; ADR index file exists

---

### Phase 1 — Gateway MVP auth + Registry

**Registry domain (US1.1–US1.4)**
- `[CODE]` Hexagonal package structure: domain, application, infrastructure/persistence
- `[INFRA]` Registry Flyway V005 (services) — tenant_id, TIMESTAMPTZ, RLS, GIN on metadata
- `[INFRA]` Registry Flyway V006 (services_history)
- `[INFRA]` Registry Flyway V007 (scm_webhooks) — tenant_id, TIMESTAMPTZ, RLS
- `[CODE]` JDBC repos: ServiceRepository, TeamRepository, ScmConnectionRepository (explicit SQL; to_tsvector FTS)
- `[CODE]` Service CRUD use cases; unique (tenant_id, name); soft delete
- `[CODE]` services_history writer on every material change
- `[CODE]` Point-in-time query: GET .../history/at + GET .../history
- `[CODE]` GET /services/orphaned
- `[CODE]` REST controllers with ApiResponse<T> → {data, traceId}; @RestControllerAdvice → {error, traceId}
- `[CODE]` Team + ScmConnection CRUD
- `[TEST]` Testcontainers integration tests: CRUD + history + orphan
- `[DOCS]` Write ADR: PostgreSQL vs graph DB
- `[BIP]` BIP1.1 — publish ADR: PostgreSQL vs graph DB (after ADR merged)
- `[DOCS]` Write ADR: Spring Data JDBC default
- `[BIP]` BIP1.3 — design note: Spring Data JDBC (after ADR merged)

**Gateway auth + proxy (US1.7, US1.8, US1.10)**
- `[INFRA]` Registry migration: extend users (email_verified, auth_provider, auth_subject, password_hash)
- `[CODE]` Spring Security 7 config on gateway
- `[CODE]` Resend HTTP client (API key from env; test mode in CI)
- `[CODE]` POST /auth/register → OTP via Resend; POST /auth/verify-email; POST /auth/login; POST /auth/refresh; POST /auth/logout; GET /auth/userinfo
- `[CODE]` OAuth start + callback for Google + GitHub
- `[CODE]` Per-tenant OIDC config: schema + admin API (defer UI)
- `[CODE]` httpOnly cookie + Bearer token issuance; X-Tenant-Id injection from token
- `[CODE]` Redis token-bucket rate limiting on ALL routes; stricter bucket on /auth/*
- `[CODE]` Trust boundary: strip client X-Tenant-Id; derive from session only
- `[CODE]` Proxy routes to registry; forward traceparent; set X-Trace-Id
- `[TEST]` Auth flow: register → Resend mock → verify → login (cookie + Bearer); 429 behavior; OTel hop trace verification
- `[DOCS]` Update docs/api/gateway.openapi.yaml with all /auth/* routes
- `[BIP]` BIP1.6 — thread: HTTP envelope + trace id end-to-end (after proxy works)
- `[BIP]` BIP1.8 — thread: Gateway owns MVP auth (after auth works end-to-end)

**SCM SPI + ingestion workers (US1.5)**
- `[CODE]` ScmProvider SPI interface in ingestion/application/spi/ (no Spring)
- `[CODE]` GitHubProvider (PAT from scm_connections JSONB; WireMock in tests)
- `[CODE]` AzureDevOpsProvider (PAT/SP from connection config; WireMock in tests)
- `[INFRA]` Ingestion Flyway V001 (sync_jobs): id, tenant_id, connection_id, status, started_at, completed_at, error_detail; index on (tenant_id, connection_id); RLS
- `[CODE]` GitHubSyncWorker + AzureDevOpsSyncWorker: consume sync-commands, update sync_jobs, produce sync-results; propagate traceparent
- `[CODE]` K8s worker behind ENABLE_K8S_WORKER=true env flag; stub + runbook
- `[TEST]` WireMock integration tests for both providers
- `[DOCS]` Write ADR: SCM provider abstraction (SPI pattern)
- `[DOCS]` Add PAT setup to docs/runbooks/deployment.md
- `[BIP]` BIP1.2 — publish ADR: SCM SPI (after ADR merged + SPI coded)
- `[BIP]` BIP1.7 — thread: SPI sketch (GitHub vs Azure DevOps)

**Kafka events + catalog UI (US1.5, US1.6, US1.9)**
- `[CODE]` Registry Kafka producer: service-events on create/update/delete; Kafka envelope + traceparent header
- `[CODE]` Sync-commands consumer in ingestion; idempotency documented
- `[CODE]` Tenant OIDC login flow (if not deferred)
- `[UI]` Login / register / verify screens; OAuth redirects via gateway; no tokens in localStorage
- `[UI]` Catalog: service list with filters (team, health, tech_stack, scm_provider, search); service detail + SCM badge; orphan highlight
- `[UI]` Envelope parsing in apiFetch: .data vs .error; ApiError with traceId
- `[TEST]` Contract tests: envelope + X-Trace-Id on registry + gateway OpenAPI
- `[DOCS]` Update docs/api/registry.openapi.yaml to reflect implemented endpoints
- `[BIP]` BIP1.4 — blog: self-healing registry narrative
- `[BIP]` BIP1.5 — blog: multi-tenant + temporal versioning deep dive
- `[BIP]` BIP1.9 — LinkedIn: enterprise SCM angle
- `[BIP]` BIP1.10 — GitHub: screenshots + OpenAPI links

### Phase 1 Gate
- `[GATE]` US1.1–US1.8 + US1.10 satisfied; US1.9 done or explicitly deferred with public note
- `[GATE]` Both SCM sync workers succeed in scripted / runbooked tests; Kafka events visible
- `[GATE]` 429 behavior smoke-tested + documented
- `[GATE]` OpenAPI + envelope match project-guide §2
- `[GATE]` BIP1.1–BIP1.8 minimum published

---

### Phase 2 — Topology service

**Schema + persistence**
- `[CODE]` Topology module skeleton: data-jdbc, explicit flyway, OTel, virtual threads
- `[INFRA]` Topology Flyway V001 (dependencies): tenant_id, source/target service IDs, dependency_type, confidence_score, observation_count; source≠target constraint; RLS
- `[INFRA]` Topology Flyway V002 (dependency_drifts)
- `[INFRA]` Topology Flyway V003 (dependency_graph materialized view + REFRESH strategy)
- `[CODE]` DependencyRepository: insert, upsert, soft delete; confidence_score update for observed edges
- `[CODE]` MV refresh strategy chosen + documented (advisory lock / debounce if needed)

**Graph algorithms + REST API (US2.1–US2.4)**
- `[CODE]` Blast radius recursive CTE (cap depth, prevent path explosion)
- `[CODE]` Cycle detection CTE (normalize + deduplicate cycles)
- `[CODE]` SPOF heuristic: high fan-in; configurable threshold
- `[CODE]` Drift detector: scheduled + on-demand; write dependency_drifts; PUT /drifts/{id}/resolve
- `[CODE]` REST controllers: POST/DELETE /dependencies; GET /graph?type=; GET /graph/impact/{id}; GET /graph/cycles; GET /graph/spof; GET /drifts — all use HTTP envelope
- `[TEST]` Graph algorithm unit tests on fixed fixtures (blast radius, cycles, SPOF)
- `[DOCS]` Update docs/api/topology.openapi.yaml
- `[DOCS]` Write ADR: recursive CTEs vs graph DB (check ADR README for next available number)
- `[BIP]` blog: recursive CTEs in production PostgreSQL (after algorithms passing)
- `[BIP]` thread: blast radius SQL + explanation

**Event-driven graph builder**
- `[CODE]` Consumer group topology-graph-builder: subscribe to cartogra.registry.service-events; idempotent edge upsert
- `[CODE]` Emit cartogra.topology.dependency-events on changes; propagate traceparent
- `[CODE]` OtelSpanWorker: consume cartogra.topology.otel-spans → infer observed edges; fixture span batch test
- `[CODE]` Observability: MV refresh duration metric, consumer lag, query latency histograms
- `[DOCS]` Write ADR: Kafka envelope + topic taxonomy (check ADR README for next available number)
- `[BIP]` blog: Kafka topic design — 16 topics walkthrough
- `[BIP]` thread: UUIDv5 idempotency pattern

**D3 graph frontend**
- `[UI]` D3 force-directed graph: zoom/pan; declared vs observed toggle; drift overlay
- `[UI]` Node click panel: upstream/downstream lists
- `[UI]` Blast-radius highlight: select node, highlight affected subgraph
- `[TEST]` Graph renders with ≥20 services without browser freeze; p95 latency documented
- `[BIP]` blog: declared vs observed dependencies
- `[BIP]` thread: materialized view for graph speed
- `[BIP]` LinkedIn: event naming + partitioning story
- `[BIP]` optional video: live impact analysis

### Phase 2 Gate
- `[GATE]` Graph renders for ≥20 services (target hardware documented)
- `[GATE]` Impact/cycles/SPOF stable on seed/fixture data
- `[GATE]` Consumer lag observable in metrics; DLQ path stubbed
- `[GATE]` BIP2.1–BIP2.7 minimum published

---

### Phase 3 — Contract guardian

**Contract CRUD + versions (US3.1)**
- `[CODE]` Contract module skeleton
- `[INFRA]` Contract Flyway V001 (api_contracts)
- `[INFRA]` Contract Flyway V002 (contract_versions: spec_hash, spec_content JSONB, semver uniqueness)
- `[INFRA]` Contract Flyway V003 (contract_consumers)
- `[INFRA]` Contract Flyway V004 (contract_checks)
- `[INFRA]` Contract Flyway V005 (outbox_events: idx_outbox_unpublished)
- `[CODE]` OpenAPI 3 + AsyncAPI 2 parsers: validate, reject invalid, store canonical JSON
- `[CODE]` Contract CRUD + versions REST; all responses use HTTP envelope
- `[TEST]` Valid + invalid spec payloads; version uniqueness; Testcontainers

**Breaking-change engine (US3.2, US3.3)**
- `[CODE]` Breaking-change rules: removed fields, type changes, new required fields, enum removals
- `[CODE]` POST /contracts/{id}/check: persist check; return is_breaking + changes JSON + affected_consumers
- `[CODE]` Approve/block workflow
- `[TEST]` Golden tests: known breaking vs compatible OpenAPI pairs (table-driven)
- `[BIP]` thread: structured JSONB diffs for automation
- `[BIP]` blog: breaking change detection algorithm

**Outbox + Kafka + notifications (US3.5)**
- `[CODE]` Transactional outbox: contract version + outbox_events in same DB transaction
- `[CODE]` Outbox relay: poll idx_outbox_unpublished; publish to Kafka; backoff; poison message handling
- `[CODE]` Publish cartogra.contract.schema-events + cartogra.contract.check-events; propagate traceparent
- `[CODE]` notification_rules CRUD (admin); notification_log + retry
- `[CODE]` Fan-out worker: subscribe to check-events → deliver to Slack or Teams webhook
- `[CODE]` cartogra.platform.dead-letter stub
- `[TEST]` Outbox integration: transaction commit → relay → Kafka publish; webhook mock delivery
- `[DOCS]` Write ADR: outbox pattern (check ADR README for next available number)
- `[BIP]` publish ADR: outbox (after integration test passes)
- `[BIP]` blog: outbox eliminating dual-write bugs

**CI extensions (US3.4)**
- `[INFRA]` tenant_api_keys Flyway migration; keys hashed at rest
- `[CODE]` POST /ci/check: X-Cartogra-Api-Key auth only (v1, no HMAC); persist check; blocking semantics; global envelope
- `[CODE]` Admin API: issue, list, revoke API keys
- `[CODE]` ci-extensions/github-action: call /ci/check; fail step on is_breaking
- `[CODE]` ci-extensions/azure-pipelines-task: same logic; marketplace packaging checklist
- `[DOCS]` Example GitHub Actions + Azure Pipelines YAML in docs/
- `[BIP]` blog: dual marketplace (GitHub Action + Azure Task)
- `[BIP]` thread: one check, two CI systems

**Spec discovery + Contract Hub UI (US3.3, US3.4, US3.5)**
- `[CODE]` Ingestion spec discovery consumer: detect openapi.yaml/asyncapi.yaml → publish spec-discovered; contract processor registers idempotently
- `[UI]` Contract Hub: side-by-side diff viewer; compatibility matrix heatmap; version timeline; breaking check queue
- `[DOCS]` Update docs/api/contract.openapi.yaml; extend runbooks for CI integration + notification troubleshooting
- `[BIP]` blog: consumer-driven contracts without ceremony
- `[BIP]` publish GitHub Marketplace action (or documented blocker + timeline)
- `[BIP]` publish Azure DevOps extension (or documented blocker + timeline)
- `[BIP]` thread: schema diff UI demo
- `[BIP]` LinkedIn: compatibility matrix story

### Phase 3 Gate
- `[GATE]` End-to-end in local compose: new spec → check → Kafka event → notification log (webhook mock OK)
- `[GATE]` CI extensions: failing check on breaking; passing on compatible
- `[GATE]` Contract OpenAPI + CI extension README complete
- `[GATE]` BIP3.1–BIP3.10 minimum published; marketplace publish or documented blocker

---

### Phase 4 — Intelligence layer

**Service skeleton + Claude client**
- `[CODE]` Intelligence module skeleton
- `[INFRA]` Intelligence Flyway V001 (analysis_runs)
- `[INFRA]` Intelligence Flyway V002 (anti_pattern_findings)
- `[INFRA]` Intelligence Flyway V003 (nl_query_log)
- `[CODE]` Claude client behind an interface; prompt templates in resources/prompts/ (nl-query-system.txt, anti-pattern-analysis.txt, digest-generation.txt)
- `[CODE]` Redis cache for repeated NL queries; per-tenant rate limits
- `[CODE]` Record analysis_runs with token count + latency on every AI call
- `[DOCS]` Write ADR: Claude integration + prompt strategy

**NL query (US4.1)**
- `[CODE]` SQL safety guardrails: allowlisted views/tables only; reject multi-statement; bind params; max rows; timeout; read-only transaction
- `[CODE]` POST /intelligence/query → {answer, data?, generated_sql, query_id}; HTTP envelope
- `[CODE]` POST /intelligence/query/{id}/feedback → update nl_query_log.feedback
- `[CODE]` Mock Claude client for CI (no real API calls in CI)
- `[TEST]` Golden tests for prompt formatting; SQL safety guardrail unit tests
- `[DOCS]` Update docs/api/intelligence.openapi.yaml with query + feedback endpoints
- `[BIP]` blog: NL over PostgreSQL + nl_query_log feedback loop
- `[BIP]` thread: token + latency tracking schema

**Anti-patterns + health score + digest (US4.2, US4.3)**
- `[CODE]` ≥3 anti-pattern types: circular_dependency, god_service, orphaned_service; evidence-first — deterministic metrics cited; LLM adds narrative only
- `[CODE]` POST /intelligence/analyze → Kafka analysis-requests → worker → analysis_runs + anti_pattern_findings
- `[CODE]` GET /findings; PUT /findings/{id}/acknowledge; PUT /findings/{id}/resolve
- `[CODE]` Architecture health score: versioned formula documented
- `[CODE]` Weekly digest: generate + store; GET /intelligence/digests/latest
- `[CODE]` Emit cartogra.intelligence.analysis-results
- `[TEST]` ≥3 anti-pattern types produce findings on fixture DB with known scenarios
- `[BIP]` thread: anti-pattern + graph evidence (deterministic first, LLM second)
- `[BIP]` thread: trust = deterministic evidence + LLM explanation

**Intelligence UI + evaluation (US4.4)**
- `[UI]` Chat panel: NL query input + answer + generated_sql toggle; thumbs up/down feedback
- `[UI]` Findings feed: severity chips; acknowledge/resolve actions
- `[UI]` Health score trend chart (Recharts); latest digest display
- `[TEST]` Evaluation set of NL questions against seed/fixture DB; pass rate documented
- `[DOCS]` Update docs/api/intelligence.openapi.yaml with all remaining endpoints
- `[BIP]` publish ADR: Claude integration + prompt strategy
- `[BIP]` blog: LLMs for infrastructure intelligence (honest limits)
- `[BIP]` video: NL query demo

### Phase 4 Gate
- `[GATE]` NL queries safe under threat model doc; abuse limits enforced
- `[GATE]` ≥3 anti-pattern types produce findings on fixture or seed data
- `[GATE]` Digest + health score available via API; UI surfaces them
- `[GATE]` BIP4.1–BIP4.5 minimum published

---

### Phase 5 — Production

**Seed data + demo hardening**
- `[INFRA]` Complete seed/seed-data.json: 20 Acme Fintech services with all demo scenarios (orphans, cycles, god service, drift, pending breaking change)
- `[CODE]` Seed loader: idempotent; hits real APIs; verify all scenarios populate correctly
- `[CODE]` Guest read-only demo mode: guest cannot mutate Acme tenant; logged-in users get sandbox; AI rate limits enforced
- `[CODE]` Sandbox tenant cleanup automation

**Helm + Terraform + staging CI**
- `[INFRA]` Helm umbrella chart + subcharts (gateway/registry/topology/contract/intelligence/ingestion); resource requests + limits; liveness/readiness probes; non-root securityContext
- `[INFRA]` Helm values: staging.values.yaml + prod.values.yaml
- `[INFRA]` Terraform modules: vpc, eks, rds, kafka-compatible, elasticache; environments/staging/
- `[INFRA]` Remote state: S3 + DynamoDB lock + encrypt=true
- `[INFRA]` deploy-staging.yml CI workflow; optional deploy-production.yml; release.yml
- `[TEST]` Smoke test job post-deploy in staging

**Observability + DLQ + operations**
- `[INFRA]` Full OTel → Jaeger pipeline in staging; trace correlation verified
- `[INFRA]` Micrometer → Prometheus + Grafana dashboards: consumer lag, error rate, p95 latency, token usage
- `[INFRA]` Alerts: consumer lag threshold, error rate spike
- `[CODE]` DLQ topic cartogra.platform.dead-letter: write on retry exhaustion; admin replay API; audit log per replay
- `[UI]` Operations view: ingestion connector status (last_sync_at, error counts); Kafka lag widget; link to Grafana
- `[DOCS]` Update runbooks: deployment.md + incident-response.md for observability, DLQ, ops view
- `[BIP]` blog: observability stack for the platform
- `[BIP]` blog: Flyway in multi-service monorepo

**Docusaurus + Playwright E2E**
- `[DOCS]` Docusaurus site: ADR index; API reference links; onboarding happy-path tutorial; cross-linked runbooks
- `[TEST]` Playwright E2E in CI: guest browse catalog → open graph → contract matrix → NL query (mock Claude in CI); guest cannot mutate Acme
- `[BIP]` blog: full data architecture retrospective (20+ tables, 16 topics, 2 SCM providers)
- `[BIP]` LinkedIn: ADR index + build-in-public meta

**Performance + domain + launch**
- `[TEST]` k6 scripts in perf/: GET /graph, POST /ci/check, GET /graph/impact/{id}; thresholds; fail CI on regression
- `[CODE]` DB index + MV refresh tuning from k6 results; document final p95 targets
- `[INFRA]` cartogra.dev: domain registration, ingress, cert-manager or provider TLS
- `[BIP]` thread: ship retrospective + lessons
- `[BIP]` thread: DLQ saved us — only if a real incident occurred, skip otherwise
- `[BIP]` video: 15-minute architecture walkthrough
- `[BIP]` launch post: demo site + how to try it

### Phase 5 Gate
- `[GATE]` Staging reproducible from docs; seed loads cleanly; guest demo matches all scenarios from implementation guide §7
- `[GATE]` Observability answers "What's broken?" and "Which consumer lags?" within 5 minutes for a maintainer
- `[GATE]` E2E + k6 gates pass in CI; thresholds documented
- `[GATE]` BIP5.1–BIP5.5 + BIP5.8 published

---

## Verification

After implementation:
1. Open `docs/execution-checklist.md` — it should render cleanly in GitHub markdown preview
2. Open `docs/plan.md` line 27 — the blockquote callout should appear between the italic preamble and the `# Cartogra — Implementation Plan` heading
3. Grep for `[BIP]` — items should appear distributed across weeks, not batched at phase ends
4. Grep for `[DONE]` — only S0.1, S0.2, S0.3 items should carry this prefix
5. Grep for `ADR-0003\|ADR-0004\|ADR-0005\|ADR-0006` — none should appear; all ADR references should be descriptive text only
