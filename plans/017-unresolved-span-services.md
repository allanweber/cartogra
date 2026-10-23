# 3.6 — Unresolved span services
Issue: #190  ·  Branch: `feat/3.6-unresolved-span-services`

## Context
3.1 (`ObservedDependencyService.recordUnknown`) fills `unresolved_span_services` with CLIENT `peer.service` names that match no catalog node. Nothing reads it today. 3.6 exposes the list (API + UI next to drift) so users can register the missing service or dismiss noise (external SaaS).

Constraints found:
- Partial unique index `(tenant_id, lower(name)) WHERE deleted_at IS NULL` + `record()` inserting when no live row → a plain soft-delete dismiss would be re-raised on the next span batch.
- Nothing clears a row when the name becomes a catalog service.
- `seen_count` counts span *batches* containing the name (per-batch dedup in `recordUnknown`), not spans. Still a valid relative ranking; unchanged.
- Acme fake service emits a CLIENT span with `peer.service=<to>` even when the HTTP call fails (DNS), so an external target works as-is.

## Decisions
| Decision | Choice | Why |
|---|---|---|
| Dismiss semantics | Sticky: soft delete, `record()` never re-inserts a dismissed name | Mirrors drift DISMISS; otherwise dismiss is useless for chatty externals |
| Clearing resolved names | Soft delete with `resolution='registered'` on every graph-node upsert (registered/updated/rename + backfill) | Table stays truthful; user choice |
| Distinguish the two | `resolution VARCHAR(20)` (`dismissed`/`registered`) + `resolved_by UUID`, mirroring V012 | Sticky check only on `dismissed`; a later-deleted catalog service can resurface as unresolved |
| Dismiss authz | Any tenant user; `resolved_by` audit | User choice; `resolved_by` = compensating control for security review |
| API | `GET /unresolved-span-services?limit=50&offset=0` → `{items,total,limit,offset}`, order `seen_count DESC, last_seen_at DESC, id`, limit clamp 1–200. `POST /unresolved-span-services/{id}/dismiss` → item. 404 not found, 409 already resolved | Mirrors `DriftController`/`DriftService` |
| UI placement | Section under `DriftTab` in graph page's no-selection "Drift" card | Literal "next to drift"; rows aren't nodes |
| Create shortcut | `RegisterServiceDrawer` inline on `/graph`, new optional `defaultName` + `onRegistered` props; optimistic row hide + invalidate | Keeps graph context; server clears row on `service.registered` |
| UI pagination | Fetch 20, "Show more" raises limit (+20, max 200) | Simple; cap is 1000 rows/tenant |
| Acme | `external: true` flag on `SeedCall`; `payments-service → stripe-gateway` | Reuses ACME_CALLS plumbing |
| Acme verify | Poll list until `stripe-gateway` present; never dismiss | Sticky dismiss would break idempotent re-runs |
| E2E | Stub unresolved API, real registry | Spans not producible in e2e; matches `drift.spec.ts` |

## Implementation steps

### Backend — `services/topology`
1. `src/main/resources/db/migration/V013__unresolved_span_services_resolution.sql`
   - `ADD COLUMN resolution VARCHAR(20)`, `ADD COLUMN resolved_by UUID`
   - `UPDATE … SET resolution='dismissed' WHERE deleted_at IS NOT NULL`
   - `CHECK (resolution IN ('dismissed','registered'))`, `CHECK ((deleted_at IS NULL) = (resolution IS NULL))`
   - `CREATE INDEX ON unresolved_span_services (tenant_id, lower(name)) WHERE resolution = 'dismissed'`
   - `CREATE INDEX ON unresolved_span_services (tenant_id, seen_count DESC) WHERE deleted_at IS NULL` (list order)
2. `domain/UnresolvedSpanService.java` — record `(id, tenantId, name, firstSeenAt, lastSeenAt, seenCount, @Nullable deletedAt, @Nullable UnresolvedResolution resolution)` + `isResolved()`.
   `domain/UnresolvedResolution.java` — enum `DISMISSED, REGISTERED` with `dbValue`/`fromDbValue` (pattern: `DriftResolution`).
   List returns shared `io.cartogra.common.api.PageResult<UnresolvedSpanService>` (no per-feature page record).
3. `repository/UnresolvedSpanServiceRepository.java` — add `findActive(tenantId, limit, offset)`, `countActive(tenantId)`, `findById(tenantId, id)` → `Optional`, `dismiss(tenantId, id, @Nullable userId, Instant)` → boolean (UPDATE … WHERE deleted_at IS NULL), `markRegistered(tenantId, name, Instant)`.
4. `infrastructure/jdbc/JdbcUnresolvedSpanServiceRepository.java`
   - `record()`: insert gains `AND NOT EXISTS (… lower(name)=lower(:name) AND resolution='dismissed')`; update Javadoc on the interface.
   - Implement new methods; all queries filter `tenant_id`.
5. `domain/UnresolvedSpanServiceService.java` — `list(tenantId, limit, offset)` (clamp like `DriftService.list`), `dismiss(tenantId, userId, id)` → throws `UnresolvedSpanServiceNotFoundException` / `UnresolvedSpanServiceAlreadyResolvedException` (new, in `domain/exception`); re-read after update.
6. `domain/GraphNodeService.java` — inject `UnresolvedSpanServiceRepository`; after `graphNodeRepository.upsert(...)` in `applyLifecycleEvent` (non-delete branch) and in `backfill()`, call `markRegistered(tenantId, name, now)`.
7. `api/UnresolvedSpanServiceController.java` (`@RequestMapping("/unresolved-span-services")`, `X-Tenant-Id`, optional `X-User-Id`, `TracedResponse.ok`) — pattern: `api/DriftController.java`.
   `api/dto/UnresolvedSpanServiceResponse.java` with `static from(...)`; controller returns `page.map(UnresolvedSpanServiceResponse::from)`.
8. `api/GlobalExceptionHandler.java` — map the two exceptions to 404 / 409 with new codes (`UNRESOLVED_SPAN_SERVICE_NOT_FOUND`, `UNRESOLVED_SPAN_SERVICE_ALREADY_RESOLVED`) next to the drift handlers; add codes wherever topology error codes live.
9. Gateway: none — `/api/v1/topology/**` route already covers it.

### Frontend
10. `frontend/src/lib/topology-types.ts` — `UnresolvedSpanService`, `UnresolvedSpanServicesPage`.
11. `frontend/src/hooks/useUnresolvedSpanServices.ts` — `useQuery(['unresolved-span-services', limit])` via `apiFetch('/v1/topology/unresolved-span-services?limit=…&offset=0')`; `useDismissUnresolvedSpanService` `useMutation` invalidating `['unresolved-span-services']` (pattern: `hooks/useDrifts.ts`).
12. `frontend/src/components/RegisterServiceDrawer.tsx` — optional `defaultName?: string`, `onRegistered?: (service: RegistryService) => void`; `setFieldValue('name', defaultName)` on open; call `onRegistered` in `onSuccess`. Catalog usage unchanged.
13. `frontend/src/components/UnresolvedSpanServices.tsx` — heading "Unresolved services (total)"; rows: name, `seenCount×`, relative last seen, `Create service` + `Dismiss` buttons; Skeleton loading, `Alert` with traceId on error, empty state text; "Show more" when `items.length < total`. Owns its drawer state and renders `RegisterServiceDrawer` with `defaultName`; `onRegistered` removes row from query cache + invalidates. Toast on dismiss error with trace.
14. `frontend/src/routes/_authenticated/graph.tsx` — render `<UnresolvedSpanServices />` under `DriftTab` in the no-selection card (with a separator).

### Acme seed — `seed/acme-fintech`
15. `src/schema.ts` — `SeedCall.external?: boolean`; `validateSeed` allows non-catalog `to` only when `external`, requires `external` calls to be `declared:false, enabled:true`; `declaredCalls`/`dormantCalls`/`silentDeclaredCalls`/cycle helpers ignore external calls; new `externalCalls(seed)`.
16. `seed-data.json` — add `{from:"payments-service", to:"stripe-gateway", declared:false, enabled:true, external:true}`.
17. `src/manifests.ts` — external enabled calls included in `ACME_CALLS` (check `enabledCallsFrom`).
18. `src/load.ts` — ensure external calls are never declared as edges.
19. `src/api.ts` — `unresolvedSpanServices(): Promise<{name:string}[]>` via `listAll('/api/v1/topology/unresolved-span-services')`.
20. `src/verify.ts` — unless `skipObserved`: poll (same timeout as observed) until every `externalCalls(seed).to` is listed → check `"N external peers unresolved"`.
21. `README.md` — mention the external call; `docs/roadmap.md` 3.6 → `[x]` with `*Acme test:* ✅` line + plan ref; topology `CONTEXT.md` glossary entry for "unresolved span service" if a glossary exists.

## Acceptance criteria
- [ ] `GET /api/v1/topology/unresolved-span-services` returns only the caller tenant's live rows, ordered by `seen_count DESC`, with `total`, `limit`, `offset`; limit clamped 1–200, negative offset → 0.
- [ ] `POST …/{id}/dismiss` sets `deleted_at`, `resolution='dismissed'`, `resolved_by`; returns the item; 404 for unknown/other-tenant id; 409 when already resolved.
- [ ] A dismissed name seen again in spans creates no new row and doesn't bump anything.
- [ ] `service.registered`/`service.updated` (incl. rename) or backfill for name X soft-deletes live row X with `resolution='registered'`; X then disappears from the list.
- [ ] After a catalog service X is deleted, a new sighting of X creates a fresh unresolved row.
- [ ] Graph page Drift card lists unresolved services under drift, with count, seen count, last seen.
- [ ] "Create service" opens the register drawer with name pre-filled; on success the row disappears.
- [ ] "Dismiss" removes the row; errors show a toast with traceId.
- [ ] `pnpm acme verify` asserts `stripe-gateway` appears in the unresolved list; existing checks (drift counts, cycles, declared edges) unchanged.
- [ ] All responses use the `{data, traceId}` envelope.

## Tests
- **Repository IT** `JdbcUnresolvedSpanServiceRepositoryIT` (extend): sticky dismiss blocks re-insert; registered row does not block re-insert; `findActive` order + tenant isolation; `countActive`; `dismiss` returns false when already resolved; `markRegistered` case-insensitive.
- **Unit** `UnresolvedSpanServiceServiceTest`: limit clamping, not found, already resolved.
- **Unit** `GraphNodeServiceTest` (extend): upsert + backfill call `markRegistered`; delete does not.
- **Controller IT** `UnresolvedSpanServiceControllerIT` (pattern `DriftControllerIT`): list envelope/order/pagination, dismiss 200/404/409, cross-tenant 404.
- **Worker IT** `OtelSpanWorkerIT` (extend): fixture span to dismissed name → no new row.
- **Vitest**: `UnresolvedSpanServices.test.tsx` (loading, error w/ trace, empty, rows, dismiss calls API, create opens drawer pre-filled, show more); `RegisterServiceDrawer.test.tsx` (defaultName prefill, onRegistered called); `graph.test.tsx` renders section.
- **Acme vitest**: schema validation of `external` (rejects external+declared, allows unknown `to` only when external); helpers exclude external; verify check with `test/fakeApi.ts`.
- **E2E** `frontend/e2e/unresolved-span-services.spec.ts`: stub GET/dismiss via `page.route`; (1) dismiss → POST hits right id, row gone; (2) Create service → drawer pre-filled with stub name → real registry create → row gone → service visible in `/catalog`.

## Verification
- `./gradlew :services:topology:build` (unit + IT), then `./gradlew build`
- `cd frontend && pnpm test && pnpm lint && pnpm exec tsc --noEmit`
- `cd frontend && pnpm exec playwright test e2e/unresolved-span-services.spec.ts` (check vite proxy target / DB first — dev proxy hardcoded to :8080)
- `cd seed/acme-fintech && pnpm test && pnpm acme verify` against the kind cluster
- Manual: `/graph` → Drift card shows `stripe-gateway`; Create service → pre-filled → registered → row gone; dismiss another name → stays gone after next span batch.
- DoD: `/code-review` and `/security-review` on the diff.

## Out of scope
- Un-dismiss / listing dismissed names / admin-only dismiss.
- Snooze / time-based re-raise.
- Kafka events for unresolved names (no consumer).
- Recording `peerHost`-only names (only `peer.service` is recorded today).
- Changing `seen_count` semantics (counts batches, not spans).
- 3.7 messaging/DB edges.
