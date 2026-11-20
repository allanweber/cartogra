# Spec discovery (5.8)
Issue: #138  ·  Branch: `feat/5.8-spec-discovery`  ·  Plan file: `plans/032-spec-discovery.md` (031 is used by 5.3)

## Context
At the moment, contracts are made only by a manual `POST /v1/contracts` and `/versions` call. Story 5.8 lets ingestion find OpenAPI files in SCM repos on each sync, and lets contract store them. Then a merged spec change becomes the next baseline (this is necessary for 5.5).

Constraints found during the exploration:
- **Ingestion cannot know `service_id`.** The registry makes ids with `UUID.randomUUID()` (`services/registry/.../domain/ServiceService.java:483`). The key is `(tenant, externalId = repo.fullPath())` (ADR-0021). The roadmap and ADR-0018 text ("ingestion carries service_id") cannot work. Contract resolves the service instead.
- **Race on the first sync.** `processRepo` sends `service.changed` and the spec only milliseconds apart. The registry has not made the service yet, so contract must keep the spec as PENDING.
- **Existing services have no external_id** in the contract `services` projection. Their events were consumed before this change. A registry lookup on a miss fixes this with no one-time job.
- `ScmProvider.getFileContents` (`services/ingestion/.../infrastructure/scm/ScmProvider.java:19`) already fetches any path for GitHub and Azure. `TechStackDetector.safeGetFile` (`domain/TechStackDetector.java:71`) is the pattern to copy.
- Kafka uses the default 1 MB `max.request.size`. GitHub returns empty `content` for files over 1 MB.
- k3d has no SCM, and the public registry API cannot set `externalId`.
- The contract publish methods are `@PreAuthorize`. A Kafka thread has no SecurityContext, so the new path uses unguarded service methods, the same as `ContractService.deleteForService`.

## Decisions
1. **Event** `cartogra.ingestion.spec.discovered`. The envelope `eventType` is `spec.discovered`. The payload is `{tenant_id, connection_id, repository_full_name, file_path, content_sha256, content_b64}`, with **no service_id**. `entityId` = `UuidV5.fromNames(tenant, repo, path)`, and the message key = entityId. It is sent from `SyncExecutionService.processRepo`, after `publishDiscovered`, with a traceparent header.
2. **Contract resolves repo → service:**
   1. Look in the `services.external_id` projection.
   2. On a miss, call the registry `GET /internal/services/by-external-id?externalId=` with the internal token. A hit writes external_id to the projection.
   3. If there is still no match, the row becomes `PENDING`.
   4. `service.registered`/`service.updated` from `cartogra.registry.service.changed` stores `externalId` and applies the PENDING specs for that `(tenant, external_id)`.
   5. If the same PENDING event comes back on a later sync, contract tries to resolve it again.
3. **Contract V005:** a `discovered_specs` table with an `is_latest` flag (partial unique index on `(tenant_id, repository_full_name, file_path) WHERE is_latest`) and status `PENDING|APPLIED|UNCHANGED|REJECTED|SKIPPED|SUPERSEDED`, plus `contract_version_id` and `errors JSONB`. Also `services.external_id` with a partial unique index, and the columns `contract_versions.source` (`manual|discovery`, default `manual`) and `discovered_spec_id`. RLS and grants are the same as in V003.
4. **Idempotency (last spec seen):** contract records the last spec seen for each `(tenant, repository_full_name, file_path)`. A spec is skipped only when it equals the last spec seen for that repo/path and that row is `APPLIED`, `UNCHANGED` or `REJECTED` for a live service. If the row is `SKIPPED`, or `APPLIED`/`UNCHANGED` for a service that is now tombstoned, contract inserts a PENDING copy as the new latest row and resolves the repo again: a live service gets the spec, else the copy stays PENDING for the next `service.registered`. `service.deleted` does not change `discovered_specs`. A different spec gets a new row, also a revert: A → B → A publishes A again as v3. An unchanged repeated spec is still skipped, so a manual POST between syncs is not overwritten on each sync. A new row moves an older PENDING row of the same repo/path to `SUPERSEDED`, so a stale PENDING spec is never applied over a newer one.
5. **Apply rules:**
   - The projection lookup returns only a live service; a tombstoned service is a miss, so the spec stays PENDING until a new service of the repo is registered. A service that Registry resolves but the projection has tombstoned gives `SKIPPED`. The same spec sent again after a `SKIPPED` row, or after its service was deleted, goes to the live service of the repo (decision 4).
   - An invalid spec gives `REJECTED` and keeps the parser errors. The event is acked, with no DLQ.
   - If no contract exists, create the contract and v1. Audit `created`.
   - If the canonical hash equals the latest version, the row becomes `UNCHANGED`.
   - In other cases, create a new version and audit `version_published`. The actor is `Actor.SYSTEM`, and the source is `discovery`.
   - The unique index `api_contracts_live_service_uq` can cause a race with a manual create. In that case, read the contract again and publish a version.
6. **Spec paths for each tenant** (ingestion V008 `tenant_spec_paths(tenant_id PK, paths TEXT[], timestamps, deleted_at)` + RLS):
   - The defaults are `openapi.yaml, openapi.yml, openapi.json, api/openapi.yaml, docs/openapi.yaml`.
   - Admin custom paths are **added** to the defaults. The probe order is the custom paths first, then the defaults. The first hit wins.
   - `GET /v1/ingestion/spec-paths` → `{defaults[], custom[]}`. `PUT` (`Roles.ADMIN_ONLY`, audited `spec_paths.updated`) `{custom[]}` replaces only the custom list.
   - Validation (400 `VALIDATION_FAILED`): a relative path, no `..`, no leading `/`, no glob characters (`*?[]{}`), ≤256 characters, ≤10 entries, no duplicates, and not equal to a default.
7. **Size:** ingestion skips a spec that is over 512 KiB or empty. It writes a WARN log and increments the counter `cartogra.ingestion.spec.skipped` (tag `reason`). A fetch error is the same as no file (the `safeGetFile` pattern).
8. **UI:**
   - A "Spec discovery" card on Settings → SCM Configuration. It shows the defaults as read-only chips and the custom list as editable (TanStack Form). The card is read-only for non-admins.
   - The contract timeline shows a "from `repo`/`path`" badge for versions with `source=discovery`. The version API returns `source`, `repositoryFullName` and `filePath` (nullable).
9. **Out of scope:** a spec removed from a repo (the contract stays), glob patterns, AsyncAPI and Swagger 2, more than one spec for each repo, and a larger Kafka message size.

## Implementation steps
### Shared / registry
1. `services/registry/.../api/ServiceByExternalIdInternalController.java` (new, `/internal/services/by-external-id`, the same security as `ServiceNamesInternalController`). It uses `ServiceService.findByExternalId` (`:390`) and returns `{serviceId}` or 404. Add the path to the internal matcher in the registry `SecurityConfig`.
2. Confirm that the registry `ServiceDiscoveredPayload`/`ServiceLifecycleEventProducer.java:44` already sends `externalId` on registered and updated. It does today.

### Ingestion
3. `db/migration/V008__tenant_spec_paths.sql`: the table + RLS (copy V006).
4. `repository/SpecPathRepository.java` + `infrastructure/jdbc/JdbcSpecPathRepository.java`.
5. `domain/SpecPathService.java`: `get(tenant)`, `update(tenant, Actor, request)` (ADMIN_ONLY, validation, audit through `IngestionAuditRecorder`), and `probeOrder(tenant)`.
6. `api/SpecPathController.java` + `api/dto/SpecPathsRequest.java`, `SpecPathsResponse.java` (`static from`). Use the envelope + `X-Trace-Id`, the same as `ScmConnectionController`.
7. `domain/SpecDiscoverer.java`: walk `probeOrder`, call `provider.getFileContents`, and give back the first non-blank hit ≤512 KiB. Increment the skip counter. Put the defaults list here as a constant.
8. `domain/SpecDiscoveredPayload.java` + `infrastructure/kafka/SpecDiscoveredProducer.java`. Copy `OwnershipResolvedProducer` (traceparent inject, log on a send failure).
9. `domain/SyncExecutionService.processRepo` (`:169-196`): after `publishDiscovered`, call the discoverer → producer. Load `probeOrder` one time for each sync, not for each repo (no queries in loops).

### Contract
10. `db/migration/V005__spec_discovery.sql` (5.3 took V004), as in decision 3.
11. `domain/DiscoveredSpec.java` record + `repository/DiscoveredSpecRepository.java` + `infrastructure/jdbc/JdbcDiscoveredSpecRepository.java` (`recordLatest` gives the last spec seen when the hash is equal, else inserts a new latest row and supersedes an older PENDING row, `findPending(tenant, externalId)`, `markStatus`).
12. Projection: `JdbcServiceProjectionRepository` with `upsertExternalId` and `findByExternalId`. `ServiceEventConsumer` stores `externalId` on registered and updated, then calls `SpecDiscoveryService.applyPending`.
13. `infrastructure/registry/RegistryNameClient` → add `findServiceIdByExternalId` (the same retry and internal token).
14. `domain/SpecDiscoveryService.java` (no `@PreAuthorize`): `ingest(envelope)` → idempotency → resolve → `apply`. `apply` reuses `SpecParser` and the internals of `ContractService` (extract a package-private `createInternal`/`publishInternal` from `create`/`publishVersion`, so that the guarded public methods stay the same). Each step runs inside `TenantContext.runAs` and a `TransactionTemplate`.
15. `infrastructure/kafka/SpecDiscoveredConsumer.java`: copy `ServiceEventConsumer` (trace extract, manual ack, the DLQ from `KafkaConfig` for malformed records only).
16. Version DTO (`api/dto/ContractVersionResponse` or equivalent): add `source`, `repositoryFullName` and `filePath`. The JDBC version mapper joins `discovered_specs`.

### Frontend
17. `frontend/src/lib/contract-types.ts`: add the new version fields. Add the timeline badge in the existing timeline component.
18. `frontend/src/components/SpecPathsCard.tsx` (named export, `useQuery`/`useMutation`, queryKey `['ingestion','spec-paths']`, Skeleton/Alert with the traceId), used in `routes/_authenticated/settings.connections.tsx`.

### Seed / k3d / docs
19. `seed/acme-fintech/src/verify.ts`: `verifySpecDiscovery`. If the tenant has an SCM connection, each seed service must have a contract. A service with no manual seed versions must also have a `source=discovery` version; a service with manual seed versions gets `UNCHANGED` from discovery, so only its contract is checked. If there is no SCM connection, it prints `SKIP`.
20. `infra/helm/local-test.sh`: a step `"Check 5.8 spec discovery"` (see Tests).
21. Docs:
    - ADR-0018: amend it (no service_id, contract resolves through the projection or the registry lookup, PENDING, the discovered_specs key, the 512 KiB cap).
    - `docs/architecture/kafka-topics.md`: move the topic from Proposed to live, and add it to the DLQ list.
    - `services/{ingestion,contract}/CONTEXT.md`, `CONTEXT-MAP.md` (Ingestion → Contract live), `docs/architecture/data-model.md` (contract tables, `tenant_spec_paths`).
    - `docs/roadmap.md` 5.8 text (service_id is resolved by contract) + the box at ship time.

## Acceptance criteria
- [ ] Each sync publishes one `spec.discovered` event for each repo that has a spec at a probe path. A repo with no spec publishes no event.
- [ ] Custom paths are tried before the defaults. The first hit is the only one sent.
- [ ] A spec over 512 KiB, or empty, makes no event and increments `cartogra.ingestion.spec.skipped`.
- [ ] Contract makes the contract and v1 (`source=discovery`) for a known service. The audit `created` has a system actor.
- [ ] The same spec sent again for the same repo/path changes nothing (no version, no audit). A → B → A gives v3. A, a manual C, then A again gives no new version.
- [ ] A spec that comes before its service becomes PENDING. It is applied when `service.registered` comes.
- [ ] A service with no projection external_id is resolved through the registry lookup.
- [ ] A changed spec gives version n+1. A changed file with the same canonical hash gives UNCHANGED and no version.
- [ ] An invalid spec gives a REJECTED row with errors and no DLQ. A tombstoned service gives SKIPPED and no contract. A deleted-then-rediscovered repo keeps the spec PENDING and applies it to the new service. A stale PENDING spec is never applied over a newer one.
- [ ] Specs with the same `openapi.yaml` path in two repos give two separate contracts.
- [ ] `GET/PUT /v1/ingestion/spec-paths` works for an admin. A non-admin PUT gives 403 (added to the guest-403 IT). Invalid paths give 400.
- [ ] The timeline shows the repo/path badge for discovered versions.
- [ ] All tenant tables have RLS. All queries filter by tenant.

## Tests
- **Unit:**
  - `SpecPathServiceTest` (validation, merge order).
  - `SpecDiscovererTest` (first hit, size cap, blank, fetch error).
  - `SpecDiscoveryServiceTest` (each status branch, the duplicate-key race).
  - The frontend `SpecPathsCard` and timeline badge tests (vitest).
- **Ingestion IT:**
  - `SpecDiscoveryFlowIT`, based on `ServiceDiscoveryFlowIT` with WireMock stubs for the spec paths. It covers the custom-path priority and the payload fields/sha256/entityId.
  - `SpecPathControllerIT` (admin 200, guest 403, validation 400, audit). RLS IT for the new table.
- **Registry IT:** the internal by-external-id endpoint (200/404, no internal token gives 401).
- **Contract IT:**
  - `SpecDiscoveredConsumerIT`: known service, duplicate, PENDING → registered, registry lookup (mocked client), changed spec v2, unchanged, invalid, tombstoned, deleted then rediscovered, revert A → B → A gives v3, A + manual C + A gives no version, stale PENDING superseded, two repos with the same path.
  - `RowLevelSecurityIT` extended to `discovered_specs`.
- **E2E (Playwright):** `frontend/e2e/settings-spec-paths.spec.ts`. An admin adds `src/main/resources/openapi.yml`, reloads and sees it, then removes it. The spec waits for the onboarding wizard's queries and closes the wizard only when it opens. There is no non-admin e2e case: only an admin can open Settings. The read-only branch of the card (for a user who is not yet known, or not an admin) is covered by the vitest test only.
- **k3d** `step "Check 5.8 spec discovery"`:
  1. Use `kubectl exec` with kafka-console-producer to send `spec.discovered` for repo `local-test/spec-<rand>`.
  2. Then send ingestion `service.changed` discovered for the same repo.
  3. `pass` if a contract with v1 exists for the new service (the PENDING path).
  4. Send the same spec again: `pass` if the version count is still 1.
  5. Send a changed spec: `pass` if v2 exists.
  6. `pass` if `GET spec-paths` returns 5 defaults.
  7. `pass` if an admin PUT of a custom path gives 200, and then reset it.
  8. `pass` if a guest PUT gives 403.

## Verification
- `./gradlew build` from the repo root.
- `cd frontend && pnpm test && pnpm lint && pnpm typecheck`.
- `sh .claude/skills/task-eval/k3d-run.sh` (local-test.sh `--e2e`). Read the `REPORT` paths and confirm that the 5.8 step and `settings-spec-paths.spec.ts` pass.
- After the deploy (user merge): `pnpm acme verify` against cartogra.dev. `verifySpecDiscovery` passes, or prints SKIP when there is no SCM connection.

## Out of scope
- A spec removed from a repo (the contract stays; no deprecation).
- Glob patterns or a repo-tree search.
- AsyncAPI and Swagger 2.0.
- More than one spec for each repo.
- A larger Kafka message size, or a reference + S3 pattern.
- An SCM pod in k3d.
