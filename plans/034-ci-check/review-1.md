# Review: plan 034, CI check + tenant API keys (5.5)

Reviewer: an independent plan review on 2026-11-24. The review covers the uncommitted working tree of `/home/allan/developer/cartogra` (branch `feat/5.5-ci-check`), compared with `origin/main`. The review includes the approved items of `## Changes during execution`.

## 1. Verdict

**CHANGES REQUIRED**

- Blockers: 0
- Major: 1 (F-1: the unauthenticated requests on `POST /api/v1/contracts/check` skip the IP rate limit)
- Minor: 4

All the tests that the plan names exist, and they pass in my run. The code agrees with the business rules and with the data model. I did not run the e2e spec (T17) or the k3d step (T19). Thus those items are UNVERIFIED (see section 2).

## 2. Plan coverage

### Tasks

| ID | Result | Evidence |
|---|---|---|
| T1 | PASS | `shared/common/src/main/java/io/cartogra/common/audit/AuditKind.java:32` (`API_KEY("api_key", Set.of())`); `shared/common/src/main/java/io/cartogra/common/api/ErrorCodes.java:29-32`; `AuditKindTest.apiKeyIsHiddenFromGuests` passes (6/6 in the class) |
| T2 | PASS | `shared/common/src/main/java/io/cartogra/common/identity/Actor.java:11,16-18,28-30`; `ActorTest.apiKeyActorHasNoUserAndIsNotAdmin` passes; `./gradlew build` passes with no change at the call sites |
| T3 | PASS | `shared/web/src/main/java/io/cartogra/web/filter/InternalTokenFilter.java:83-89`; `InternalTokenFilterTest.apiKeySubjectBecomesApiKeyActor` passes; the old tests of the class pass (11/11) |
| T4 | PASS | `services/registry/src/main/resources/db/migration/V020__create_api_keys.sql:1-23`; `PlanLimits.java:3`, `JdbcPlanLimitRepository.java:24,35`; `registry.RowLevelSecurityIT.apiKeysAreIsolatedByTenant` and `FlywayMigrationSmokeTest` (6/6) pass |
| T5 | PASS | `services/registry/src/main/java/io/cartogra/registry/domain/ApiKeyService.java:52-94`; `ApiKeyCrudIT.createReturnsTheKeyOnceAndStoresOnlyTheHash`, `.createAcceptsAFutureExpiry`, `.createRejectsInvalidInputWith400`, `.planLimitCountsActiveOnly`; `api.WriteAuthorizationIT.apiKeyWritesNeedAnAdmin` (MEMBER, VIEWER, GUEST); `ApiKeyServiceTest.keyFormatAndHash`. All pass |
| T6 | PASS | `ApiKeyService.java:96-99`, `JdbcApiKeyRepository.java:57-69` (newest first), `ApiKey.java:24-32` (status); `ApiKeyCrudIT.listShowsStatusesWithoutSecrets` passes |
| T7 | PASS | `ApiKeyService.java:101-109`, `JdbcApiKeyRepository.java:89-97`; `ApiKeyCrudIT.revokeIsIdempotentAndAuditedOnce`, `.revokeOfAnUnknownOrForeignKeyReturns404`; `WriteAuthorizationIT` (DELETE). All pass |
| T8 | PASS | `services/gateway/src/main/java/io/cartogra/gateway/domain/ApiKeyAuthenticator.java:27-33`, `infrastructure/jdbc/JdbcApiKeyRepository.java:25-45`; `test-schema.sql` has `api_keys` with RLS; `JdbcApiKeyRepositoryIT` 6/6 pass |
| T9 | PASS | `ApiKeyAuthenticationFilter.java:42-76`, `ApiKeyAllowlist.java:14-23`, `TenantInjectionFilter.java:55-58,74-85`, `SecurityConfig.java:71,97-98`; `ApiKeyAuthIT` 11/11 pass; the stub receives the subject `apikey:<id>` and the role `CI_CHECK` (`ApiKeyAuthIT.activeKeyIsProxiedAsItsTenantWithTheCiCheckRole`) |
| T10 | PASS (weak test, F-2) | `RateLimitFilter.java:94-96`; `ApiKeyRateLimitIT.keyBucketDoesNotDrainTenantBucket` passes |
| T11 | PASS | `services/contract/src/main/resources/db/migration/V007__contract_checks.sql:1-24`; `JdbcContractCheckRepository.java`; `contract.RowLevelSecurityIT.contractChecksIsolatesTenants` passes |
| T12 | PASS | `ContractCheckService.java:52-73`, `ContractCheckController.java:28-35`, `contract/config/SecurityConfig.java:50`; `ContractCheckIT.breakingByName`, `.compatibleById`, `.neverCreatesVersion` pass |
| T13 | PASS | `ContractCheckService.java:75-89`, `contract/api/GlobalExceptionHandler.java:58-71`; `ContractCheckIT.notFoundUnknownName`, `.notFoundServiceOfAnotherTenant`, `.notFoundDeletedService`, `.ambiguousName`, `.noBaseline`, `.invalidSpec`; `contract.api.WriteAuthorizationIT.adminJwtRoleCannotCallCheck`. All pass |
| T14 | PASS | `frontend/src/routes/_authenticated/settings.api-keys.tsx` (no `underDevelopment`); `placeholder-pages.test.tsx` no longer lists API keys; vitest "viewer sees the list and no actions" passes |
| T15 | PASS | `ApiKeyCreateDialog.tsx`, `ApiKeyRevealDialog.tsx`, `useApiKeys.ts`; vitest "admin creates, sees key once, revokes" and "plan limit error shown" pass |
| T16 | PASS | `frontend/src/lib/audit-format.ts:12,36`; vitest "formats api_key events" passes |
| T17 | UNVERIFIED | `frontend/e2e/api-keys.spec.ts` exists and agrees with S-20 and the changed S-21. I did not run it: nothing listens on port 8080, and the dev stack is the user's to start. The only evidence is the claim "e2e 10/10" in `ship-log.md` |
| T18 | PASS | `seed/acme-fintech/src/api.ts`, `src/verify.ts:252-293`; vitest `verify.test.ts` "breaking verify runs the CI check and revokes the key" and "revokes the key when the check fails" pass (28/28) |
| T19 | UNVERIFIED (intended) | The step exists at `infra/helm/local-test.sh:448-488`. It was not run (the k3d run is a separate step). I ran its `node` body builder alone, and it gives a valid `{service, spec}` body |
| T20 | PASS with a gap (F-3) | The docs, openapi files and CONTEXT files are updated. `services/contract/CONTEXT.md:3` still says "CI checks ... planned" |

### Business rules

| ID | Result | Evidence (code / test that fails when the rule breaks) |
|---|---|---|
| BR-1 | PASS | `ApiKeyService.java:52,101` (`@PreAuthorize(ADMIN_ONLY)`), list has no role check; `WriteAuthorizationIT.apiKeyWritesNeedAnAdmin`, `ApiKeyCrudIT.listShowsStatusesWithoutSecrets` (VIEWER gets 200) |
| BR-2 | PASS | `ApiKeyService.java:111-128`; `ApiKeyResponse.java` has no key or hash field; `ApiKeyServiceTest.keyFormatAndHash`, `ApiKeyCrudIT.createReturnsTheKeyOnceAndStoresOnlyTheHash`, `.listShowsStatusesWithoutSecrets` |
| BR-3 | PASS | `ApiKeyService.java:56-67`, `ApiKeyScope.java:18-23`; `ApiKeyCrudIT.createRejectsInvalidInputWith400` |
| BR-4 | PASS | `ApiKeyService.java:69-71`; no update endpoint in `ApiKeyController.java`; `createRejectsInvalidInputWith400` (past expiry) |
| BR-5 | PASS | `ApiKeyService.java:76-93` (advisory lock, count of active keys), `JdbcApiKeyRepository.java:79-87`; `ApiKeyCrudIT.planLimitCountsActiveOnly` (402, as changed during execution) |
| BR-6 | PASS | `ApiKey.java:24-32`, `ApiKeyService.java:104-108`, gateway `JdbcApiKeyRepository.java:26-32`; `ApiKeyServiceTest.statusFollowsTimestamps`, `ApiKeyCrudIT.revoke*`, `ApiKeyAuthIT.revoked/expiredKeyIsUnauthorized` |
| BR-7 | PASS | `ApiKeyAuthenticationFilter.java:44-48`, `TenantInjectionFilter.java:37` (header stripped); `ApiKeyAuthIT.headerIsIgnoredOffTheAllowlist`, `.jwtAppliesOffTheAllowlistEvenWithAKeyHeader` |
| BR-8 | PASS | `ApiKeyAuthenticationFilter.java:49-75`; `ApiKeyAuthIT` (each case), `ApiKeyAuthenticationFilterTest` |
| BR-9 | PASS (weak test, F-2) | `RateLimitFilter.java:94-96`; `ApiKeyRateLimitIT` fails if the key shares the tenant bucket, but not if it falls back to the IP bucket |
| BR-10 | PASS | gateway `JdbcApiKeyRepository.java:38-45`; `JdbcApiKeyRepositoryIT.lastUsedIsSetOnceForTwoCallsInTenSeconds` |
| BR-11 | PASS | `ContractCheckService.java:75-89`, `JdbcServiceProjectionRepository.java:63-80`; `ContractCheckIT.notFound*`, `.ambiguousName`, `.breakingByName` ("bIlLiNg") |
| BR-12 | PASS | `ContractCheckService.java:61-72`; `ContractCheckIT.breakingByName` (12 consumers), `.compatibleById` (empty) |
| BR-13 | PASS | `ContractCheckService.java:59-63`, handler `GlobalExceptionHandler.java:68-71`; `ContractCheckIT.noBaseline`, `.invalidSpec` (422 and 400, row count 0) |
| BR-14 | PASS | `ContractCheckService.java:68-70` (one insert, no audit or Kafka call); `ContractCheckIT.breakingByName` (1 row with `api_key_id`), `.neverCreatesVersion`. No test asserts "no audit or Kafka event"; the code has no such call |
| BR-15 | PASS | `ApiKeyService.java:130-137` (payload: name, prefix, scopes, expiresAt); `ApiKeyCrudIT.createReturnsTheKeyOnceAndStoresOnlyTheHash` (payload has no key or hash, actor is the admin), `AuditKindTest.apiKeyIsHiddenFromGuests` |

### Scenarios

| ID | Result | Evidence |
|---|---|---|
| S-1 | PASS | `ApiKeyCrudIT.createReturnsTheKeyOnceAndStoresOnlyTheHash` (201, `cgk_`, active, SHA-256 in the row, one `created` audit event without the key or hash) |
| S-2 | PASS | `registry.api.WriteAuthorizationIT.apiKeyWritesNeedAnAdmin` (POST and DELETE, MEMBER/VIEWER/GUEST, 403) |
| S-3 | PASS | `ApiKeyCrudIT.listShowsStatusesWithoutSecrets` (VIEWER, 3 statuses, no key/hash, no tenant B key) |
| S-4 | PASS | `ApiKeyCrudIT.createRejectsInvalidInputWith400` (`[]`, `["admin"]`, blank, 101 characters) |
| S-5 | PASS | `ApiKeyCrudIT.createRejectsInvalidInputWith400` (past expiry) |
| S-6 | PASS | `ApiKeyCrudIT.planLimitCountsActiveOnly` (free plan, max 2: 201 then 402 `PLAN_LIMIT_EXCEEDED`) |
| S-7 | PASS | `ApiKeyCrudIT.revokeIsIdempotentAndAuditedOnce`, `.revokeOfAnUnknownOrForeignKeyReturns404` |
| S-8 | PASS | `ApiKeyAuthIT.headerIsIgnoredOffTheAllowlist` (401), `.jwtAppliesOffTheAllowlistEvenWithAKeyHeader` (200) |
| S-9 | PASS | `ApiKeyAuthIT.jwtWithNoKeyIsForbiddenOnTheAllowlistedEndpoint`, `.noKeyAndNoJwtIsUnauthorized`, `.unknownKeyIsUnauthorized`, `.revokedKeyIsUnauthorized`, `.expiredKeyIsUnauthorized`, `.keyWithoutTheScopeIsForbidden`, `.keyWinsOverAJwtOfAnotherTenant` |
| S-10 | PASS | `ApiKeyAuthIT.revokeWorksOnTheNextRequest`. The revoke is an SQL update, not the admin API, because the gateway IT has no registry |
| S-11 | PASS (weak, F-2) | `ApiKeyRateLimitIT.keyBucketDoesNotDrainTenantBucket` |
| S-12 | PASS | `JdbcApiKeyRepositoryIT.lastUsedIsSetOnceForTwoCallsInTenSeconds` |
| S-13 | PASS | `ContractCheckIT.breakingByName` (name in mixed case, `path-removed` + `parameter-required-added`, 12 consumers, 1 row with the key id, 2 versions, 1 edge query) |
| S-14 | PASS | `ContractCheckIT.compatibleById` |
| S-15 | PASS | `ContractCheckIT.notFoundUnknownName`, `.notFoundServiceOfAnotherTenant`, `.notFoundDeletedService` (404 `SERVICE_NOT_FOUND`, 0 rows) |
| S-16 | PASS | `ContractCheckIT.ambiguousName` (409, 0 rows) |
| S-17 | PASS | `ContractCheckIT.noBaseline` (404 `BASELINE_NOT_FOUND`, 0 rows) |
| S-18 | PASS | `ContractCheckIT.invalidSpec` (swagger 2.0 gives 422 with messages; no spec gives 400; 0 rows) |
| S-19 | PASS | `AuditKindTest.apiKeyIsHiddenFromGuests` (unit seam, as the plan names) |
| S-20 | UNVERIFIED (e2e) | The spec is `frontend/e2e/api-keys.spec.ts:47-78`. Not run by me. The vitest "admin creates, sees key once, revokes" passes, and covers the same steps with a mocked API |
| S-21 | UNVERIFIED (e2e) | The spec is `frontend/e2e/api-keys.spec.ts:80-111` (changed S-21: Admin-role alert, no buttons, API GET 200, POST 403). Not run by me. The API half is also covered by `ApiKeyCrudIT` (VIEWER list 200) and `WriteAuthorizationIT` (VIEWER POST 403) |

### Acceptance criteria

| ID | Result | Evidence |
|---|---|---|
| AC-1 | PASS (UI e2e not run) | `ApiKeyCrudIT.createReturnsTheKeyOnceAndStoresOnlyTheHash`; vitest "admin creates, sees key once, revokes" |
| AC-2 | PASS | `WriteAuthorizationIT.apiKeyWritesNeedAnAdmin`; `ApiKeyCrudIT.listShowsStatusesWithoutSecrets`; the UI half is in the e2e spec (not run) |
| AC-3 | PASS | `ApiKeyCrudIT.planLimitCountsActiveOnly` |
| AC-4 | PASS | `ApiKeyAuthIT.revokedKeyIsUnauthorized`, `.expiredKeyIsUnauthorized`, `.revokeWorksOnTheNextRequest` |
| AC-5 | PASS | `ApiKeyAuthIT.headerIsIgnoredOffTheAllowlist`, `.jwtWithNoKeyIsForbiddenOnTheAllowlistedEndpoint` |
| AC-6 | PASS | `ContractCheckIT.breakingByName`, `.compatibleById` |
| AC-7 | PASS | `ContractCheckIT.breakingByName`, `.neverCreatesVersion`, and the 0-row asserts in `.notFound*`, `.ambiguousName`, `.noBaseline`, `.invalidSpec` |
| AC-8 | PASS | `ApiKeyCrudIT` (create and revoke events, no secret), `AuditKindTest.apiKeyIsHiddenFromGuests` |
| AC-9 | PASS (weak test, F-2) | `RateLimitFilter.java:94-96`, `ApiKeyRateLimitIT` |
| AC-10 | UNVERIFIED (intended) | k3d not run. The tenant plan of Acme on k3d and production has no evidence (plan Notes: "Check the plan of the acme tenant on k3d and prod before T18 is complete") |
| AC-11 | UNVERIFIED | Open. This review and the code review are the input for it |

### Data model

| Item | Result | Evidence |
|---|---|---|
| `api_keys` | PASS | `V020__create_api_keys.sql:1-23` agrees with the table of the plan (no tenant FK, as changed during execution). Partial index and RLS `ENABLE` + `FORCE` + policy are present. The gateway `test-schema.sql` has the same table and policy |
| `PlanLimits.maxApiKeys` | PASS | `PlanLimits.java:3`, `JdbcPlanLimitRepository.java:24,35` |
| `contract_checks` | PASS | `V007__contract_checks.sql:2-22` agrees with the plan, with the index and RLS. Line 24 adds the `services (tenant_id, lower(name))` index |
| DTOs | PASS | `ApiKeyResponse` = `{id, name, prefix, scopes, status, expiresAt, lastUsedAt, createdAt}`; `CreatedApiKeyResponse` adds `key`; `ContractCheckResponse` = `{isBreaking, baselineVersion, changes, affectedConsumers}` |

### Assertion changes in existing tests

| Test | Change | Support |
|---|---|---|
| `frontend/src/test/routes/placeholder-pages.test.tsx` | Removed the "API keys" row | T14 "Done when" |

All the other changes to existing test files only add tests.

## 3. Findings

### F-1 (major): the unauthenticated requests on the check endpoint skip the IP rate limit

- Where: `services/gateway/src/main/java/io/cartogra/gateway/config/SecurityConfig.java:71-72`, `services/gateway/src/main/java/io/cartogra/gateway/config/ApiKeyAuthenticationFilter.java:54-65`.
- Problem: `ApiKeyAuthenticationFilter` now runs before `RateLimitFilter`. On `POST /api/v1/contracts/check`, a request with no key, with a JWT only, with an unknown key, or with a key that has no scope gets its 401/403 from `ApiKeyAuthenticationFilter`. The chain stops there, so `RateLimitFilter` never runs. Before this change, each unauthenticated request used the `ip:<addr>` bucket (`RateLimitFilter.java:103-110`). Also, each request with a key header does one Postgres lookup in system scope (`ApiKeyAuthenticator.java:28-32`). An anonymous client can thus send an unlimited flow of database queries to the production gateway. The gateway uses the same pool for login and guest mint. Key guessing is not a risk (256 bits), but load on the database is a real risk.
- Fix: Apply the IP bucket before the key lookup on an allowlisted endpoint. For example, put `RateLimitFilter` before `ApiKeyAuthenticationFilter` for the anonymous scope, or let `ApiKeyAuthenticationFilter` consume an `ip:` token before it calls `authenticate`. Then add an IT: N requests with unknown keys from one IP give 429.

### F-2 (minor): the bucket test cannot see a fall back to the IP bucket

- Where: `services/gateway/src/test/java/io/cartogra/gateway/api/ApiKeyRateLimitIT.java:18-30`.
- Problem: If someone removes `RateLimitFilter.java:94-96`, the key requests use `ip:127.0.0.1`. The JWT request uses `tenant:<id>`, so it still gets 200, and the test still passes. Thus no test fails when BR-9 ("its own bucket `apikey:<id>`") breaks in this way.
- Fix: Add a second active key of the same tenant from the same client. Drain the first key, then assert that the second key gets 200. Alternatively, assert that the Redis key `rate_limit:apikey:<id>:default` exists.

### F-3 (minor): a doc still says that CI checks are planned

- Where: `services/contract/CONTEXT.md:3` ("CI checks, alerts planned").
- Problem: T20 "Done when" says that no doc says the feature is planned. Line 87 of the same file says that `/check` is live.
- Fix: Move "CI checks (5.5)" to the "Live" list of line 3.

### F-4 (minor): a failed key create stops the whole Acme verify

- Where: `seed/acme-fintech/src/verify.ts:274`.
- Problem: `api.createApiKey` is outside the `try`. If the create fails (for example 402 when the Acme plan has no free key slot), `verify` rejects. The report then has no named failed check, and the later checks (graph, risk) do not run. The plan Notes ask to check the plan limit of Acme, and there is no evidence of this check.
- Fix: Put the create in the `try`, and `add` a failed "CI check key created" item with the error. Record the Acme plan (`max_api_keys`) on k3d and production in the plan before AC-10.

### F-5 (minor): the expiry date is the end of the day in UTC, not in the local time zone

- Where: `frontend/src/components/ApiKeyCreateDialog.tsx:85`.
- Problem: The date input gives `YYYY-MM-DD`, and the code sends `YYYY-MM-DDT23:59:59Z`. For a user west of UTC in the evening, today's date is already in the past in UTC, so the server gives 400 (BR-4). For each user, the key stops at midnight UTC, not at the end of the local day. The input has no `min`, so the UI permits a date in the past.
- Fix: Build the instant from the local end of the day (`new Date(y, m - 1, d, 23, 59, 59)`), and set `min` on the input to today.

## 4. Checklist problems

- Ticks without independent evidence:
  - `T17`, `S-20`, `S-21`, and "Verification: `pnpm exec playwright test e2e/api-keys.spec.ts` passes". I did not run the e2e spec, because nothing listens on port 8080. The only evidence is `ship-log.md`.
  - `AC-1` and `AC-2` depend in part on the e2e spec (the UI half). The API half is verified.
  - `AC-9`: ticked, but the test is weak (F-2).
  - `T20`: ticked, but `services/contract/CONTEXT.md:3` still says "planned" (F-3).
- Correct open items: `T19`, `AC-10`, `AC-11`, and the k3d verification line are not ticked. That is correct.
- Passed items without a tick: none.

## 5. Out-of-plan changes

| Change | Note |
|---|---|
| `.claude/skills/execute/SKILL.md`, `.claude/skills/review-plan/SKILL.md`, `.claude/skills/security-audit/SKILL.md` | They come from commit `c00ae58` on this branch (`chore(skills)`), not from the implementation. They are not part of plan 034 |
| `.claude/rules/backend.md:294` | It replaces the "planned" note for `X-Cartogra-Api-Key`. This is a doc change that T20 does not list. It agrees with the code |
| `frontend/src/lib/permissions.ts` | Decision 14 names it, but no task lists it in `Files` |
| `ApiKeyStatus.java`, `CreatedApiKey.java`, `ConsumerService.allConsumersOfService`, `ServiceProjectionRepository.findLiveId`/`findLiveIdsByName` | Support code for T5, T6 and T12 |
| `ApiKeyAuthenticationFilterTest`, `ApiKeyAuthenticatorTest`, `TenantInjectionFilterTest` (new case), gateway `RowLevelSecurityIT` (new case), `ContractCheckServiceTest`, `ActorTest.twoArgumentActorsHaveNoApiKey` | Extra tests, not named by a task |

No change touches an item of "Out of scope".

## 6. Commands that I ran

| Command | Result |
|---|---|
| `git diff --stat origin/main`, `git ls-files --others --exclude-standard`, `git log --oneline origin/main..HEAD` | 51 tracked files changed, 49 new files; 3 commits ahead (skills chore, CONTEXT terms, plan) |
| `./gradlew --continue :shared:common:test --tests '*AuditKindTest' --tests '*ActorTest' :shared:web:test --tests '*InternalTokenFilterTest' :services:registry:test --tests '*ApiKeyCrudIT' --tests '*ApiKeyServiceTest' --tests '*api.WriteAuthorizationIT' --tests '*registry.RowLevelSecurityIT' --tests '*FlywayMigrationSmokeTest' :services:gateway:test --tests '*ApiKeyAuthIT' --tests '*ApiKeyRateLimitIT' --tests '*ApiKeyAuthenticatorTest' --tests '*JdbcApiKeyRepositoryIT' --tests '*ApiKeyAuthenticationFilterTest' --tests '*TenantInjectionFilterTest' --tests '*gateway.RowLevelSecurityIT' :services:contract:test --tests '*ContractCheckIT' --tests '*ContractCheckServiceTest' --tests '*contract.api.WriteAuthorizationIT' --tests '*contract.RowLevelSecurityIT'` | BUILD SUCCESSFUL. All test tasks executed (not up to date). 0 failures: AuditKindTest 6, ActorTest 2, InternalTokenFilterTest 11, ApiKeyCrudIT 7, ApiKeyServiceTest 3, registry WriteAuthorizationIT 8, registry RowLevelSecurityIT 4, FlywayMigrationSmokeTest 6, ApiKeyAuthIT 11, ApiKeyRateLimitIT 1, ApiKeyAuthenticatorTest 3, JdbcApiKeyRepositoryIT 6, ApiKeyAuthenticationFilterTest 7, TenantInjectionFilterTest 6, gateway RowLevelSecurityIT 4, ContractCheckIT 9, ContractCheckServiceTest 7, contract WriteAuthorizationIT 5, contract RowLevelSecurityIT 6 |
| `./gradlew build` | BUILD SUCCESSFUL. The test tasks came FROM-CACHE or UP-TO-DATE, so a run with the same inputs passed before |
| `pnpm exec vitest run src/test/routes/settings.api-keys.test.tsx src/test/lib/audit-format.test.ts src/test/routes/placeholder-pages.test.tsx` (frontend) | 3 files, 13 tests pass |
| `pnpm typecheck` and `pnpm lint` (frontend) | No errors |
| `pnpm exec vitest run test/verify.test.ts` and `pnpm typecheck` (seed/acme-fintech) | 28 tests pass; no type errors |
| `node --input-type=module -e "<body builder of the T19 step>"` (seed/acme-fintech, Node v24.19.0) | Gives `{"service":"LEDGER-SERVICE","spec":"openapi: \"3.0.3\" ..."}` |
| `ss -ltnp \| grep ':8080 '` | Nothing listens. Thus the e2e spec was not run |

I did not run `infra/helm/local-test.sh`, start containers outside Testcontainers, or call cartogra.dev.

## Code review

Fixed point: origin/main (40727e8), uncommitted working tree.

### Standards

3 documented-standard breaches, 3 soft breaches, 7 smells. No AGENTS.md rule is broken.

**AGENTS.md rules: no violations**
- `SecurityContextHolder` is used only in filters.
- Controllers take `@AuthenticationPrincipal Actor`; services take `Actor`.
- `ApiKeyResponse` leaves out `createdBy`; the audit payload has no user ids.
- No query in a loop: `withNames` fetches names in one batch.
- The list uses the shared `PageResult`.

**Documented-standard breaches (hard)**
1. `services/contract/src/main/java/io/cartogra/contract/domain/ConsumerService.java:71` calls `findConsumers(..., Integer.MAX_VALUE, 0)`. backend.md (Spring Data JDBC): "NEVER unbounded queries on large tables". On a CI request path.
2. `services/registry/src/main/java/io/cartogra/registry/domain/ApiKeyService.java:96` does not clamp `limit` (same rule). `AuditEventService:29` clamps its limit.
3. `frontend/src/routes/_authenticated/settings.api-keys.tsx:80` uses a raw `<table>`. frontend.md: "Tables: TanStack Table". (`contracts.index.tsx` does the same.)

**Soft breaches**
- `services/gateway/src/main/java/io/cartogra/gateway/infrastructure/jdbc/JdbcApiKeyRepository.java:25,38`: no `tenant_id` filter (backend.md Multi-Tenancy). The tenant is not known before the lookup; Javadoc and gateway CONTEXT.md:110 explain it. Acceptable, but visible to a reviewer.
- `frontend/src/hooks/useApiKeys.ts:6`: queryKey `['api-keys']` has no tenant id (frontend.md). No hook in the repo follows this rule.
- Fully qualified names: gateway `SecurityConfig.java:97`, `TenantInjectionFilter.java` `proceedAsTenant`, registry `ApiKeyStatus.java:7`. Style only.

**Baseline smells (judgement calls)**
- Shotgun Surgery / Primitive Obsession: endpoint and scope literals in 7 places (`ApiKeyAllowlist.java:15`, gateway `SecurityConfig.java:97-98`, contract `SecurityConfig.java:50`, `ContractCheckService.java:52`, registry `ApiKeyScope.java:6`, frontend `ApiKeyCreateDialog.tsx:13`). `Roles` has no `CI_CHECK` constant.
- Duplicated Code: SHA-256 hex hash in `ApiKeyService.hash` (registry :121) and `ApiKeyAuthenticator.hash` (gateway :35). Move to `shared/common`.
- Duplicated Code: `"apikey:"` in `ApiKeyAuthentication.subject()`, `RateLimitFilter`, `InternalTokenFilter.API_KEY_PREFIX`.
- Duplicated Code: `ApiKeyAuthentication` constructor maps the scopes two times.
- Data Clumps: `CreatedApiKeyResponse` copies the 8 fields of `ApiKeyResponse` plus `key`.
- Speculative Generality: `ContractCheckRepository.countByService` has no caller.
- Divergent Change: contract `V007__contract_checks.sql:24` also adds an index on the `services` projection.

**Worst:** unbounded consumer query `allConsumersOfService` with `Integer.MAX_VALUE` at `ConsumerService.java:71`.

### Spec

The diff covers nearly all of the spec (T1–T20, S-1 to S-21) and follows each item of "Changes during execution". Test names match the plan.

**(a) Missing or partial**
1. T20 lists `CONTEXT-MAP.md`, but the diff does not change it. Minor: the file has no "planned" note (line 30 already says "API Key").
2. Note line 471: "Check the plan of the acme tenant on k3d and prod before T18 is complete." No evidence of this check. Confirm by hand; not on prod without approval.

**(b) Scope creep (none from `## Out of scope`)**
1. `services/registry/src/main/java/io/cartogra/registry/domain/PlanLimits.java:6-8`: a new 4-argument constructor defaults `maxApiKeys = UNLIMITED`. The spec asked only to "add `maxApiKeys` to `PlanLimits`". If production code calls it, the limit check stops silently. Remove it and update `TeamServiceTest` and `ServiceServiceTest`.
2. `services/gateway/src/main/java/io/cartogra/gateway/config/TenantInjectionFilter.java:37`: the gateway removes `X-Cartogra-Api-Key` before proxying. Not asked for; safe and helps security.

**(c) Implemented, but the behaviour looks wrong**
1. BR-9 "A request authenticated by a key uses its own rate-limit bucket": on `/contracts/check`, `ApiKeyAuthenticationFilter` answers 401/403 before `RateLimitFilter` runs (`services/gateway/src/main/java/io/cartogra/gateway/config/SecurityConfig.java:71-72`, `ApiKeyAuthenticationFilter.java:78-94`). Requests with no key, a JWT only, or a bad key get no ip bucket, and each bad key does a DB lookup with no limit. Before, unauthenticated requests used the `ip:` bucket. DoS regression, not key guessing.
2. BR-7 / Decision 5: `ApiKeyAllowlist.requiredScope` matches the exact `getRequestURI()` (`ApiKeyAllowlist.java:21-22`). `/api/v1/contracts/check/` is not on the allowlist, so a valid key is ignored there. Still secure (contract `@PreAuthorize("hasRole('CI_CHECK')")` and the `apiKeyId` check refuse it), but CI gets an unclear 401. Low risk.

**Count:** 6 findings (2 missing/partial, 2 scope creep, 2 behaviour). **Worst:** (c)1, failed key requests on the CI check endpoint have no rate limit and each does a DB lookup.

**Summary:** Standards 13 findings (3 hard, 3 soft, 7 smells), worst: unbounded consumer query `ConsumerService.java:71`. Spec 6 findings, worst: failed key requests on `/contracts/check` skip the rate limit.
