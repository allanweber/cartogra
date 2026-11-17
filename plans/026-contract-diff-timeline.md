# 5.2 — Contract diff + timeline
Issue: #132  ·  Branch: `feat/phase5-contracts` (one branch per phase; 5.1 already on it)

## Context
5.1 stores contracts and immutable versions but nothing reads them except `GET /contracts/{id}`. `/contracts` still renders `MOCK_CONTRACTS`. 5.2 adds the read side: a paginated contract list, a version timeline, and a structured diff of each version against the previous one. 5.3's breaking-change engine walks that diff. Constraints:
- The frontend holds no logic. Names, diffs and line alignment all come from the backend.
- Contract stores only `serviceId`, so it needs its own service-name projection. Names come from `service.changed` events; a service with no stored name is looked up from Registry once.
- The Acme seed already publishes v1→v2 (the v2 spec adds a `/{x}/{id}/history` path), so 5.2 only extends `verify`.

## Decisions
| # | Decision | Why |
|---|---|---|
| 1 | Contract keeps a **`services` projection** (`tenant_id, service_id, name, deleted_at, updated_at`), upserted from `service.registered/updated/deleted` on `cartogra.registry.service.changed`. It replaces `deleted_services`: a tombstone is a row with `deleted_at` set. | Backend returns names. One topic keyed by service id (5.10) keeps the events in order. |
| 2 | **No backfill.** Reads (list, get, create) look up only services with no stored name: one call to Registry's new internal `POST /internal/services/names` (`{tenantId, serviceIds[≤100]}` → `{id: name}`, one `IN` query), stored in `services`, never asked again. The list fills all of the tenant's unnamed services first so search sees them. Registry down → name stays empty, read still succeeds. | Services registered before contract consumed the topic (the demo Acme services) get names without a backfill endpoint. |
| 3 | `POST /contracts` stays **permissive**: an unknown serviceId is accepted and the list returns `serviceName: null`. | Avoids a race between registering a service and the projection catching up (Acme, e2e, 5.8). |
| 4 | `GET /contracts?limit&offset&q` → `PageResult<ContractSummary>` with `{id, serviceId, serviceName, latestVersion{number,label,createdAt}, versionCount, updatedAt}`. Sorted by `updated_at DESC`; `q` is an ILIKE match on the service name. | Pagination is required by the roadmap. Search is cheap once the projection exists. |
| 5 | `GET /contracts/{id}/versions?limit&offset` → `PageResult<ContractVersion>`, newest first. | The timeline needs it. |
| 6 | `GET /contracts/{id}/versions/{v}/diff` returns `{from, to, changes[], lines[]}`. `from`/`to` = `{versionNumber, label, createdAt}`. `changes[]` = tree diff of the canonical trees, `{op: added\|removed\|changed, pointer (RFC 6901), before, after}`: objects are compared key by key, arrays by index, leaves compared for equality. `lines[]` = aligned side-by-side rows `{left, right, leftNo, rightNo, kind: same\|added\|removed\|changed}` over **normalized YAML** (canonical tree, sorted keys, 2-space indent), computed with **java-diff-utils**. | Structured diff for 5.3; the UI only renders. Normalized YAML means reformats and key reorders show no noise, consistent with `specHash`. |
| 7 | v1's diff has `from: null` and is diffed against an empty document, so every line shows as added. An unknown `v` → 404 `NOT_FOUND`. | Uniform UI: the timeline can open any version. |
| 8 | Reads need no role guard beyond authentication (GUEST included). Writes are unchanged. | Same as other read endpoints. |
| 9 | UI: `/contracts` uses a TanStack Table list (Service, Version, Versions, Last changed) with search and pagination. Status chips and the Consumers column are removed (5.3/5.4). A row opens `/contracts/$contractId?v=N`, which shows the timeline on the left and the diff on the right: a change list, then a side-by-side grid. The `underDevelopment="mock"` banner and `MOCK_CONTRACTS` are deleted, and so is `mock-data.ts` if nothing else uses it. | Linkable, back button works, roomy diff. |

## Implementation steps
**Backend: `services/contract`**
1. `db/migration/V002__services_projection.sql`: create `services` (PK `tenant_id, service_id`, `name TEXT NULL`, `deleted_at`, `updated_at`, RLS); copy the `deleted_services` rows in as tombstones; drop `deleted_services`. Add a `contract_versions (contract_id)` count-friendly index only if the list query needs one.
2. `repository/ServiceProjectionRepository` (replaces `DeletedServiceRepository`): `upsertName(tenant, id, name, at)` (a no-op on a tombstoned row), `tombstone(tenant, id, at)` (returns false if already tombstoned), `isDeleted`. Jdbc impl in `infrastructure/jdbc`.
3. Kafka: rename `ServiceDeletedConsumer` → `ServiceEventConsumer`. It handles `service.registered`/`service.updated` (upsert name) and `service.deleted` (the existing cascade); other types are acked. Payload `name` is read from the envelope.
4. Name lookup: registry `ServiceNamesInternalController` + `ServiceRepository.findNames` (one `IN` query). Contract `infrastructure/registry/RegistryNameClient` (`ServiceCallRetry`, `contract.registry.base-url`), `ServiceProjectionService.nameOf` / `fillMissingNames`, `ServiceProjectionRepository.findUnnamed` / batch `upsertNames`. Wire `REGISTRY_URI` into Helm contract values, compose and `application.yml`; add `contract` to registry's `networkPolicy.allowFrom`. The gateway already denies `/api/v1/*/internal/**`.
5. Repository reads: `ContractRepository.listLive(tenant, q, limit, offset)` + `countLive` joining `services` for the name and a version count; `ContractVersionRepository.list(tenant, contractId, limit, offset)`, `count`, `findByNumber(tenant, contractId, n)`.
6. `domain/SpecDiffer` (`@Component`): `List<SpecChange> diff(JsonNode from, JsonNode to)` + `List<DiffLine> lines(String fromYaml, String toYaml)`; YAML rendering of the canonical JSON via the existing `YAMLMapper` (sorted keys, since canonical is already sorted). Add `io.github.java-diff-utils:java-diff-utils` (pin the latest stable) to `build.gradle.kts`.
7. `ContractService`: `list`, `listVersions`, `diff(tenant, contractId, v)`. Records `ContractSummary`, `SpecChange`, `DiffLine`, `VersionDiff` live in `domain`.
8. `ContractController`: `GET ""`, `GET /{id}/versions`, `GET /{id}/versions/{v}/diff`. DTOs `ContractSummaryResponse`, `VersionDiffResponse` with `static from`.
9. `docs/api/contract.openapi.yaml`: add the three operations + schemas and bump `info.version` to 0.3.0. `ContractEnvelopeContractTest` validates against it.
10. `services/contract/CONTEXT.md`: mark the list/diff endpoints Live (5.2), add the versions list and the name lookup, replace the `deleted_services` row with `services`, update the Kafka row.

**Frontend**
11. `frontend/src/lib/contract-types.ts`: `ContractSummary`, `ContractVersion`, `VersionDiff`, `DiffLine`, `SpecChange`.
12. `frontend/src/hooks/useContracts.ts`: `useContracts({q, limit, offset})`, `useContractVersions(id)`, `useVersionDiff(id, v)`. Query keys follow the existing tenant-scoped pattern.
13. `routes/_authenticated/contracts.tsx` becomes the layout (`<Outlet/>`); `contracts.index.tsx` is the list (TanStack Table, search input, pager, skeleton, Alert with traceId); `contracts.$contractId.tsx` has `validateSearch` `{v?: number}` and defaults to the latest version.
14. Components: `ContractTimeline.tsx`, `ContractChangeList.tsx`, `SideBySideDiff.tsx` (renders `lines[]` only, monospace, row tint by `kind`). Named exports, shadcn primitives.
15. Delete `MOCK_CONTRACTS` / `ContractStatus` from `lib/mock-data.ts` (delete the file if it becomes empty) and update the sidebar or command palette if they reference the mock.

**Acme seed**
16. `seed/acme-fintech/src/api.ts`: `listContracts(q?)`, `contractDiff(id, v)`. In `verify.ts` → `verifyContracts`: the list contains each seeded contract with `serviceName` = the seed name, and the diff of v2 has an `added` change at `/paths/<history path>` plus `added` lines.

## Acceptance criteria
- [ ] `GET /v1/contracts` (via gateway) returns a paginated `PageResult` of live contracts for the caller's tenant only, with `serviceName`, latest version and version count; `q` filters by service name; soft-deleted contracts are absent.
- [ ] `GET /v1/contracts/{id}/versions` lists versions newest first, paginated; another tenant's id → 404.
- [ ] `GET /v1/contracts/{id}/versions/{v}/diff` returns `changes[]` with RFC 6901 pointers and `lines[]` aligned rows. v1 → `from: null`, all lines added. Unknown v → 404. Malformed v → 400.
- [ ] Reformatting a spec (YAML↔JSON, key order) gives no new version (5.1) and, between differing versions, produces no extra changes or lines beyond the real differences.
- [ ] `service.registered`/`updated` set or rename the projected name; `service.deleted` still cascades (the 5.1 IT stays green); replaying any event changes nothing; an update after a delete does not resurrect the service.
- [ ] A contract whose service has no stored name shows the Registry name after one lookup, which is stored; named services never call Registry; Registry down → empty name, 200.
- [ ] `/contracts` shows real data, has no mock banner, and `MOCK_CONTRACTS` is deleted; a row opens the detail, the timeline selects a version, and the diff renders the side-by-side and the change list; loading shows skeletons; errors show the traceId.
- [ ] `pnpm acme verify` passes the new contract-list and diff checks against the local app.

## Tests
- Unit: `SpecDifferTest` covers added/removed/changed keys, nested objects, array length changes, pointer escaping (`~0`, `~1`), identical trees → empty, empty→tree, and the line alignment for a changed block. `ContractServiceTest` covers the new reads.
- IT (Testcontainers): `ContractControllerIT` covers list pagination, `q`, tenant isolation, versions list, diff for v1/v2, 404/400. `ServiceEventConsumerIT` (renamed) covers registered → name, updated → rename, deleted → cascade, replay, update after delete. `ContractReadIT` covers the lookup with a mocked `RegistryNameClient`; `RegistryNameClientTest` (WireMock); `ServiceProjectionServiceTest`; registry `ServiceNamesInternalControllerIT`.
- Contract test: `ContractEnvelopeContractTest` covers the new responses against the OpenAPI.
- Frontend (Vitest + RTL): list renders rows, search and pager; detail picks the latest by default and `?v=` selects a version; `SideBySideDiff` renders the kinds; error Alert shows the traceId.
- E2E: `frontend/e2e/contracts.spec.ts` registers a service in the UI, posts v1/v2 via `page.request`, waits for the name in `/contracts`, opens the row, and asserts the timeline, the added path in the change list, and the added lines.
- Acme: `verify` checks listed in step 16; `pnpm test` in the seed for the api/verify unit tests.

## Verification
- `./gradlew build --continue`
- `cd frontend && pnpm test && pnpm lint && pnpm exec playwright test e2e/contracts.spec.ts` (check the target DB first; the e2e proxy is hardcoded to :8080)
- `cd seed/acme-fintech && pnpm test && pnpm typecheck`
- Local app: restart registry + contract + gateway, `pnpm acme load && pnpm acme verify`, then open `/contracts` → `payments`-style row → v2 diff in the browser.

## Out of scope
- Compatibility status badge and filter (5.3); consumers column and detail (5.4); `contract_checks` (5.5).
- Spec upload or edit UI; a contract tab on the service detail page; spec download.
- Requiring a known service on create (decision 3).
- Any backfill endpoint. Topology's existing backfill is a separate decision.
