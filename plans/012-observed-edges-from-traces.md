# 3.1 — Observed edges from traces
Issue: #120  ·  Branch: `feat/3.1-observed-edges`  ·  ADR: 0029 (new)

## Context
Phase 3 aims to stop the catalog relying on manual upkeep. 3.1 turns OTel spans into `dependency_type='observed'` edges, so the graph shows calls that really happen. Facts found while exploring:
- **Schema is ready.** `V001__create_dependencies.sql` already allows `'observed'`. Its partial unique index `(tenant_id, source_service_id, target_service_id, dependency_type, protocol) WHERE deleted_at IS NULL` is the edge identity. `DependencyType.OBSERVED` exists. No production code writes observed edges yet.
- **ADR-0016 (Proposed) won't work as written.**
  - The collector `kafka` exporter emits raw OTLP and cannot wrap it in `EventEnvelope`.
  - Nothing sets `tenant.id` on spans.
  - Roadmap/issue cite ADR-0028, but that number is taken (orphan risk).
- **Graph view refresh is manual.** Nothing triggers a `dependency_graph_edges` MV refresh automatically; writers must call `DependencyGraphViewRefreshScheduler.markDirty()`.
- **No name→node lookup** exists. Registry guarantees `UNIQUE (tenant_id, lower(name)) WHERE deleted_at IS NULL`.
- **Blast radius and SPOF already count all edge types.** That is kept deliberately, because observed calls are real dependencies.
- **Existing tests need care:**
  - `GraphNodeEventConsumerIT` waits for assignment on *every* listener container, so a new listener breaks it.
  - Frontend `graph.tsx:214-220` always shows the "No observed dependencies yet" alert on Observed.
  - e2e `demo-flow.spec.ts:216-217` asserts that alert.
- **Collector is dev-compose only** (`otel-collector-contrib:0.151.0`, so the kafka exporter is available). It doesn't depend on kafka yet.

## Decisions
| # | Decision | Choice | Why |
|---|---|---|---|
| D1 | ADR number | ADR-0029 for this; guest-mode item's ADR → 0030; ADR-0016 → Superseded by 0029 | 0028 is taken |
| D2 | Span tenant | Resource attr `cartogra.tenant.id`; dev collector `insert`s it from `${env:CARTOGRA_TENANT_ID}` (only if absent, so the e2e can set its own). ADR documents that it's unauthenticated until 5.5 API keys | works now, honest about the trust gap |
| D3 | Bad tenant | Missing / not UUID / tenant with no live `graph_nodes` → throw non-retryable `UnattributedSpanBatchException(reason)`. Existing `DefaultErrorHandler` + DLQ sends it to `cartogra.observability.spans.dlq`. Counter `cartogra.spans.quarantined{reason}` | reuses the existing DLQ pattern |
| D4 | Wire format | Exporter `encoding: otlp_json`; Jackson 3 parses into minimal records. The topic is a documented exception to the Kafka envelope rule | no new deps, readable fixtures reusable against the collector |
| D5 | Target, primary | kind=CLIENT: target = `peer.service`, else host of `server.address` (port stripped) | stateless |
| D6 | Target, fallback | Join on span IDs via table `pending_span_links`. CLIENT spans that didn't resolve wait for a SERVER span with `parent_span_id = span_id`; SERVER spans with an unseen parent wait for their CLIENT. **On match, both halves are hard-deleted.** Reaper removes rows past `expires_at` (TTL 5 min, configurable) | survives restarts and multiple instances, works in any arrival order |
| D7 | Protocol | `rpc.system=grpc` → grpc; `http.request.method`/`http.method` present → http; anything else is skipped (messaging/db out of scope) | CHECK constraint, scope |
| D8 | Name match | `lower(service.name)` = `lower(graph_nodes.name)`, live rows only | catalog already unique per lower(name) |
| D9 | Unknown names | Upsert `unresolved_span_services` (one row per tenant + lower(name): first/last seen, seen_count). Only `service.name` and `peer.service` names are recorded; never `server.address` hosts/IPs | review later, no noise |
| D10 | Freshness | New `dependencies.last_seen_at` (null for declared); update only if more than 1 min old | 3.3 drift needs it |
| D11 | Impact calcs | Blast radius / SPOF / cycles keep counting observed edges; no code change, documented in ADR | matches reality |
| D12 | Roadmap reshuffle | 4.3 Acme seed → **3.2**; 3.2/3.3/3.4 → 3.3/3.4/3.5; Phase 4 renumbers (4.4→4.3 …). New §2 DoD rule: "every feature that needs data extends the Acme seed and says so in its roadmap item". New 3.2 replaces `seed/graph-perf-seed.sql` and sends its observed-edge spans through the collector. Issues retitled | seed usable for the rest of the roadmap |
| D13 | Manual feed | No throwaway script in 3.1; the new 3.2 seed covers it | |

## Implementation steps

### Step 0 — Housekeeping (before code)
1. ~~Save plan to `plans/` + make it a rule (skill, CLAUDE.md, memory).~~ Done 2026-10-07.
2. **Edit `docs/roadmap.md`:**
   - Phase 3 list: new 3.2 = Acme Fintech seed. Its text is moved from 4.3 and extended with: replaces `seed/graph-perf-seed.sql`; observed edges/drift fed via collector spans.
   - Renumber the rest of Phase 3 and all of Phase 4, plus cross-references (e.g. "3.3 orphan risk", "2.5" refs stay).
   - 3.1 text: ADR-0028 → ADR-0029. Guest-mode item: ADR-0029 → ADR-0030.
   - §2 DoD: add the seed rule.
   - Phase 3 demo/gate: mention the seed.
   - **New Phase 3 items** (after 3.5):
     - **3.6 — Unresolved span services.** `GET /v1/unresolved-span-services` (paginated, sorted by `seen_count`) + `POST …/{id}/dismiss` (soft delete). A list in the UI next to drift, with a "create service" shortcut that pre-fills the registry form. Reads the table that 3.1 fills.
     - **3.7 — Messaging and DB edges.** PRODUCER/CONSUMER spans joined on `messaging.destination.name` (topic) give `kafka` edges between the services on each side. CLIENT spans with `db.system` give `db` edges, but only when the DB matches a catalog service by name. Reuses `pending_span_links`.
   - **Phase 7:** new **7.9 — Cartogra emits its own CLIENT spans.** Appended after 7.8, so nothing renumbers. It fits with 7.6 dashboards. Replace the static `RestClient.builder()` calls (topology `RegistryGraphNodeClient`/`RegistryMembershipClient`, ingestion `RegistryPlanLimitClient`, registry `HttpClientConfig`) with the injected, instrumented `RestClient.Builder`. Drop the gateway `AppConfig` builder override. Turn on Kafka template/listener observation. IT asserts a CLIENT span with `server.address`.
   - **Phase 4:** after 4.3 moves out, guest mode becomes 4.3. New **4.4 — Collector in the deployable compose:** add `otel-collector` (same `otel-collector.yml`) to `docker-compose.yml`, wire every service's `OTEL_EXPORTER_OTLP_ENDPOINT` to it, and add a `kafka` dependency. One public environment stays **4.5** and depends on 4.4.
   - **Phase 5:** new **5.9 — Trusted OTLP intake.** A gateway OTLP/HTTP endpoint `POST /v1/otlp/traces`, authenticated by `X-Cartogra-Api-Key` with a new scope `spans:write`. The gateway overwrites `cartogra.tenant.id` from the key and forwards to the collector. Once it exists, the collector stops accepting unauthenticated external traffic. Depends on 5.5. Appended rather than inserted, so 5.6–5.8 keep their numbers.
   - Update `docs/architecture/topology-performance.md:37`'s reference to the old seed file only if 3.2 has landed (no; leave it and note in 3.2).
3. **GitHub issues** (`gh issue edit`):
   - #127 → title `[3.2] Acme Fintech seed`, body updated, label `phase-4`→`phase-3`.
   - #121 → `[3.3] Drift detection`; #122 → `[3.4] …`; #123 → `[3.5] …`.
   - Phase 4: #128 → `[4.3] Guest read-only mode`; #129 stays `[4.5]`, body notes it depends on 4.4.
   - **Create** (labels `user-story` + phase + service): `[3.6] Unresolved span services` (phase-3, topology, frontend), `[3.7] Messaging and DB observed edges` (phase-3, topology), `[7.9] Cartogra emits its own CLIENT spans` (phase-7), `[4.4] Collector in the deployable compose` (phase-4), `[5.9] Trusted OTLP intake` (phase-5, gateway). Each body follows the existing issue template (roadmap §, text, DoD checklist).
   - Phase gates #124 / #130 / #139: add or retitle the items if they're listed.
   - #120 body: ADR-0028 → ADR-0029.
   - Gate #124 if it lists item numbers.
4. **Write `docs/adr/0029-observed-edges-from-otel-spans.md`** covering D2–D11. Mark ADR-0016 `Superseded by ADR-0029` and add 0029 to `docs/adr/README.md`.
5. **Update docs:**
   - `services/topology/CONTEXT.md`: observed edges come from `OtelSpanWorker` in Topology, not Ingestion; remove the `cartogra.ingestion.dependency.observed` framing.
   - `docs/architecture/kafka-topics.md`: payload = raw OTLP JSON, envelope exception, `.dlq`.

### Step 1 — Migration `services/topology/src/main/resources/db/migration/V011__observed_edges.sql`
- `ALTER TABLE dependencies ADD COLUMN last_seen_at TIMESTAMPTZ;`
- `CREATE INDEX ON graph_nodes (tenant_id, lower(name)) WHERE deleted_at IS NULL;`
- **`pending_span_links`:**
  - Columns: `id` UUID PK, `tenant_id` NOT NULL, `trace_id` TEXT, `span_id` TEXT, `parent_span_id` TEXT NULL, `kind` TEXT CHECK in ('client','server'), `service_name` TEXT, `protocol` TEXT NULL, `created_at`, `expires_at` TIMESTAMPTZ.
  - Indexes on `(tenant_id, trace_id, span_id)` and `(tenant_id, trace_id, parent_span_id)`, plus `(expires_at)`. RLS.
  - Comment: transient join buffer, hard deletes are intentional (not domain rows).
- **`unresolved_span_services`:** `id`, `tenant_id`, `name`, `first_seen_at`, `last_seen_at`, `seen_count BIGINT`, `created_at`, `updated_at`, `deleted_at`, plus `UNIQUE (tenant_id, lower(name)) WHERE deleted_at IS NULL` and RLS.
- Recreate MV `dependency_graph_edges` only if it needs `last_seen_at` (it doesn't for 3.1; leave it).

### Step 2 — Span parsing (`infrastructure/otel/`)
- Records `OtlpTraceRequest` → `ResourceSpans(resource, scopeSpans)` → `Span(traceId, spanId, parentSpanId, kind, attributes)` with an `AnyValue` helper (stringValue/intValue). Use `@JsonIgnoreProperties(ignoreUnknown=true)`, Jackson 3 (`tools.jackson`).
- `OtlpSpanMapper` produces domain `SpanObservation(tenantRaw, serviceName, kind, traceId, spanId, parentSpanId, peerName, peerHost, protocol)`. Protocol mapping per D7; host stripping per D5.

### Step 3 — Domain (`domain/`)
- **`ObservedDependencyService`** (one `@Service`), `ingest(tenantId, List<SpanObservation>)`, in one transaction per batch:
  1. Resolve all distinct names in one query: `GraphNodeRepository.findLiveByLowerNames(tenantId, names)` → `Map<lowerName, serviceId>`.
  2. For each CLIENT span: try the D5 primary.
     - Resolved: upsert edge.
     - Not resolved: record the unknown name (D9, unless host-only), then try `pending_span_links` for a SERVER partner. On match, upsert edge + delete the partner; otherwise insert pending.
  3. For each SERVER span with `parentSpanId`: look for a CLIENT partner in pending. On match, upsert edge + delete it; otherwise insert pending. Also handle within-batch partners before touching the table.
  4. Skip self-edges and unresolved sources; record the unknown source name.
  5. If any edge was inserted or touched: `graphViewRefreshScheduler.markDirty()` once.
  6. Micrometer counters: `cartogra.spans.edges{result=inserted|refreshed}`, `cartogra.spans.skipped{reason=unresolved_source|unresolved_target|self|unsupported_protocol}`, `cartogra.spans.fallback_matched`.
- `domain/exception/UnattributedSpanBatchException(reason)` with an enum `QuarantineReason { TENANT_MISSING, TENANT_MALFORMED, TENANT_UNKNOWN }`.
- Tenant check: `GraphNodeRepository.existsLiveForTenant(tenantId)`.

### Step 4 — Repositories (`repository/` + `infrastructure/jdbc/`)
- **`DependencyRepository.upsertObserved(tenantId, source, target, protocol, seenAt)`** → returns `UpsertResult(inserted)`. It reuses the existing `findByEdgeIdentity` index:
  ```sql
  INSERT INTO dependencies (tenant_id, source_service_id, target_service_id, dependency_type, protocol, last_seen_at)
  VALUES (..., 'observed', ..., :seenAt)
  ON CONFLICT (tenant_id, source_service_id, target_service_id, dependency_type, protocol) WHERE deleted_at IS NULL
  DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at, updated_at = now()
    WHERE dependencies.last_seen_at IS NULL OR dependencies.last_seen_at < EXCLUDED.last_seen_at - interval '1 minute'
  RETURNING (xmax = 0) AS inserted
  ```
- **New `PendingSpanLinkRepository`** (+ `JdbcPendingSpanLinkRepository`): `findClientPartner`, `findServerPartner`, `insert`, `delete(id)`, `deleteExpired(now)`.
- **New `UnresolvedSpanServiceRepository`** (+ Jdbc): `record(tenantId, name, seenAt)` via upsert on lower(name), `seen_count = seen_count + 1`.
- **`GraphNodeRepository`**: add `findLiveByLowerNames`, `existsLiveForTenant`.

### Step 5 — Worker `infrastructure/kafka/OtelSpanWorker.java`
- `@KafkaListener(id = "otelSpanWorker", topics = "cartogra.observability.spans", groupId = "${spring.kafka.consumer.group-id}")`, `ConsumerRecord<String,String>` + `Acknowledgment`. Same pattern as `GraphNodeEventConsumer`: traceparent extraction via the existing `TextMapGetter`, Jackson 3 parse, ack on success.
- Group observations by `cartogra.tenant.id` per ResourceSpans. If any group's tenant is invalid, throw `UnattributedSpanBatchException` (the whole record goes to the DLQ; the collector batches per resource anyway).
- In `config/KafkaConfig.java`, add `UnattributedSpanBatchException` to `addNotRetryableExceptions` next to `JacksonException`. The DLQ recoverer already names `<topic>.dlq` and keeps exception headers.
- Quarantine counter incremented in the worker before throwing.

### Step 6 — Reaper `infrastructure/scheduled/PendingSpanLinkReaperScheduler.java`
- `@Scheduled(fixedDelayString="${topology.span-links.reap-interval:PT1M}")` → `deleteExpired`.
- `@ConfigurationProperties(prefix="topology.span-links")` with `ttl` (PT5M) and `reap-interval` (PT1M), `@Validated`. Follow the `ProcessedEventReaperScheduler` pattern.

### Step 7 — Collector & compose
- **`infra/docker-compose/otel-collector.yml`:**
  - Exporter `kafka/spans` { brokers `[kafka:29092]`, `traces: { topic: cartogra.observability.spans, encoding: otlp_json }` }.
  - Resource processor adds `{ action: insert, key: cartogra.tenant.id, value: ${env:CARTOGRA_TENANT_ID} }`.
  - Traces pipeline exporters `[otlp/tempo, kafka/spans]`.
- **`docker-compose.dev.yml`** otel-collector: `depends_on: kafka`, env `CARTOGRA_TENANT_ID: ${CARTOGRA_TENANT_ID:-}`. An empty value means spans without the attribute get quarantined. Check that an empty `insert` value doesn't stamp an empty string; if it does, the worker treats blank as TENANT_MISSING.

### Step 8 — Frontend
- **`frontend/src/routes/_authenticated/graph.tsx:214-220`:** show the "No observed dependencies yet" alert only when `type === 'OBSERVED' && edges.length === 0`.
- **Vitest `src/test/routes/graph.test.tsx`:** Observed + empty → alert; Observed + edges → no alert, edge rendered.

## Acceptance criteria
- [ ] A JSON fixture published straight to Kafka (no collector) with a CLIENT span `checkout → payments` (peer.service) creates exactly one `observed`/`http` edge with `last_seen_at` set. **(Phase 3 gate check)**
- [ ] Replaying the same record changes no edge count; `last_seen_at` moves only if more than 1 min newer.
- [ ] A CLIENT span whose target is an IP + a SERVER span (separate record) with matching parent → one edge; `pending_span_links` is empty afterwards.
- [ ] Same pair in reverse arrival order → same edge.
- [ ] An unmatched pending row is gone after TTL + reaper tick.
- [ ] Missing / malformed / no-live-nodes tenant → record lands on `cartogra.observability.spans.dlq` with the reason in the exception header, no rows written, counter incremented.
- [ ] Unknown `service.name` / `peer.service` → one `unresolved_span_services` row per name with an incrementing `seen_count`. IP/host targets are never recorded.
- [ ] Messaging/DB spans and self-calls create no edge.
- [ ] Observed edges appear on `GET /graph?type=OBSERVED` after an MV refresh, and are included in blast radius and SPOF.
- [ ] Declared dependency endpoints still return declared only.
- [ ] Graph page: Observed alert only when empty.
- [ ] Roadmap/issues renumbered per D12; ADR-0029 written; ADR-0016 superseded; CONTEXT.md + kafka-topics.md updated.

## Tests
- **Unit:**
  - `OtlpSpanMapperTest`: AnyValue parsing, kind mapping, protocol mapping, host/port stripping, missing attrs.
  - `ObservedDependencyServiceTest`: primary vs fallback branch, self-edge, unknown-name recording rules, markDirty called once / not at all.
- **IT** (`AbstractTopologyIT` + `KafkaTestSupport`):
  - `OtelSpanWorkerIT` publishes fixtures from `src/test/resources/otel/*.json`, the first test resources dir: `client-peer-service.json`, `client-ip-target.json` + `server-child.json`, `no-tenant.json`, `messaging-span.json`. Tenant is substituted at runtime.
  - Assert edges, DLQ (consume `.dlq` with a test consumer), pending table, unresolved table.
  - Wait only for the `otelSpanWorker` container assignment.
- **Repository ITs:**
  - `JdbcDependencyRepositoryIT`: `upsertObserved` insert / no-op / throttle.
  - `JdbcPendingSpanLinkRepositoryIT`, `JdbcUnresolvedSpanServiceRepositoryIT`.
  - `PendingSpanLinkReaperSchedulerIT`.
- **Fix `GraphNodeEventConsumerIT:89-94`** to wait only for its own listener ids (give its listener an `id`).
- **E2E** `frontend/e2e/demo-flow.spec.ts`:
  1. Keep the empty-state check.
  2. POST `client-peer-service.json` (with the test tenant's id and its two services' names) to `http://localhost:4318/v1/traces`.
  3. Poll until the Observed graph shows the edge (MV refresh ≤5s plus collector batch ≤5s, so allow ~30s).
  - Share the fixture by copying it into `frontend/e2e/fixtures/`. Use a helper in `e2e/helpers` if one exists.
  - `global-teardown.ts` already refreshes the MV; extend cleanup if it deletes test edges.

## Verification
- `./gradlew :services:topology:test :services:topology:integrationTest` (or `./gradlew build` from the root).
- `cd frontend && npm test && npm run test:e2e`, with the dev stack up: `docker compose -f infra/docker-compose/docker-compose.dev.yml up -d` (Kafka, Postgres, collector) + services + frontend. Check `vite.config.ts` proxy → :8080 and that the persistent DB is acceptable.
- **Manual:**
  1. With the dev stack up, set `CARTOGRA_TENANT_ID` to your tenant.
  2. `curl -X POST localhost:4318/v1/traces -H 'Content-Type: application/json' -d @services/topology/src/test/resources/otel/client-peer-service.json` (edit the names).
  3. Open `/graph?type=OBSERVED`.
- `kafka-console-consumer` on `cartogra.observability.spans.dlq` after sending `no-tenant.json`.
- `/code-review` + `/security-review` on the diff (DoD).

## Out of scope
- Cartogra's own services emitting CLIENT spans → **7.9**.
- Messaging and DB edges → **3.7**.
- Drift detection → **3.3**; UI/API for `unresolved_span_services` → **3.6**.
- Authenticated OTLP intake / trusted tenant stamping → **5.9**.
- Continuous traffic generator + Acme seed → **3.2**.
- Collector in `docker-compose.yml` → **4.4**.
- Commits / PR only when you say so.
