# Cartogra — Product Roadmap

> **Plan of record.** First written 2026-08-18 against commit `d6922fa`. The old execution checklist is in the
> git history.

---

## Where we are (2026-11-23)

| Area | State |
|---|---|
| Gateway, Registry, Ingestion | Live: auth, catalog, SCM and Kubernetes discovery, ownership, audit trail, guest mode |
| Topology | Live: dependency graph, blast radius, SPOF, observed edges from OTel spans, drift |
| Contract | Phase 5 in progress: store, diff, breaking-change engine, spec discovery and consumers are done; CI check, GitHub Action, Slack and trusted OTLP intake are open |
| Intelligence | Phase 6: no code |
| Production | `cartogra.dev`, single-node k3s (ADR-0032) |

---

## Rules this roadmap follows

The old checklist broke each of these; that is why it stopped being usable.

1. **Linear.** A phase depends only on phases before it. No story references a later phase.
2. **One vertical per story.** API + UI + tests + OpenAPI ship together, or the story is not done.
3. **No screen ships on mock data.** A story that adds a screen removes its `MOCK_*` import.
4. **No topic without a consumer.** Producers are written when the consumer exists, not before.
5. **No story on data that does not exist.** If a story needs a column, an API, or an event that is not implemented, the story that creates it comes first — explicitly.
6. **Content is listed, never gating.** Every phase names the posts worth writing when it ships — as many as the work earns, sometimes none. They sit outside the gate checklist and never block a release.
7. **Done means demoable.** Every phase ends with a scripted demo a stranger could follow.

### Definition of done

**Every story, before it merges:**

- **Seed data.** A story that needs data to be demoable or testable extends the Acme Fintech seed (3.2) and says so in its roadmap item (`*Acme test:*` line: ✅ asserted by `pnpm acme verify`, 🔎 manual or asserted when the feature lands, ❌ seed work still needed, n/a not seed-testable).
- `/code-review` on the story's diff — every correctness finding fixed, or answered in the PR with a reason.
- `/security-review` on the same diff — anything above informational fixed before merge, or recorded as an accepted risk naming the compensating control.

**Every phase, before its gate closes:**

- `/impeccable audit` over every screen the phase created or changed. A phase that touched no UI records that fact rather than skipping silently.
- `/improve` (shadcn) over the services the phase touched. Its plans get triaged into fix-now / next-phase / won't-do — filed and forgotten does not count as run.
- `/improve-codebase-architecture` over the same scope. Architectural findings are fixed, or written up as an ADR that says why not.

**Tooling provenance**, so these gates are reproducible by anyone:

| Skill | Where it comes from |
|---|---|
| `/code-review`, `/security-review` | Ship with Claude Code — nothing to install |
| `/improve` | shadcn's skill — the `.claude/skills/improve` link was removed in `eb74504`, so re-add it via `npx skills add shadcn/improve` (README) |
| `/impeccable`, `/shape` | External: `npx impeccable install`, pinned version in `CONTRIBUTING.md` |
| `/improve-codebase-architecture` | External (mattpocock), pinned version in `CONTRIBUTING.md` — run before use |

---

## ✅ Phase 0 — Make the repo honest DONE

Phases 0–4 are done. Each line below is the item title only; the detail is in `plans/` and the git history.


No user-facing value. It exists because the next phases are unverifiable without it.

- [x] **0.1 — Topology enters CI.**
- [x] **0.2 — Topology test harness.**
- [x] **0.3 — Restore the decision record.**
- [x] **0.4 — Docs match code.**
- [x] **0.5 — Retire the old checklist.**
- [x] **0.6 — Make the gate tooling installable.**

**Worth writing about**

- *Your backlog is lying to you* — auditing an LLM-written roadmap against the code: items already shipped still open, stories citing APIs that were never written. (article)
- Deleting ADRs breaks more than docs: 40 dangling references and public posts pointing at files that no longer exist. (short post)

---

## ✅ Phase 1 — See the map DONE

**Goal:** a user can express and see how their services connect. This is the first time Cartogra does something a catalog cannot.

- [x] **1.1 — Graph nodes projected from the registry.**
- [x] **1.2 — Declared dependencies.**
- [x] **1.3 — Graph read API.**
- [x] **1.4 — Graph page.**
- [x] **1.5 — Dependencies on the service page.**
- [x] **1.6 — `docs/api/topology.openapi.yaml`**

**Demo:** register three services, draw two edges, open `/graph`, click a node, see its neighbours.

**Worth writing about**

- Building a read model from Kafka events instead of calling the owning service synchronously. (article)
- UUIDv5 plus a dedupe table: making a projection survive replay. (thread)
- A force-directed graph in plain D3, and why we did not reach for Cytoscape. (short post + clip)

---

## ✅ Phase 2 — Answer impact questions DONE

**Goal:** the graph stops being a picture and starts making decisions. This is the "seconds, not hours" promise in `PRODUCT.md`.

- [x] **2.1 — Blast radius.**
- [x] **2.2 — Impact panel + on-graph highlight.**
- [x] **2.3 — Cycles.**
- [x] **2.4 — Single points of failure.**
- [x] **2.5 — Risks page.**
- [x] **2.6 — Performance evidence.**

**Demo:** "if payments-api goes down, what breaks?" answered in one click, with the owning teams listed.

**Worth writing about**

- Blast radius in one recursive CTE: the SQL, the cycle guard, the depth cap. (deep dive)
- Materialized view + advisory lock + debounce: refreshing a graph without stalling writers. (article)
- A SPOF score is a heuristic, not a fact — so publish the threshold in the API response. (short post)
- 60-second clip: click a service, see what breaks. (video)

---

## ✅ Phase 3 — The map maintains itself DONE

**Goal:** remove manual upkeep — the reason internal service catalogs die.

- [x] **3.1 — Observed edges from traces.**
- [x] **3.2 — Acme Fintech seed.**
- [x] **3.3 — Drift detection.**
- [x] **3.4 — CODEOWNERS team mapping (orphan risk from ownership events).**
- [x] **3.5 — Topology events.** Deferred: `dependency.*` moved to 5.4, `drift.detected` to 6.7.
- [x] **3.6 — Unresolved span services.**
- [x] **3.7 — Messaging and DB edges.**

**Demo:** load the Acme Fintech seed and run its traffic generator, watch an edge appear on the graph that nobody declared, resolve the drift.

**Worth writing about**

- Declared versus observed dependencies: the gap between them *is* the product. (article)
- Turning OTel spans into graph edges: peer resolution, sampling, and what you deliberately throw away. (deep dive)
- Drift as a workflow, not an alert. (short post)

---

## ✅ Phase 4 — A product a stranger can try DONE

**Goal:** the first public artifact. Moved forward from the old plan's Phase 5 — nothing is validated until someone outside can click it. It runs on k3s with 7.1's Helm charts pulled in (ADR-0030). Plan: `plans/021-phase4-public-demo.md`.

- [x] **4.1 — Audit trail.**
- [x] **4.2 — Dashboard on real data.**
- [x] **4.3 — Guest read-only mode.**
- [x] **4.4 — Collector in the deployable compose.**
- [x] **7.1 — Helm charts (pulled forward from Phase 7).**
- [x] **4.5 — One public environment.**

**Demo:** send someone a link. They browse Acme Fintech, run a blast radius, and cannot change anything.

**Worth writing about**

- A cross-service audit trail through one port and one topic — no shared table. (article)
- Guest mode without an anonymous auth path. (short post)
- Seed data as product storytelling: designing a fake company that shows real value. (article)
- The demo link itself, with the three questions it answers. (launch-ish post)

---

## ⬜ Phase 5 — Change safety: contracts

**Goal:** the second product pillar — catch breaking API changes before merge. Start with the smallest loop that a CI pipeline can block on. OpenAPI 3.x only; AsyncAPI is a candidate story once a user brings an AsyncAPI spec.

Stories are listed in execution order; numbers are kept stable for their issues, so 5.10 runs first and 5.8 runs after 5.3.

- [x] **5.10 — One topic per entity.** Lifecycle events of one entity share one topic, keyed by entity id, with the event in the envelope's `event_type` — Kafka orders only within a topic, so an `updated` can no longer be read after its `deleted`. Registry's `service.{registered,updated,deleted}` become `cartogra.registry.service.changed`, and `team.{created,updated,deleted}` become `cartogra.registry.team.changed`; topology's `GraphNodeEventConsumer` and its DLQ move with them. Cut over in one deploy, old topics deleted. The naming rule becomes `cartogra.{domain}.{entity}.changed` for lifecycle events (`backend.md`, `patterns.md`, `kafka-topics.md`); single-event topics (`audit.recorded`, …) keep their names. Later, plan 030 moved `ingestion.service.discovered` to `cartogra.ingestion.service.changed` (`service.{discovered,removed,resynced}`); the old topic is deleted. Runs before 5.1 so contract's consumer is written once, against the new topic.
  *Acme test:* ✅ `verify` unchanged: graph nodes still appear after `load`, now through the new topic.
- [x] **5.1 — Contract store.** Contract service skeleton + `api_contracts` (one per service), `contract_versions` (`contract_checks` moved to 5.5, where its writer and 5.3's `changes[]` shape exist). `POST /v1/contracts` and `/versions` parse and validate OpenAPI 3.x, canonicalize, store `spec_hash`. Invalid specs return envelope errors. New-service plumbing in the same story: gateway `contract` route + same-named circuit breaker, Helm subchart + umbrella entry, compose service, contract steps enabled in `ci.yml`/`ci-full.yml`. Writes are `@PreAuthorize`-guarded and added to the 4.3 guest-403 IT; every contract write publishes `cartogra.platform.audit.recorded` (ADR-0017). Consumes `cartogra.registry.service.changed` (5.10) and, on `event_type = service.deleted`, soft-deletes that service's contract and versions, idempotently.
  *Acme test:* ❌ repos hold one `openapi.yaml` each; needs a second version of one spec (`repos` push of v2).
- [x] **5.2 — Diff + timeline.** `GET /v1/contracts` (paginated) and `/versions/{v}/diff` returning a structured diff against the previous version. Contract list + side-by-side diff + version timeline in the UI, no compatibility status yet (5.3 adds it); `contracts.tsx` drops `MOCK_CONTRACTS`.
  *Acme test:* ❌ same as 5.1: a v1 → v2 pair for the diff and timeline.
- [x] **5.3 — Breaking-change engine.** Own rule engine, direction-aware: removed path or operation; removed response field; field or parameter type change; new required request field or parameter; enum value removed from a request or added to a response. Result is binary — `breaking` | `compatible` — as `is_breaking` + `changes[]`. Adds the status badge and filter to the contract list. Golden tests over compatible and breaking pairs, including request-vs-response cases of the same change.
  *Acme test:* ✅ `pnpm acme load --breaking` publishes ledger-service v3 (one v1 path removed, a required `currency` query parameter added); `pnpm acme verify --breaking` asserts v3 is breaking with `path-removed` and `parameter-required-added`, v2 is compatible (`path-added`), and `?status=breaking` lists ledger-service.
- [x] **5.8 — Spec discovery.** Ingestion publishes `cartogra.ingestion.spec.discovered` after each sync, with no `service_id`: ingestion cannot know it (Registry makes random ids keyed by `external_id`, ADR-0021). Contract resolves the repository to a service through its `services.external_id` projection or a Registry lookup, keeps a spec that comes before its service as PENDING, and skips a spec only when it equals the last spec seen for `(tenant_id, repository_full_name, file_path)` (a revert publishes again), into 5.1 (ADR-0018, amended). Runs before 5.5 so a merged change becomes the next baseline without a manual `POST /versions`. Admins add custom probe paths in Settings → SCM Configuration.
  *Acme test:* ✅ `verifySpecDiscovery`: with an SCM connection, each seed service has a contract, and each service with no manual seed versions has a `source=discovery` version; with no SCM connection it prints SKIP.
- [x] **5.4 — Consumers from the graph.** No manual consumer table: affected consumers are the services with inbound declared or observed edges to the contract's service. Topology publishes `dependency.{declared,observed,removed}` on one topic, `cartogra.topology.dependency.changed`, keyed by edge id (5.10's rule; pulled forward from 6.7; contract is the consumer, so rule 4 holds); contract projects them into a local inbound-edge table (`inbound_edges`), idempotent on edge id. Topology writes them through a transactional outbox in `shared/web`, and a one-time migration backfilled the edges that existed before the topic. A Consumer is a live `http` or `grpc` edge; kafka and db edges do not call the API. Surfaced in contract detail and feeding `affected_consumers`. Endpoint-level matching (which consumer calls which path) is 6.6.
  *Acme test:* ✅ `verify` checks it: the 43 declared edges give each contract its consumers (`ledger-service` has 12).
- [x] **5.5 — CI check.** Tenant API keys (hashed, scoped, create/revoke audited; the migration lands in the **registry** schema — the gateway has no Flyway setup today, contrary to the old plan) + `POST /v1/contracts/check` authenticated by `X-Cartogra-Api-Key` with scope `ci:check`. Body `{service, spec}` (service id or slug); diffs the candidate against the latest stored version and returns `{is_breaking, changes[], affected_consumers[]}`; records a `contract_checks` row (table created in this story), never creates a version; 404 when the service has no baseline. API key management page replaces the "coming soon" stub.
  *Acme test:* ✅ `verify` posts the `--breaking` spec to `/v1/contracts/check` and asserts `is_breaking` and the consumers named.
- [x] **5.6 — GitHub Action.** One CI integration, published under `ci-extensions/`, blocking on breaking changes. Azure Pipelines only when a user asks for it. The Action is `ci-extensions/github-action/`, with a Marketplace copy `allanweber/cartogra-contract-check` synced by the tag `action-vX.Y.Z` (ADR-0033). The Acme GitHub mirror gets the workflow. Correction for the Acme test: under the 5.3 rules a removed request field is compatible, so the test pull request removes a response field or a path.
  *Acme test:* ✅ on `Acme-cartogra/ledger-service`, a PR that removes a path fails the Action (1 breaking change, 12 affected consumers), and a PR that adds a path passes.
- [x] **5.7 — Notifications on breaking checks.** Contract owns `notification_rules` + `notification_log` and sends to Slack, Teams, and email from one tenant rule with an optional Service scope. A breaking check returns before a virtual thread sends to every matching enabled rule. Each try writes a log row, with three tries and waits of 1 s and 2 s. Slack and Teams receive one text payload; Contract sends email directly to Resend. Rule changes are audited with no destination value. The settings page replaces the notifications stub with rules, attempts, and a test button. No Kafka topic carries a check result.
  *Acme test:* n/a: needs a real Slack webhook; an IT covers delivery against a stub server.
- [ ] **5.9 — Trusted OTLP intake.** A gateway OTLP/HTTP endpoint `POST /v1/otlp/traces`, authenticated by `X-Cartogra-Api-Key` with a new scope `spans:write`. The gateway overwrites `cartogra.tenant.id` from the key and forwards to the collector; once it exists the collector stops accepting unauthenticated external traffic. This story drops the per-tenant `CARTOGRA_TENANT_ID` collector env var (still set in `values-production.yaml`), and the in-cluster Acme pods send through the gateway too. Settings → SCM Configuration → OpenTelemetry card (currently an inactive placeholder, `settings.connections.tsx`): create/revoke a `spans:write` key (hashed, shown once), show the endpoint URL and a copy-paste snippet (`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_HEADERS=X-Cartogra-Api-Key=<key>`), and status — last span received, edges created, quarantined count — read from a per-tenant stats row topology persists (the `cartogra.spans.*` counters carry no tenant). Depends on 5.5.
  *Acme test:* ❌ add an `ACME_OTLP_HEADERS` option so pods send through the gateway with an API key.

**Demo:** open a PR that deletes a required field; CI fails with the affected consumers named; Slack shows it.

**Gate**

- [ ] A PR deleting a required field fails the GitHub Action; a compatible change passes.
- [ ] API key scopes enforced (missing `ci:check` → 403); keys hashed at rest; revocation takes effect immediately.
- [ ] Golden diff tests cover OpenAPI compatible and breaking pairs, including request-vs-response direction cases.
- [ ] The CI check names affected consumers from topology edges; a `dependency.removed` drops that consumer.
- [ ] Slack delivery logged with retry and backoff; the failure path is recorded, not silent.
- [ ] Spec discovery skips a spec only when it equals the last spec seen for `(tenant_id, repository_full_name, file_path)`.
- [ ] Deleting a service soft-deletes its contract.
- [ ] OTLP intake: no key → 401, key without `spans:write` → 403, `cartogra.tenant.id` overwritten from the key.
- [ ] `MOCK_CONTRACTS` is deleted.
- [ ] Lifecycle events use one topic per entity (`registry.service.changed`, `registry.team.changed`, `topology.dependency.changed`); the per-event registry topics no longer exist.

*Standing checks (§2 — Definition of done):*

- [ ] Every story in this phase passed `/code-review` and `/security-review` before merge.
- [ ] `/impeccable audit` run over every screen this phase touched; findings fixed or recorded.
- [ ] `/impeccable critique` run over every screen this phase touched; findings fixed or recorded.
- [ ] `/improve` run over the services this phase touched; its plans triaged, blockers fixed.
- [ ] `/improve-codebase-architecture` run; findings fixed or captured in an ADR.

**Worth writing about**

- Structured diffs over OpenAPI specs: canonicalization, `spec_hash`, and why text diff fails. (deep dive)
- The breaking-change rule set, in full, with the cases we chose not to flag. (article)
- Hashing and scoping tenant API keys. (short post)
- Shipping a GitHub Action that blocks a merge on your own API. (article)
- Screen recording: PR blocked, affected consumers named. (video)

---

## ⬜ Phase 6 — Intelligence

**Goal:** explanation on top of evidence. Deliberately after Phases 1–5, because an LLM narrating an empty graph is a demo, not a product.

- [ ] **6.1 — Deterministic findings.** Intelligence service + `analysis_runs`, `anti_pattern_findings`. Circular dependency, god service, orphaned service — each with structured evidence (cycle path, fan-in count, days since last commit). Fixture ITs assert exact findings on known shapes.
  *Acme test:* ✅ circular (2 cycles), god service (`ledger-service`), orphans (3). ❌ "days since last commit": all repos have one fresh commit; backdate one.
- [ ] **6.2 — Narrative layer.** Claude client with externalized prompts, Redis cache, per-tenant rate limits, token/latency persisted per request. The model writes prose over 6.1's evidence and never invents a fact. ADR-0031.
  *Acme test:* 🔎 shares 6.1's findings as input.
- [ ] **6.3 — Findings feed.** `POST /v1/intelligence/analyze`, `GET /findings`, acknowledge/resolve. `intelligence.tsx` replaces its placeholder.
  *Acme test:* 🔎 shares 6.1's findings as input.
- [ ] **6.4 — NL query, guarded.** Allowlisted views only, read-only role, bound parameters, row limit, statement timeout. `POST /v1/intelligence/query` returns `{answer, data?, generated_sql?, query_id}` + feedback endpoint. Committed eval set under `src/test/resources/nl-eval/` with a stated pass bar.
  *Acme test:* 🔎 manual queries over the Acme tenant ("which services have no owner?" → 3).
- [ ] **6.5 — Health score + weekly digest**, persisted as trends, surfaced in the same panel.
  *Acme test:* 🔎 a score over the Acme tenant; trends need repeated runs.
- [ ] **6.6 — Endpoint-level consumers** (refines 5.4): match span `url.full` against each contract's servers and paths, so a breaking change names only the consumers that call the changed operation, not every service with an edge.
  *Acme test:* ❌ spans carry no `url.full`; add it (paths match each repo's `openapi.yaml`).
- [ ] **6.7 — `drift.detected` published** (deferred from 3.5), now that Intelligence consumes it. `dependency.{declared,observed,removed}` already ship in 5.4.
  *Acme test:* n/a: deferred.

- [ ] **6.8 — Deployed contract version** (refines 5.4). A consumer does not select a contract version: it calls the version that is deployed. Contract shows which version runs, for example "v3 is published, v2 is deployed, so v3's breaking change has not reached the 12 consumers yet". K8s discovery reads the container image tag of each workload and an optional annotation `cartogra.dev/contract-version`. Without the annotation, contract matches the image tag to the spec `info.version` of a stored version. If nothing matches, the deployed version is "unknown", never a guess. Contract detail shows a "deployed" marker on the timeline, and 5.5's check can tell whether the breaking change compares with the deployed version or with a version that is not deployed. Prerequisite: K8s discovery does not read images today, and there is no deploy history (see the anomaly-detection cut below). This story keeps only the current deployed version, not a history.
  *Acme test:* ❌ the acme fake-service Deployments use one generic image; add a per-service image tag or the annotation so that `ledger-service` reports a deployed version.

**Cut from the old plan, with reasons:** *AI ownership suggestions* assumed `ScmProvider.getCommitHistory` — the interface has `getLastCommit` only, so the prerequisite is a new SCM API and 90 days of commit aggregation; it is not a story until someone builds that. *Anomaly detection* assumed `services.last_deploy_at` history — no such column, no deploy-event ingestion, no history table. Both return as candidates once their data sources exist.

**Demo:** ask "which services have no owner and the most dependents?" in plain English; get the answer, the evidence, and the generated SQL.

**Gate**

- [ ] Three anti-pattern types produce findings with structured evidence on fixtures.
- [ ] The narrative layer cites only fields present in the evidence payload — a test asserts no invented service or team names.
- [ ] The NL eval set passes its documented bar; unsafe SQL cases are rejected, not sanitized.
- [ ] Token count and latency persisted per AI request and visible in Grafana.

*Standing checks (§2 — Definition of done):*

- [ ] Every story in this phase passed `/code-review` and `/security-review` before merge.
- [ ] `/impeccable audit` run over every screen this phase touched; findings fixed or recorded.
- [ ] `/impeccable critique` run over every screen this phase touched; findings fixed or recorded.
- [ ] `/improve` run over the services this phase touched; its plans triaged, blockers fixed.
- [ ] `/improve-codebase-architecture` run; findings fixed or captured in an ADR.

**Worth writing about**

- Evidence first, narrative second: letting an LLM explain without letting it invent. (article)
- Natural language over PostgreSQL with allowlists, bound parameters, row limits and timeouts. (deep dive)
- Tracking tokens and latency per tenant request. (short post)
- The trust model users need before they believe an AI finding. (article)
- NL query demo against the live Acme tenant. (video)

---

## ⬜ Phase 7 — Production hardening

Triggered by real users, not by a calendar. Before this, Phase 4's single-node k3s host is enough.

- [ ] ~~**7.1** Helm charts per service + umbrella~~ — moved into Phase 4 (ADR-0030); Phase 7 adds staging values and HA settings.
- [ ] **7.2** Terraform modules + one staging environment + remote state (S3 + DynamoDB lock + encryption + versioning).
- [ ] **7.3** Staging deploy pipeline + `:smoke:test` (readiness on every service, one authenticated call each, envelope conformance) gating the deploy.
- [ ] **7.4** NetworkPolicy restricting services to gateway-namespace ingress + short-lived `X-Gateway-Token` validated by a shared filter in `shared:common`; IT proves direct access is rejected.
- [ ] **7.5** DLQ (`cartogra.platform.dead-letter`) + admin replay writing audit events; IT proves poison → DLQ → replay.
- [ ] **7.6** Grafana dashboards auto-provisioned per service + platform overview; alerts on consumer lag, error rate, MV refresh duration.
- [ ] **7.7** Playwright E2E (guest browse, graph exploration, contract check, one NL query) + k6 thresholds committed under `infra/perf/results/`.
- [ ] **7.8** Docs site with the ADR index and runbooks.

- [ ] **7.9** Cartogra emits its own CLIENT spans. Replace the static `RestClient.builder()` calls (topology `RegistryMembershipClient`, contract `RegistryNameClient`, ingestion `RegistryPlanLimitClient`, registry `HttpClientConfig`) with the injected, instrumented `RestClient.Builder`; drop the gateway `AppConfig` builder override; turn on Kafka template/listener observation. IT asserts a CLIENT span with `server.address`.

**Demo:** kill a pod mid-demo; readiness pulls it, Grafana shows it, nothing user-visible breaks.

**Gate**

- [ ] Staging rebuilt from the docs alone, by following the runbook and nothing else.
- [ ] `:smoke:test` gates the deploy — a failing readiness probe blocks it.
- [ ] Poison message → DLQ → replay, with audit rows for the replay.
- [ ] A direct call to a service bypassing the gateway is rejected (NetworkPolicy + service-token IT).
- [ ] Playwright and k6 thresholds enforced in CI, not advisory.

*Standing checks (§2 — Definition of done):*

- [ ] Every story in this phase passed `/code-review` and `/security-review` before merge.
- [ ] `/impeccable audit` run over every screen this phase touched; findings fixed or recorded.
- [ ] `/impeccable critique` run over every screen this phase touched; findings fixed or recorded.
- [ ] `/improve` run over the services this phase touched; its plans triaged, blockers fixed.
- [ ] `/improve-codebase-architecture` run; findings fixed or captured in an ADR.

**Worth writing about**

- Flyway in a multi-service monorepo sharing one Postgres. (article)
- The observability stack that answers "what is broken" in under a minute. (deep dive)
- Before/after k6 numbers from index and MV tuning. (short post)
- A real DLQ incident — only if one happens, and only if the write-up is honest. (post)

---

## ⬜ Phase 8 — Commercial (only when someone wants to pay)

Partially built already: `billing_plans` (V010/V013), `tenants.plan_id` (V011), `PlanLimitService`, and the plan-limit advisory lock exist.

- [ ] **8.1** Stripe checkout + customer portal + webhook receiver; `tenant_subscriptions`, `billing_events`; `PlanEnforcementFilter` with a Redis-cached plan tier returning 402 on overage.
- [ ] **8.2** `/settings/billing` page.
- [ ] **8.3** GDPR erasure: admin + re-auth, anonymize users and audit events, `tenant.erased` event, 30-day hard delete, signed receipt.

Moved out of the old Phase 3 because none of it tests the product hypothesis, and all of it blocked contract work behind Stripe.

**Demo:** a new tenant subscribes, exceeds the free service limit, and is upgraded through the portal without support involvement.

**Gate**

- [ ] Checkout → active subscription → portal → webhook update, end to end, with signature verification.
- [ ] Overage returns 402 in the standard envelope; the cached plan tier invalidates correctly.
- [ ] GDPR erase produces a signed receipt, anonymizes users and audit events, and schedules the hard delete.

*Standing checks (§2 — Definition of done):*

- [ ] Every story in this phase passed `/code-review` and `/security-review` before merge.
- [ ] `/impeccable audit` run over every screen this phase touched; findings fixed or recorded.
- [ ] `/impeccable critique` run over every screen this phase touched; findings fixed or recorded.
- [ ] `/improve` run over the services this phase touched; its plans triaged, blockers fixed.
- [ ] `/improve-codebase-architecture` run; findings fixed or captured in an ADR.

**Worth writing about**

- Metering a B2B platform on plan limits that already existed in the schema. (article)
- The full data-architecture retrospective: the schema at launch versus the schema on day one. (deep dive)
- Launch post with the live demo link and the how-to-try-it path. (post)

---

## Not in the plan

**Content is inside the phases now, not here.** The old checklist carried 31 build-in-public items as numbered backlog entries and made every phase gate depend on them ("minimum BIP set shipped"). Each phase above ends with *Worth writing about* — as many pieces as that phase actually earns, sometimes four, sometimes two — sitting outside the gate checklist. Items whose subject got cut (dual-marketplace, one-check-two-CI-systems, the marketplace listing) are gone with it.

**Removed as unexecutable or already done:**

| Old item | Why it is gone |
|---|---|
| "Write ADR-0016…0021" | Those numbers belong to ADRs deleted in `e6f0b45`; new ADRs start at 0028 |
| 2.10 full-text search, 2.11 pagination | Already shipped — `to_tsvector` + GIN index (V005), `limit`/`offset` on services and teams, `search` wired in `catalog.index.tsx`. Only audit-event pagination remained, and it moved into 4.1 |
| Gateway-owned Flyway migrations (API keys, billing) | Gateway has no Flyway and shares the `registry` schema; the plan assumed an ownership split that does not exist |
| ~12 speculative Kafka topics + "Notification worker" context | No producer, no consumer, no module. Violates the project's own no-speculative-topics rule |
| Azure Pipelines extension, Teams channel, dual-marketplace listing | Duplicate integrations before the first one has a user |

**Parking lot (research, unscheduled):** gRPC for internal calls, Avro + Schema Registry, SSE/WebSocket live graph updates, multi-region, users in multiple tenants, tenant-level OIDC beyond what ships today.

---

## Sequencing at a glance

```
P0 repo truth  →  P1 see the map  →  P2 answer impact  →  P3 self-maintaining
                                                              │
                                            P4 public demo ◄──┘
                                                 │
                                     P5 contracts + CI check
                                                 │
                                        P6 intelligence
                                                 │
                                    P7 production   →   P8 commercial
```

**P0–P4** deliver a public, self-maintaining dependency map with an audit trail — the smallest thing that is recognisably Cartogra rather than a catalog. P5 adds the contract pillar, P6 the intelligence pillar.

No time estimates: the work is executed by AI agents, so human-pace weeks mean nothing. If scope must shrink, cut Phase 5 to 5.10, 5.1, 5.3, 5.8, 5.5, 5.6 (topics, store, engine, discovery, check, Action) rather than thinning every story.

