# Cartogra — Execution Checklist

## Phase 2 — Topology service

### System design and UX — Phase 2

- [x] 2.1 [UI] Run `/shape dependency graph` to produce a confirmed design brief for the D3 graph view, blast radius panel, SPOF findings, cycle warnings, drift overlay, and Risks page before writing any Phase 2 UI code. See `docs/design/phase-2-dependency-graph.md`.

### Topology foundation

- [x] 2.2 [CODE] Topology service skeleton + persistence. Spring Data JDBC, Flyway, OTel, virtual threads. Migrations: `Vxxx__create_dependencies.sql` (declared + observed edges, tenant isolation, self-edge guard, indexes), `Vxxx__create_dependency_drifts.sql` (resolution fields), `Vxxx__create_dependency_graph_view.sql` (materialized view + indexes + documented refresh strategy with debounce + advisory lock). Repositories for insert/update/delete, observed-edge enrichment, drift persistence. Hexagonal layout matching registry.

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

- [ ] 2.12a [CODE] Authenticated health endpoints. Extend `services` table with an optional `health_endpoint_bearer_token` column (AES-GCM encrypted at rest, key from env var `REGISTRY_HEALTH_TOKEN_ENCRYPTION_KEY`). `RestClientHealthChecker` injects `Authorization: Bearer <decrypted-token>` when the column is non-null. Semantics: no credential configured → 401/403 stays `PROBE_AUTH_FAILED` (established in 1.56); credential configured → 401/403 maps to `UNHEALTHY` (credential was rejected). `PATCH /v1/services/{id}` accepts optional `healthEndpointBearerToken`; stored encrypted, never returned in API responses. ITs cover: no token → 401 → `PROBE_AUTH_FAILED`; valid token → 200 → `HEALTHY`; wrong token → 401 → `UNHEALTHY`.
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
- [ ] [GATE] Phase 2 screens have passed `/improve`.
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

- [ ] 3.9 [CODE] Tenant API keys. Migration in gateway (hashed at rest, per-key scopes `ci:check`, `webhooks:push`, `catalog:read`, etc.). Gateway admin endpoints to issue/list/revoke keys with scope selection. UI: API key management page (list + create with scope selector + revoke with confirmation). Scope validation enforced at every API-key-authenticated endpoint. ITs cover happy + insufficient-scope paths. ADR written and accepted inline documenting the hashing/revocation design — replaces the phantom "ADR-0011" reference in `docs/bip/1.48-gateway-auth-rationale.md` (that number was cited as already written but no such file exists under `docs/adr/`; use the next available number, not 0011).
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
- [ ] [GATE] Phase 3 screens have passed `/improve`.
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
- [ ] [GATE] Phase 4 screens have passed `/improve`.
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
- [ ] [GATE] Phase 5 screens have passed `/improve`.
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

## Phase Future

- Users with multiple tenants
- OIDC
