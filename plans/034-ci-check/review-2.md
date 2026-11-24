# Review (round 2): plan 034, CI check + tenant API keys (5.5)

Reviewer: an independent plan review on 2026-11-24. The review covers the uncommitted working tree of `/home/allan/developer/cartogra` (branch `feat/5.5-ci-check`), compared with `origin/main`. It includes `## Changes during execution` and the fix tasks T21 to T26. The round 1 review is in `review-1.md`. Section 7 gives the status of each round 1 finding.

## 1. Verdict

**CHANGES REQUIRED**

- Blockers: 0
- Major: 1 (R2-F1: T22 is not met end to end. A valid key on `POST /api/v1/contracts/check/` gets 403 from the contract service.)
- Minor: 4

All the tests that the plan names exist, and they pass in my run. T21 fixes the round 1 major finding (F-1). T19, AC-10 and AC-11 are UNVERIFIED by design (k3d step).

## 2. Plan coverage

### Tasks

| ID | Result | Evidence |
|---|---|---|
| T1 | PASS | `shared/common/src/main/java/io/cartogra/common/audit/AuditKind.java` (`API_KEY("api_key", Set.of())`), `ErrorCodes.java`; `AuditKindTest` 6/6 pass, including `apiKeyIsHiddenFromGuests` |
| T2 | PASS | `shared/common/src/main/java/io/cartogra/common/identity/Actor.java` (3-component record, 2-argument constructor, `Actor.apiKey`); `ActorTest` 2/2 pass; build compiles with no change at the call sites |
| T3 | PASS | `shared/web/src/main/java/io/cartogra/web/filter/InternalTokenFilter.java` (`toActor`, prefix `apikey:`); `InternalTokenFilterTest` 11/11 pass |
| T4 | PASS | `services/registry/src/main/resources/db/migration/V020__create_api_keys.sql`; `JdbcPlanLimitRepository.java:24,35`; registry `RowLevelSecurityIT` 4/4 and `FlywayMigrationSmokeTest` 6/6 pass |
| T5 | PASS | `services/registry/src/main/java/io/cartogra/registry/domain/ApiKeyService.java:53-95`; `ApiKeyCrudIT` 7/7, registry `api.WriteAuthorizationIT` 8/8, `ApiKeyServiceTest.keyFormatAndHash` pass |
| T6 | PASS | `ApiKeyService.java:97-102`; `ApiKeyCrudIT.listShowsStatusesWithoutSecrets` passes |
| T7 | PASS | `ApiKeyService.java:104-112`; `ApiKeyCrudIT.revoke*`, `WriteAuthorizationIT` pass |
| T8 | PASS | `services/gateway/src/main/java/io/cartogra/gateway/infrastructure/jdbc/JdbcApiKeyRepository.java:24-45`, `domain/ApiKeyAuthenticator.java:27-33`; `JdbcApiKeyRepositoryIT` 6/6 pass |
| T9 | PASS | `services/gateway/src/main/java/io/cartogra/gateway/config/ApiKeyAuthenticationFilter.java:45-84`, `TenantInjectionFilter.java` (`doFilterAsApiKey`); `ApiKeyAuthIT` 12/12 pass |
| T10 | PASS | `services/gateway/src/main/java/io/cartogra/gateway/config/RateLimitFilter.java:113-115`; `ApiKeyRateLimitIT.keyBucketDoesNotDrainTenantBucket` passes |
| T11 | PASS | `services/contract/src/main/resources/db/migration/V007__contract_checks.sql`; contract `RowLevelSecurityIT` 6/6 pass |
| T12 | PASS | `services/contract/src/main/java/io/cartogra/contract/domain/ContractCheckService.java:52-73`; `ContractCheckIT.breakingByName`, `.compatibleById`, `.neverCreatesVersion` pass |
| T13 | PASS | `ContractCheckService.java:75-89`; `ContractCheckIT` 9/9 and contract `api.WriteAuthorizationIT` 5/5 pass |
| T14 | PASS | `frontend/src/routes/_authenticated/settings.api-keys.tsx`; vitest "viewer sees the list and no actions" passes; `placeholder-pages.test.tsx` has no API keys row |
| T15 | PASS | `ApiKeyCreateDialog.tsx`, `ApiKeyRevealDialog.tsx`, `useApiKeys.ts`; vitest "admin creates, sees key once, revokes" and "plan limit error shown" pass |
| T16 | PASS | `frontend/src/lib/audit-format.ts`; vitest "formats api_key events" passes |
| T17 | PASS (log) | `frontend/e2e/api-keys.spec.ts:47-111`. I did not run the e2e (forbidden in this round). Evidence: the orchestrator log `e2e2.log` shows 10 passed, including `api-keys.spec.ts:47` and `:80`. The log is from 12:30, after the last frontend change (12:24:44) |
| T18 | PASS | `seed/acme-fintech/src/verify.ts:252-299`, `src/api.ts`; vitest "breaking verify runs the CI check and revokes the key" passes (29/29 in the file) |
| T19 | UNVERIFIED (k3d step) | The step is at `infra/helm/local-test.sh` ("Check 5.5 CI check"). It was not run, by design |
| T20 | PASS with a gap (R2-F4) | The openapi files, `data-model.md`, `system-overview.md` and the 3 `CONTEXT.md` files are updated. `docs/bip/1.48-gateway-auth-rationale.md` still says that API keys are planned |
| T21 | PASS | `ApiKeyAuthenticationFilter.java:53-56` calls `RateLimitFilter.allowBeforeKeyLookup` (`RateLimitFilter.java:98-109`) before the header check and the lookup. `ApiKeyRateLimitIT.badKeysAreRateLimitedByIp` passes: 10 bad keys give 401 then 429, and `findActiveByHash` runs at most 4 times. The test fails if the IP bucket is removed (10 lookups) |
| T22 | FAIL (R2-F1) | The gateway part is done: `ApiKeyAllowlist.java:109-111`, gateway `SecurityConfig.java:97-99`; `ApiKeyAuthIT.trailingSlashIsOnTheAllowlist` passes. But the test stubs the downstream for `/check/` (`ApiKeyAuthIT.java:114-116`). The real contract service refuses `/check/` with 403. Thus "Done when: the same result as without the slash" is false |
| T23 | PASS | `ConsumerService.java:31,71-74` (500); `ApiKeyService.java:97-101` (clamp 1..200, offset 0 or more); `ConsumerServiceTest.consumersAreBounded`, `ApiKeyServiceTest.listClampsLimit` pass. Each test fails if the bound is removed |
| T24 | PASS | `verify.ts:274-280` (create in `try`, one failed check, `return`); vitest "a failed key create is one failed check" passes (detail has the error, 0 checks run) |
| T25 | PASS | `services/contract/CONTEXT.md:3` lists "CI check (5.5)" as live |
| T26 | PASS | `settings.api-keys.tsx:75-132` uses `@tanstack/react-table` (`useTable`, `createColumnHelper`); the 3 vitest tests pass; e2e evidence as for T17 |

### Business rules

| ID | Result | Evidence |
|---|---|---|
| BR-1 | PASS | `ApiKeyService.java:53,104` (`@PreAuthorize(ADMIN_ONLY)`); `WriteAuthorizationIT.apiKeyWritesNeedAnAdmin`; `ApiKeyCrudIT.listShowsStatusesWithoutSecrets` (VIEWER 200) |
| BR-2 | PASS | `ApiKeyService.java:114-131`; `ApiKeyServiceTest.keyFormatAndHash`, `ApiKeyCrudIT.createReturnsTheKeyOnceAndStoresOnlyTheHash`, `.listShowsStatusesWithoutSecrets` |
| BR-3 | PASS | `ApiKeyService.java:57-68`; `ApiKeyCrudIT.createRejectsInvalidInputWith400` |
| BR-4 | PASS | `ApiKeyService.java:70-72`; no update endpoint; `createRejectsInvalidInputWith400` (past expiry) |
| BR-5 | PASS | `ApiKeyService.java:77-94` (advisory lock, active count); `ApiKeyCrudIT.planLimitCountsActiveOnly` (402, as changed during execution) |
| BR-6 | PASS | registry `ApiKey` status, `ApiKeyService.java:106-111`, gateway `JdbcApiKeyRepository.java:26-32`; `ApiKeyServiceTest`, `ApiKeyCrudIT.revoke*`, `ApiKeyAuthIT.revoked/expiredKeyIsUnauthorized` |
| BR-7 | PASS | `ApiKeyAuthenticationFilter.java:48-52`; `ApiKeyAuthIT.headerIsIgnoredOffTheAllowlist`, `.jwtAppliesOffTheAllowlistEvenWithAKeyHeader` |
| BR-8 | PASS at the gateway; see R2-F1 for `/check/` | `ApiKeyAuthenticationFilter.java:57-83`; `ApiKeyAuthIT` (each case), `ApiKeyAuthenticationFilterTest` 10/10 |
| BR-9 | PASS (see R2-F2) | `RateLimitFilter.java:113-115`; `ApiKeyRateLimitIT.keyBucketDoesNotDrainTenantBucket` asserts the Redis key `rate_limit:apikey:<id>:default` and no `ip:` or `tenant:` default bucket (lines 58-60). A valid key is also charged to the IP lookup bucket (approved change) |
| BR-10 | PASS | gateway `JdbcApiKeyRepository.java:38-45`; `JdbcApiKeyRepositoryIT` |
| BR-11 | PASS | `ContractCheckService.java:75-89`, `JdbcServiceProjectionRepository` `findLiveId`/`findLiveIdsByName`; `ContractCheckIT.notFound*`, `.ambiguousName`, `.breakingByName` |
| BR-12 | PASS (up to 500 consumers, approved change) | `ContractCheckService.java:65-67`, `ConsumerService.java:71-74`; `ContractCheckIT.breakingByName`, `.compatibleById` |
| BR-13 | PASS | `ContractCheckService.java:59-63`; `ContractCheckIT.noBaseline`, `.invalidSpec` |
| BR-14 | PASS | `ContractCheckService.java:68-70` (one insert; no audit or Kafka call); `ContractCheckIT.breakingByName`, `.neverCreatesVersion` |
| BR-15 | PASS | `ApiKeyService.java:133-140`; `ApiKeyCrudIT` (payload has no key or hash), `AuditKindTest.apiKeyIsHiddenFromGuests` |

### Scenarios

| ID | Result | Evidence |
|---|---|---|
| S-1 | PASS | `ApiKeyCrudIT.createReturnsTheKeyOnceAndStoresOnlyTheHash` |
| S-2 | PASS | registry `api.WriteAuthorizationIT.apiKeyWritesNeedAnAdmin` |
| S-3 | PASS | `ApiKeyCrudIT.listShowsStatusesWithoutSecrets` |
| S-4 | PASS | `ApiKeyCrudIT.createRejectsInvalidInputWith400` |
| S-5 | PASS | `ApiKeyCrudIT.createRejectsInvalidInputWith400` |
| S-6 | PASS | `ApiKeyCrudIT.planLimitCountsActiveOnly` |
| S-7 | PASS | `ApiKeyCrudIT.revokeIsIdempotentAndAuditedOnce`, `.revokeOfAnUnknownOrForeignKeyReturns404` |
| S-8 | PASS | `ApiKeyAuthIT.headerIsIgnoredOffTheAllowlist`, `.jwtAppliesOffTheAllowlistEvenWithAKeyHeader` |
| S-9 | PASS | `ApiKeyAuthIT` (JWT only 403, nothing 401, unknown/revoked/expired 401, no scope 403, key wins over a tenant B JWT) |
| S-10 | PASS | `ApiKeyAuthIT.revokeWorksOnTheNextRequest` (revoke by SQL; the gateway IT has no registry) |
| S-11 | PASS | `ApiKeyRateLimitIT.keyBucketDoesNotDrainTenantBucket` |
| S-12 | PASS | `JdbcApiKeyRepositoryIT` (touch throttled) |
| S-13 | PASS | `ContractCheckIT.breakingByName` |
| S-14 | PASS | `ContractCheckIT.compatibleById` |
| S-15 | PASS | `ContractCheckIT.notFoundUnknownName`, `.notFoundServiceOfAnotherTenant`, `.notFoundDeletedService` |
| S-16 | PASS | `ContractCheckIT.ambiguousName` |
| S-17 | PASS | `ContractCheckIT.noBaseline` |
| S-18 | PASS | `ContractCheckIT.invalidSpec` |
| S-19 | PASS | `AuditKindTest.apiKeyIsHiddenFromGuests` |
| S-20 | PASS (log) | `frontend/e2e/api-keys.spec.ts:47-78`; `e2e2.log` (passed) |
| S-21 | PASS (log) | `frontend/e2e/api-keys.spec.ts:80-111` (changed S-21); `e2e2.log` (passed) |

### Acceptance criteria

| ID | Result | Evidence |
|---|---|---|
| AC-1 | PASS | `ApiKeyCrudIT.createReturnsTheKeyOnceAndStoresOnlyTheHash`; e2e `api-keys.spec.ts:47` in `e2e2.log` |
| AC-2 | PASS | `WriteAuthorizationIT.apiKeyWritesNeedAnAdmin`; `ApiKeyCrudIT.listShowsStatusesWithoutSecrets`; e2e `api-keys.spec.ts:80` |
| AC-3 | PASS | `ApiKeyCrudIT.planLimitCountsActiveOnly` |
| AC-4 | PASS | `ApiKeyAuthIT.revokedKeyIsUnauthorized`, `.expiredKeyIsUnauthorized`, `.revokeWorksOnTheNextRequest` |
| AC-5 | PASS | `ApiKeyAuthIT.headerIsIgnoredOffTheAllowlist`, `.jwtWithNoKeyIsForbiddenOnTheAllowlistedEndpoint` |
| AC-6 | PASS | `ContractCheckIT.breakingByName`, `.compatibleById` |
| AC-7 | PASS | `ContractCheckIT` (1 row on success, 0 rows on 404, 409, 422, 400) |
| AC-8 | PASS | `ApiKeyCrudIT`, `AuditKindTest.apiKeyIsHiddenFromGuests` |
| AC-9 | PASS | `ApiKeyRateLimitIT.keyBucketDoesNotDrainTenantBucket` (Redis key asserts) |
| AC-10 | UNVERIFIED (k3d step) | Not run, by design. The Acme plan limit on k3d and production is a manual step (T24 change) |
| AC-11 | UNVERIFIED (k3d step) | Open by design. R2-F1, R2-F3 and R2-F5 are review findings that are not fixed or answered yet |

### Data model

| Item | Result | Evidence |
|---|---|---|
| `api_keys` | PASS | `V020__create_api_keys.sql` agrees with the plan (no tenant FK, as changed). Gateway `test-schema.sql` has the same table and policy |
| `PlanLimits.maxApiKeys` | PASS | `PlanLimits.java:3`, `JdbcPlanLimitRepository.java:24,35` |
| `contract_checks` | PASS | `V007__contract_checks.sql` agrees with the plan, with the index, RLS and the `services (tenant_id, lower(name))` index |
| DTOs | PASS | `ApiKeyResponse`, `CreatedApiKeyResponse`, `ContractCheckRequest` (`@NotBlank` on both fields), `ContractCheckResponse` |

### Assertion changes in existing tests

| Test | Change | Support |
|---|---|---|
| `frontend/src/test/routes/placeholder-pages.test.tsx` | The "API keys" row is removed | T14 "Done when" |

The other changes to existing test files only add tests or fixtures (`git diff origin/main` shows no other removed test line).

## 3. Findings

### R2-F1 (major): a valid key on `/check/` gets 403 from the contract service

- Where: `services/contract/src/main/java/io/cartogra/contract/config/SecurityConfig.java:50,54`, `services/contract/src/main/java/io/cartogra/contract/api/ContractCheckController.java:28`, `services/gateway/src/test/java/io/cartogra/gateway/api/ApiKeyAuthIT.java:114-118`.
- Problem: T22 makes the gateway accept a key on `POST /api/v1/contracts/check/` and proxy the request with the path unchanged. The contract service matches only `/check`. In Spring Framework 7.0.7, the pattern `/check` does not match `/check/` (I ran `PathPatternParser.defaultInstance.parse("/check").matches("/check/")`: `false`). The request thus falls to `anyRequest().hasAnyRole("ADMIN", "MEMBER", "TEAM_OWNER")` at line 54, and the key actor (role `CI_CHECK` only) gets 403. The controller has no `/check/` mapping either. The gateway test cannot see this, because it stubs the downstream for `/check/` with 200. T22 "Done when" (the same result as without the slash) is false. CI now gets an unclear 403 in place of the round 1 unclear 401. There is no security risk.
- Fix: Make the contract service accept the slash. Use `@PostMapping({"/check", "/check/"})` (as `ContractController.java:42` does for `{"", "/"}`), and add `"/check/"` to the matcher at `SecurityConfig.java:50`. Add a `ContractCheckIT` case for `/check/`. Alternatively, revert T22 and record that only the exact path is supported.

### R2-F2 (minor): a valid key is also limited by the IP of the client, with the same numbers as its own bucket

- Where: `services/gateway/src/main/java/io/cartogra/gateway/config/ApiKeyAuthenticationFilter.java:53`, `services/gateway/src/main/java/io/cartogra/gateway/config/RateLimitFilter.java:103-104`.
- Problem: Each request on the allowlist, a valid key included, first uses `rate_limit:ip:<addr>:apikey-lookup`. That bucket has the same rate and burst as the key bucket (`default-replenish-rate` 20, `default-burst-capacity` 40). Thus all the keys that call from one IP (a NAT, a shared runner, or the ingress address if the client IP is not forwarded) share one bucket. A client with bad keys from the same IP can also empty it and block a valid CI key. User story 12 ("a burst of checks does not block me") is then weaker than BR-9 says. The plan records this design (Changes during execution, T21), so it is not a plan failure.
- Fix: Charge the IP bucket only for a failed lookup, and check that bucket (without a charge) before the lookup. Alternatively, give the lookup bucket a larger capacity than one key bucket. Add an IT with two valid keys from one client.

### R2-F3 (minor): round 1 F-5 is still open (expiry date in UTC)

- Where: `frontend/src/components/ApiKeyCreateDialog.tsx:46`, `:104`.
- Problem: The dialog still sends `${expiry}T23:59:59Z`, and the date input has no `min`. A user west of UTC who picks today in the evening gets a 400 (BR-4), and each key stops at midnight UTC. No fix task covers F-5, and the plan does not record an answer. AC-11 needs a fix or an answer.
- Fix: Build the instant from the local end of the day, and set `min` to today. Or record the answer in the plan.

### R2-F4 (minor): docs still say that API keys are planned

- Where: `docs/bip/1.48-gateway-auth-rationale.md:74,129,159,196,229,273,297`; `docs/roadmap.md:183`.
- Problem: T20 and T25 "Done when" say that no doc says the CI check or API keys are planned. The draft post `1.48` says "planned, not yet built" 7 times. The roadmap item 5.5 is not ticked. Story 5.4 ticked its roadmap item in its own docs commit (`ad18097`).
- Fix: Update the post (or record that `docs/bip/` posts are historic and out of scope). Tick roadmap 5.5 in the docs commit.

### R2-F5 (minor): round 1 Spec (b)1 is still open (`PlanLimits` 4-argument constructor)

- Where: `services/registry/src/main/java/io/cartogra/registry/domain/PlanLimits.java:6-8`.
- Problem: The constructor sets `maxApiKeys` to `UNLIMITED`. Production code uses the 5-argument form (`JdbcPlanLimitRepository.java:30`), and 9 test calls use the 4-argument form. A new production call to the 4-argument form would turn off the key limit with no error. No fix task covers it, and the plan records no answer.
- Fix: Remove the constructor and update the 9 test calls, or record the answer for AC-11.

## 4. Checklist problems

- `T22` is ticked, but its "Done when" is false end to end (R2-F1).
- `T20` and `T25` are ticked with "no doc says ... planned", but `docs/bip/1.48-gateway-auth-rationale.md` still says it (R2-F4).
- `T17`, `T26`, `S-20`, `S-21` and the Playwright verification line: the evidence is the orchestrator log `e2e2.log` (10 passed, after the last frontend change). I did not rerun the e2e, because the user forbade it in this round.
- Correct open items: `T19`, `AC-10`, `AC-11` and the k3d verification line are not ticked.
- Passed items without a tick: none.
- The checklist and the plan do not track round 1 F-5 and Spec (b)1 (R2-F3, R2-F5).

## 5. Out-of-plan changes

| Change | Note |
|---|---|
| `frontend/package.json`, `frontend/pnpm-lock.yaml` | New dependency `@tanstack/react-table` `^9.3.1`, for T26. T26 does not list these files |
| `.claude/rules/backend.md` (the `X-Cartogra-Api-Key` line) | Doc update that T20 does not list. It agrees with the code |
| `frontend/src/lib/permissions.ts` (`apiKey.manage`) | Decision 14 names it; no task lists it |
| `services/gateway/.../config/SecurityConfig.java:97-99` (`/check/` matcher) | Recorded in Changes during execution (T22) |
| `ApiKeyAuthenticationFilterTest`, `ApiKeyAuthenticatorTest`, `ContractCheckServiceTest`, new cases in `TenantInjectionFilterTest` and gateway `RowLevelSecurityIT` | Extra tests that no task names |
| `plans/034-ci-check/ship-log.md`, `review-1.md` | Process files |

No change touches an item of "Out of scope".

## 6. Commands that I ran

| Command | Result |
|---|---|
| `git diff origin/main --stat`, `git status --short`, `git ls-files --others --exclude-standard` | 54 tracked files changed, 53 untracked files (49 code and test files) |
| `./gradlew --continue :shared:common:test --rerun --tests '*AuditKindTest' --tests '*ActorTest' :shared:web:test --rerun --tests '*InternalTokenFilterTest' :services:gateway:test --rerun --tests '*ApiKeyAuthIT' --tests '*ApiKeyRateLimitIT' --tests '*ApiKeyAuthenticatorTest' --tests '*JdbcApiKeyRepositoryIT' --tests '*ApiKeyAuthenticationFilterTest' --tests '*TenantInjectionFilterTest' --tests '*gateway.RowLevelSecurityIT' --tests '*GuestRateLimitIT' --tests '*RateLimit*'` | BUILD SUCCESSFUL. 0 failures: AuditKindTest 6, ActorTest 2, InternalTokenFilterTest 11, ApiKeyAuthIT 12, ApiKeyRateLimitIT 2, ApiKeyAuthenticatorTest 3, JdbcApiKeyRepositoryIT 6, ApiKeyAuthenticationFilterTest 10, TenantInjectionFilterTest 6, gateway RowLevelSecurityIT 4, GuestRateLimitIT 1, RateLimitIT 3, RateLimitRedisFailureIT 2, RateLimitFailClosedOverrideIT 1 |
| `./gradlew --continue :services:registry:test --rerun --tests '*ApiKeyCrudIT' --tests '*ApiKeyServiceTest' --tests '*registry.api.WriteAuthorizationIT' --tests '*registry.RowLevelSecurityIT' --tests '*FlywayMigrationSmokeTest' :services:contract:test --rerun --tests '*ContractCheckIT' --tests '*ContractCheckServiceTest' --tests '*ConsumerServiceTest' --tests '*contract.api.WriteAuthorizationIT' --tests '*contract.RowLevelSecurityIT'` | BUILD SUCCESSFUL. 0 failures: ApiKeyCrudIT 7, registry WriteAuthorizationIT 8, ApiKeyServiceTest 4, FlywayMigrationSmokeTest 6, registry RowLevelSecurityIT 4, ContractCheckIT 9, contract WriteAuthorizationIT 5, ConsumerServiceTest 6, ContractCheckServiceTest 7, contract RowLevelSecurityIT 6 |
| `pnpm exec vitest run src/test/routes/settings.api-keys.test.tsx src/test/lib/audit-format.test.ts src/test/routes/placeholder-pages.test.tsx` (frontend) | 3 files, 13 tests pass |
| `pnpm typecheck`, `pnpm lint` (frontend) | No errors |
| `pnpm exec vitest run test/verify.test.ts`, `pnpm typecheck` (seed/acme-fintech) | 29 tests pass; no type errors |
| `java -cp spring-web-7.0.7.jar:spring-core-7.0.7.jar PP.java` (scratch program: `PathPatternParser.defaultInstance.parse("/check")`) | `/check` matches `/check`: true; `/check` matches `/check/`: false |
| `grep` of `build2.log` and `e2e2.log` (orchestrator logs) | `./gradlew build`: BUILD SUCCESSFUL at 12:34, after the last source change (12:24:52). Playwright: 10 passed at 12:30, after the last frontend change (12:24:44) |

I did not run `infra/helm/local-test.sh` or the e2e, start containers outside Testcontainers, or call cartogra.dev.

## 7. Status of the round 1 findings

| Round 1 finding | Status | Evidence |
|---|---|---|
| F-1 (major): bad keys skip the rate limit | FIXED | `ApiKeyAuthenticationFilter.java:53-56`, `RateLimitFilter.java:98-109`; `ApiKeyRateLimitIT.badKeysAreRateLimitedByIp` |
| F-2: bucket test cannot see a fall back to the IP bucket | FIXED | `ApiKeyRateLimitIT.java:58-60` (Redis key asserts) |
| F-3: contract CONTEXT says CI checks planned | FIXED | `services/contract/CONTEXT.md:3` |
| F-4: failed key create stops verify | FIXED | `verify.ts:274-280`; vitest "a failed key create is one failed check". The Acme plan check stays manual (T24 change) |
| F-5: expiry in UTC | OPEN | R2-F3 |
| Standards hard 1: unbounded consumer query | FIXED | `ConsumerService.java:31,71-74` |
| Standards hard 2: unclamped list limit | FIXED | `ApiKeyService.java:97-101` |
| Standards hard 3: raw table, no TanStack Table | FIXED | `settings.api-keys.tsx:75-132` |
| Spec (a)1: `CONTEXT-MAP.md` not changed | NO ACTION | The file has no "planned" note (round 1) |
| Spec (a)2: Acme plan not checked | OPEN (manual) | T24 change: a manual step for the user before AC-10 |
| Spec (b)1: `PlanLimits` 4-argument constructor | OPEN | R2-F5 |
| Spec (b)2: gateway strips the key header | NO ACTION | Safe |
| Spec (c)1: bad keys skip the rate limit | FIXED | Same as F-1 |
| Spec (c)2: trailing slash | PARTIAL | Gateway fixed; the contract service gives 403 (R2-F1) |

## Code review

### Standards

Round 1 findings:

| # | Finding | Status |
|---|---|---|
| H1 | `ConsumerService` used `Integer.MAX_VALUE` | Fixed: `MAX_CHECK_CONSUMERS = 500` (ConsumerService.java:31, :71) |
| H2 | `ApiKeyService.list` did not clamp `limit` | Fixed: `Math.clamp(limit, 1, 200)`, `Math.max(offset, 0)` (ApiKeyService.java:98-99) |
| H3 | Raw `<table>` without TanStack | Fixed: `useTable` with column helper (settings.api-keys.tsx:36-93) |
| Soft | `JdbcApiKeyRepository` has no tenant filter | Accepted (documented) |
| Soft | `['api-keys']` queryKey has no tenant id | Open (no hook in the repo follows this rule) |
| Soft | Fully qualified names | Open; one more at gateway `SecurityConfig.java:97` (`org.springframework.http.HttpMethod.POST`) |
| Smell | Endpoint/scope literals in many places | Open, worse: gateway `SecurityConfig.java:97-98` repeats the path with and without the slash, next to `ApiKeyAllowlist.java:15` |
| Smell | SHA-256 `hash` in two places | Open (`ApiKeyAuthenticator.java:35`, `ApiKeyService.java:124`) |
| Smell | `"apikey:"` literal repeated | Open (`RateLimitFilter.java:114`, `ApiKeyAuthentication.java:59`) |
| Smell | `CreatedApiKeyResponse` copies the fields | Open |
| Smell | `countByService` has no caller | Open (`ContractCheckRepository.java:11`) |
| Smell | V007 also adds an index on `services` | Open |

New in round 2. Hard violations: none.

Judgement calls:
1. Inappropriate Intimacy / Middle Man: `ApiKeyAuthenticationFilter.java:35,53` injects `RateLimitFilter` and calls `allowBeforeKeyLookup`. Move the bucket logic to a `RateLimiter` component that both filters use.
2. Duplicated Code: the Retry-After formula at `RateLimitFilter.java:90` and `:107`; the bucket key `"rate_limit:" + scope + ":" + suffix` at `:104`, `:128`, `:133`.
3. Duplicated Code / Shotgun Surgery: the trailing-slash rule is in `ApiKeyAllowlist.requiredScope` (`:24`) and by hand in `SecurityConfig.java:97-98`.
4. Duplicated Code (minor): the label of the breaking check is written two times in `verify.ts` `verifyCiCheck`.
5. Silent truncation at 500 consumers. (Conductor note: the docs state the cap at `docs/api/contract.openapi.yaml:745` and `services/contract/CONTEXT.md:87`, so the doc part of this item is not correct.)

Worst: the filter-to-filter dependency (item 1).

### Spec

Round 1 findings:

| # | Finding | Status |
|---|---|---|
| (a)1 | `CONTEXT-MAP.md` not changed | Open, trivial |
| (a)2 | Acme plan check on k3d/prod | Accepted (T24: manual step); verify reports a failed key create (`verify.ts:275-279`) |
| (b)1 | `PlanLimits` 4-arg constructor defaults to UNLIMITED | Open (`PlanLimits.java:6-8`); production uses 5 args |
| (b)2 | Gateway strips `X-Cartogra-Api-Key` | Open, harmless |
| (c)1 | Bad keys skip the rate limit | Fixed by T21 (`ApiKeyAuthenticationFilter.java:53`, `RateLimitFilter.java:98-109`) |
| (c)2 | Trailing slash not on allowlist | Partly fixed, see (c) |

(a) Missing or partial: none in T21, T23–T26.

(b) Scope creep: none new. `@tanstack/react-table` is needed for T26.

(c) Implemented but wrong: T22, "a valid key on `POST /api/v1/contracts/check/` gets the same result as without the slash". The gateway accepts it (`ApiKeyAllowlist.java:25`, `SecurityConfig.java:97-98`) and passes the path unchanged. The contract service matches only `"/check"` (`SecurityConfig.java:50`, `ContractCheckController.java:28`), so `/check/` gets 403 for a `CI_CHECK` principal. `ApiKeyAuthIT.trailingSlashIsOnTheAllowlist` stubs `/check/` with 200 and proves only the gateway half.

Summary: Standards 0 hard, 5 judgement calls (worst: filter-to-filter dependency). Spec 1 new (T22 end to end), 2 minor round 1 items open.
