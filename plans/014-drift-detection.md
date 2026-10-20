# 3.3 — Drift detection
Issue: #121  ·  Branch: `feat/3.3-drift-detection`

## Context
3.1 made observed edges (`dependencies.dependency_type='observed'`, `last_seen_at`). 3.3 compares declared vs observed and raises drift. Found while exploring:
- Scaffolding exists: `V002__create_dependency_drifts.sql` (unique active index per edge+type, RLS), `DependencyDrift`, `DriftType {UNDECLARED, MISSING}`, `DependencyDriftRepository` + `JdbcDependencyDriftRepository` (findActive/countActive/findActiveByEdge/save/resolve), `DriftNotFoundException` + handler, `RiskService` already reads active drifts. No detection, no controller, no `resolution` column.
- Latest migration: V011. Schedulers don't set RLS tenant (owner bypass), so cross-tenant sweeps are fine (see `PendingSpanLinkReaperScheduler`).
- `GraphController` already takes nullable `type` (null = all edges).
- Authz for declared-edge mutation lives in `DependencyService` (ADMIN or team membership either side); gateway injects `X-User-Id`.
- Design brief `docs/design/phase-2-dependency-graph.md`: Edge mode (All/Declared/Observed/Drift), Inspector "Drift" tab, resolve buttons.
- Seed: `seed/acme-fintech/seed-data.json` has 39 declared + 1 dormant undeclared call (`reporting-service > payments-service`).

## Decisions
| # | Decision | Why |
|---|---|---|
| D1 | Scheduled sweep (`DriftSweepScheduler`), not event hooks | one path for both kinds; "missing" needs time anyway |
| D2 | `topology.drift.sweep-interval` default PT5M; `application-dev.yml` + ITs PT15S | hygiene signal; demo stays snappy |
| D3 | UNDECLARED = live observed pair with no live declared pair (any protocol) | pair-level, protocol ignored |
| D4 | MISSING = declared pair, declared older than `missing-after` (P7D default; short in dev), with no observed edge whose `last_seen_at` is within the window; only when tenant has ≥1 observed edge | no false drift for untraced tenants / fresh declarations |
| D5 | Add `resolution` (accepted/removed/dismissed/auto). Dismissed row suppresses re-detection of that edge+type forever. Sweep auto-closes active rows whose condition cleared (`auto`, resolved_by null) | dismiss must stick |
| D6 | Resolve `{action: ACCEPT_OBSERVED|REMOVE_DECLARED|DISMISS}`. ACCEPT only on undeclared: creates DECLARED edge per live observed protocol. REMOVE only on missing: soft-deletes declared edges of the pair. DISMISS either. Wrong combo → 422 `INVALID_DRIFT_ACTION`; already resolved → 409 `DRIFT_ALREADY_RESOLVED` | strict, no meaningless actions |
| D7 | Authz = DependencyService rules (ADMIN or team on either side), before state reveals; `resolved_by` = `X-User-Id` | no bypass of CRUD rules |
| D8 | UI per brief: Edge mode All/Declared/Observed/Drift, Inspector Drift tab, ConfirmDialog resolve | roadmap "overlay + list" |
| D9 | No Kafka events, no ADR (3.5 deferred) | rule 4 |
| D10 | Seed: + `loyalty-service > fx-service` (declared, `enabled:false`) ⇒ MISSING; + `webhook-relay > fraud-service`, `legacy-batch > notification-service` dormant undeclared. `drift on` enables all 3. No new cycles, god service unchanged | one drift per resolve action |
| D11 | E2E: Playwright with `page.route` stubs on drifts; real path via ITs + `pnpm acme verify --with-drift` | shared dev DB / no collector in e2e |

## Implementation steps
1. `services/topology/src/main/resources/db/migration/V012__dependency_drifts_resolution.sql` → `ALTER TABLE dependency_drifts ADD COLUMN resolution VARCHAR(20)` + CHECK in (accepted,removed,dismissed,auto); index `(tenant_id, source_service_id, target_service_id, drift_type) WHERE resolution='dismissed'`. Backfill existing resolved rows to `dismissed`? No: leave null-safe, treat null+resolved_at as `auto`... (decide at impl: backfill `auto`).
2. `domain/DriftResolution.java` (enum), `DriftAction.java` (enum); add `resolution` to `DependencyDrift`; update `JdbcDependencyDriftRepository` mapper/save/resolve (+ `resolve(..., resolution)`), add `insertIfAbsent`, `findDismissedByEdge`/`existsDismissed`, `autoCloseWhereCleared`, `findActive` paging (offset exists), `findActiveByIds`.
3. `repository/DependencyRepository` + Jdbc: `findLivePairs(tenantId, DependencyType)` (distinct source,target + min created_at / max last_seen_at), `hasAnyObserved(tenantId)`, `softDeleteDeclaredPair(tenantId, source, target)`, observed protocols for pair. `GraphNodeRepository`/`DependencyRepository`: `findTenantIdsWithDependencies()`.
4. `config/DriftProperties.java` (`@ConfigurationProperties("topology.drift")`, `@Validated`: `sweepInterval`, `missingAfter`, `pageSize`). Follow `SpanLinkProperties`/`SpanLinkConfig`.
5. `domain/DriftDetectionService.java`: `sweep(tenantId, now)` implementing D3–D5 in one tx; `sweepAll()` iterates tenants. Counters `cartogra.drift.detected{type}`, `cartogra.drift.autoclosed`. No inline comments (backend.md).
6. `infrastructure/scheduled/DriftSweepScheduler.java`: `@Scheduled(fixedDelayString="${topology.drift.sweep-interval:PT5M}")`, per-tenant try/catch + log.
7. `domain/DriftService.java`: `list(tenantId, page, size)` → `DriftPage`; `resolve(tenantId, userId, id, action)` per D6/D7. Reuse authz helpers from `DependencyService` (extract `requireEitherSideAccess` to a package-visible collaborator or call a new `DependencyService.declareFromObserved/removeDeclaredPair`); `graphViewRefreshScheduler.markDirty()` on edge changes. New exceptions `InvalidDriftActionException`, `DriftAlreadyResolvedException` + `GlobalExceptionHandler` mappings + `ErrorCodes`.
8. `api/DriftController.java` (`/drifts`, controller → `DriftService` only, envelope + `X-Trace-Id` like `RiskController`); `api/dto/DriftResponse` (`static from`), `DriftPageResponse`, `ResolveDriftRequest`. Gateway route `/api/v1/topology/**` already covers it.
9. `application-dev.yml`: `topology.drift.sweep-interval: PT15S`, `missing-after: PT2M` (dev). Docs: `docs/api/topology.openapi.yaml` (+drifts paths/schemas), `services/topology/CONTEXT.md` (drop "planned", fix 3.2→3.3 refs, no drift.detected event line), `docs/architecture/data-model.md` (resolution, resolved_by).
10. Frontend: `src/lib/topology-types.ts` (`Drift`, `DriftType`, `DriftAction`, `DriftsPage`); `src/components/DriftTab.tsx` (+ query hook co-located, Skeleton/Alert w/ traceId, rows w/ service names joined from `['services']`, per-type actions via `ConfirmDialog`, mutation invalidates `['drifts']`,`['graph']`,`['risks']`); `InspectorPanel.tsx` add tab (tenant-wide, no node needed); `routes/_authenticated/graph.tsx` replace Declared/Observed toggle with Edge mode (All → `type` omitted); Drift mode filters edges to drifted pairs, color by type; selecting row highlights edge; `DependencyGraph.tsx` edge styling; legend text.
11. Seed `seed/acme-fintech`: `seed-data.json` (D10); `src/schema.ts` (dormant = `declared=false`; declared-disabled still declared), `src/drift.ts` enable/disable all dormant; `src/verify.ts` assert via `GET /drifts` (3 undeclared + 1 missing); `src/api.ts` `drifts()`; tests; `README.md` counts (40 declared, 3 dormant); `docs/roadmap.md` 3.3 text/gate/counts (39→40).
12. `frontend/e2e/drift.spec.ts` (route stubs).

## Acceptance criteria
- [ ] Observed pair with no declared pair ⇒ one active `undeclared` drift after a sweep; repeat sweeps add none.
- [ ] Declared pair older than window, tenant has observed edges, pair not observed in window ⇒ one `missing` drift; tenant with no observed edges ⇒ none.
- [ ] Condition clears (declare/observe) ⇒ drift auto-closed (`auto`).
- [ ] `GET /api/v1/topology/drifts` returns paginated active drifts in the envelope.
- [ ] ACCEPT_OBSERVED creates declared edge(s), drift closed `accepted`, graph MV marked dirty; REMOVE_DECLARED soft-deletes, `removed`; DISMISS closes `dismissed` and is not re-raised by later sweeps.
- [ ] Wrong action for type ⇒ 422; resolving twice ⇒ 409; unknown id ⇒ 404; unauthorized user ⇒ 403; cross-tenant id ⇒ 404.
- [ ] Graph shows Drift edge mode colored by type; Inspector Drift tab lists + resolves; risks refresh.
- [ ] Acme: `drift on` → 3 undeclared + 1 missing visible; accept/remove/dismiss each resolvable on its own drift.
- [ ] No new Kafka topic.

## Tests
- Unit: `DriftDetectionServiceTest` (rules D3–D5, guard, window, dismissed suppression, auto-close); `DriftServiceTest` (matrix, authz, errors).
- IT: `JdbcDependencyDriftRepositoryIT` (extend), `JdbcDependencyRepositoryIT` (pair queries), `DriftControllerIT` (list/resolve/errors/tenant isolation), `DriftDetectionIT` (OTLP fixture → `OtelSpanWorker` → sweep → drift → resolve end to end, reuse `OtelSpanWorkerIT` fixtures/support).
- Frontend vitest: `DriftTab`, Edge mode in `graph` route, `DependencyGraph` drift styling.
- Playwright: `drift.spec.ts` with route stubs (list, 3 resolve actions request bodies, list refresh).
- Seed vitest: schema (3 dormant, 40 declared, cycles still 2, god still 12), verify.

## Verification
- `./gradlew :services:topology:build`
- `cd frontend && pnpm test && pnpm lint && pnpm typecheck`; `pnpm test:e2e -- drift.spec.ts` (check vite proxy/DB per memory first)
- `cd seed/acme-fintech && pnpm test`
- Manual: dev stack + kind + `pnpm acme load`, `pnpm acme drift on`, wait ≤ sweep interval, open `/graph` Drift mode, resolve one of each; `pnpm acme verify --with-drift`.
- Before merge: `/code-review`, `/security-review`, `/impeccable audit` + `critique` on graph screen (roadmap standing checks).

## Out of scope
Topology events (3.5/6.x), dismissal expiry/reopen, per-service instrumentation guard, notifications, authenticated span tenancy (5.9), unresolved span services UI (3.6).
