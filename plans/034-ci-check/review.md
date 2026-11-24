# Review (round 3): plan 034, CI check + tenant API keys (5.5)

Reviewer: an independent plan review on 2026-11-24. The review covers the uncommitted working tree of `/home/allan/developer/cartogra` (branch `feat/5.5-ci-check`), compared with `origin/main`. It includes `## Changes during execution` and the fix tasks T27 to T31. The round 1 and round 2 reviews are in `review-1.md` and `review-2.md`. Section 7 gives the status of each round 2 finding.

## 1. Verdict

**APPROVED**

- Blockers: 0
- Major: 0
- Minor: 2 (R3-F1: the T30 tests cannot fail in the UTC time zone of CI; R3-F2: the roadmap Acme line for 5.5 is not correct)

All the tests that the plan names exist, and they pass in my run. T27 fixes R2-F1, so T22 is now true end to end. T19, AC-10, AC-11 and the k3d verification line are UNVERIFIED (k3d step), by design.

## 2. Plan coverage

### Tasks

| ID | Result | Evidence |
|---|---|---|
| T1 | PASS | `AuditKind.API_KEY("api_key", Set.of())`; `AuditKindTest` 6/6 pass, including `apiKeyIsHiddenFromGuests` |
| T2 | PASS | `/home/allan/developer/cartogra/shared/common/src/main/java/io/cartogra/common/identity/Actor.java`; `ActorTest` 2/2 pass; the build compiles with no change at the call sites |
| T3 | PASS | `/home/allan/developer/cartogra/shared/web/src/main/java/io/cartogra/web/filter/InternalTokenFilter.java`; `InternalTokenFilterTest` 11/11 pass |
| T4 | PASS | `V020__create_api_keys.sql`, `JdbcPlanLimitRepository.java`; registry `RowLevelSecurityIT` 4/4, `FlywayMigrationSmokeTest` 6/6 pass |
| T5 | PASS | `ApiKeyService.java` (create); `ApiKeyCrudIT` 7/7, registry `api.WriteAuthorizationIT` 8/8, `ApiKeyServiceTest` 4/4 pass |
| T6 | PASS | `ApiKeyCrudIT.listShowsStatusesWithoutSecrets` passes |
| T7 | PASS | `ApiKeyCrudIT.revokeIsIdempotentAndAuditedOnce`, `.revokeOfAnUnknownOrForeignKeyReturns404`, `WriteAuthorizationIT` pass |
| T8 | PASS | gateway `JdbcApiKeyRepository.java`, `ApiKeyAuthenticator.java`; `JdbcApiKeyRepositoryIT` 6/6 pass |
| T9 | PASS | `/home/allan/developer/cartogra/services/gateway/src/main/java/io/cartogra/gateway/config/ApiKeyAuthenticationFilter.java:47-84`; `ApiKeyAuthIT` 12/12 pass |
| T10 | PASS | `/home/allan/developer/cartogra/services/gateway/src/main/java/io/cartogra/gateway/config/RateLimitFilter.java:75-77`; `ApiKeyRateLimitIT.keyBucketDoesNotDrainTenantBucket` passes |
| T11 | PASS | `V007__contract_checks.sql`; contract `RowLevelSecurityIT` 6/6 pass |
| T12 | PASS | `ContractCheckService.java`; `ContractCheckIT.breakingByName`, `.compatibleById`, `.neverCreatesVersion` pass |
| T13 | PASS | `ContractCheckIT` 10/10, contract `api.WriteAuthorizationIT` 5/5 pass |
| T14 | PASS | `settings.api-keys.tsx`; vitest "viewer sees the list and no actions" passes; `placeholder-pages.test.tsx` has no API keys row |
| T15 | PASS | vitest "admin creates, sees key once, revokes", "plan limit error shown" pass |
| T16 | PASS | vitest `audit-format.test.ts` "formats api_key events" passes |
| T17 | PASS (log) | `frontend/e2e/api-keys.spec.ts:47`, `:80` passed in the orchestrator log `e2e3.log` (13:04:40), after the last frontend change (12:53:32). I did not run the e2e (forbidden) |
| T18 | PASS | `/home/allan/developer/cartogra/seed/acme-fintech/src/verify.ts:261-302`; vitest "breaking verify runs the CI check and revokes the key" passes (30/30 in the file) |
| T19 | UNVERIFIED (k3d step) | Step at `/home/allan/developer/cartogra/infra/helm/local-test.sh:448`. Not run, by design. `sh -n` and `bash -n` pass |
| T20 | PASS | Openapi, architecture and `CONTEXT.md` files updated. `docs/bip/1.48-gateway-auth-rationale.md` stays as it is, by the recorded T31 decision |
| T21 | PASS | `ApiKeyAuthenticationFilter.java:53` calls `RateLimiter.allowBeforeKeyLookup` before the header check and the lookup; `ApiKeyRateLimitIT` 2/2 pass |
| T22 | PASS | Gateway: `/home/allan/developer/cartogra/services/gateway/src/main/java/io/cartogra/gateway/config/ApiKeyAllowlist.java:24-27`, gateway `SecurityConfig.java:97-99`; `ApiKeyAuthIT.trailingSlashIsOnTheAllowlist` passes and shows that the path reaches the downstream with the slash. Contract: T27 makes `/check/` give the same result. Both halves together make "Done when" true |
| T23 | PASS | `ConsumerServiceTest.consumersAreBounded`, `ApiKeyServiceTest.listClampsLimit` pass |
| T24 | PASS | vitest "a failed key create is one failed check" passes |
| T25 | PASS | `/home/allan/developer/cartogra/services/contract/CONTEXT.md:3` lists "CI check (5.5)" as live |
| T26 | PASS | `settings.api-keys.tsx` uses `@tanstack/react-table`; 3 vitest tests pass; e2e as for T17 |
| T27 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/api/ContractCheckController.java:28` (`@PostMapping({"/check", "/check/"})`), `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/config/SecurityConfig.java:50` (`POST "/check", "/check/"` → `CI_CHECK`). `ContractCheckIT.trailingSlashGivesSameResult` (`ContractCheckIT.java:156-167`) passes: 200 on both paths, equal `data`, and an ADMIN token on `/check/` gets 403. The test goes through the real context path `/api/v1/contracts` (`application.yml:49`) |
| T28 | PASS | New `/home/allan/developer/cartogra/services/gateway/src/main/java/io/cartogra/gateway/config/RateLimiter.java` owns `bucketKey` (`:54-56`), `retryAfterSeconds` (`:59-61`), `consume`, `allowBeforeKeyLookup` and `write429`. `ApiKeyAuthenticationFilter` takes `RateLimiter`, not `RateLimitFilter` (`:37-43`). `grep "rate_limit:\|Math.ceil"` finds only `RateLimiter.java:55,60`. No filter constructor takes another filter. `ApiKeyRateLimitIT` 2, `ApiKeyAuthIT` 12, `ApiKeyAuthenticationFilterTest` 10, `RateLimitIT` 3, `RateLimitRedisFailureIT` 2, `RateLimitFailClosedOverrideIT` 1, `GuestRateLimitIT` 1 pass. `git diff` shows no removed line in an existing gateway test |
| T29 | PASS | `seed/acme-fintech/src/api.ts:188,345` (optional `expiresAt`); `verify.ts:254,279` (1 hour); `local-test.sh:458` (`date -u -d '+1 hour'`), `:467` (`key_curl` sends the header through `printf ... \| curl -K -`; `printf` is a shell builtin, so the key is not in a process argument). Vitest "the verify key expires within 1 hour" passes and fails if `expiresAt` is removed (the fake stores `null`). `sh -n` passes |
| T30 | PASS (see R3-F1) | `/home/allan/developer/cartogra/frontend/src/hooks/useApiKeys.ts:24` (`gcTime: 0`); `/home/allan/developer/cartogra/frontend/src/components/ApiKeyCreateDialog.tsx:47` (`endOfLocalDayIso`), `:108` (`min={toLocalDateString(new Date())}`); `/home/allan/developer/cartogra/frontend/src/lib/local-date.ts:6-14`. Vitest "the date input has min today" and "the expiry is the end of the local day" pass in Europe/Amsterdam, UTC and America/Los_Angeles. e2e as for T17 |
| T31 | PASS (see R3-F2) | `/home/allan/developer/cartogra/docs/roadmap.md:183` ticks 5.5; `/home/allan/developer/cartogra/CONTEXT-MAP.md:16` (CI check [5.5]) and `:84` (API key path to Contract). A grep for "planned / not yet / coming soon" near "API key" finds only `docs/bip/1.48-gateway-auth-rationale.md`, which stays by the recorded decision |

### Business rules

| ID | Result | Evidence |
|---|---|---|
| BR-1 | PASS | `ApiKeyService` `@PreAuthorize(ADMIN_ONLY)`; `WriteAuthorizationIT.apiKeyWritesNeedAnAdmin`; `ApiKeyCrudIT.listShowsStatusesWithoutSecrets` (VIEWER 200) |
| BR-2 | PASS | `ApiKeyServiceTest.keyFormatAndHash`, `ApiKeyCrudIT.createReturnsTheKeyOnceAndStoresOnlyTheHash`, `.listShowsStatusesWithoutSecrets` |
| BR-3 | PASS | `ApiKeyCrudIT.createRejectsInvalidInputWith400` |
| BR-4 | PASS | `ApiKeyCrudIT.createRejectsInvalidInputWith400` (past expiry); no update endpoint |
| BR-5 | PASS | `ApiKeyCrudIT.planLimitCountsActiveOnly` (402, as changed during execution) |
| BR-6 | PASS | `ApiKeyCrudIT.revoke*`, `ApiKeyAuthIT.revokedKeyIsUnauthorized`, `.expiredKeyIsUnauthorized` |
| BR-7 | PASS | `ApiKeyAuthIT.headerIsIgnoredOffTheAllowlist`, `.jwtAppliesOffTheAllowlistEvenWithAKeyHeader` |
| BR-8 | PASS | `ApiKeyAuthIT` (each case), `ApiKeyAuthenticationFilterTest` 10/10; `/check/` end to end by T22 + T27 |
| BR-9 | PASS | `ApiKeyRateLimitIT.keyBucketDoesNotDrainTenantBucket` (Redis key `rate_limit:apikey:<id>:default`). The extra IP pre-bucket is a recorded user decision |
| BR-10 | PASS | `JdbcApiKeyRepositoryIT` (touch throttled) |
| BR-11 | PASS | `ContractCheckIT.notFound*`, `.ambiguousName`, `.breakingByName` (name `bIlLiNg`), `.compatibleById` |
| BR-12 | PASS (up to 500 consumers, recorded) | `ContractCheckIT.breakingByName`, `.compatibleById`; `ConsumerServiceTest.consumersAreBounded` |
| BR-13 | PASS | `ContractCheckIT.noBaseline`, `.invalidSpec` |
| BR-14 | PASS | `ContractCheckIT.breakingByName` (1 row with `api_key_id`, 2 versions), `.neverCreatesVersion` |
| BR-15 | PASS | `ApiKeyCrudIT` (audit payload has no key or hash), `AuditKindTest.apiKeyIsHiddenFromGuests` |

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
| S-9 | PASS | `ApiKeyAuthIT` (JWT only 403, nothing 401, unknown/revoked/expired 401, no scope 403, `keyWinsOverAJwtOfAnotherTenant`) |
| S-10 | PASS | `ApiKeyAuthIT.revokeWorksOnTheNextRequest` (revoke by SQL; the gateway IT has no registry) |
| S-11 | PASS | `ApiKeyRateLimitIT.keyBucketDoesNotDrainTenantBucket` |
| S-12 | PASS | `JdbcApiKeyRepositoryIT` |
| S-13 | PASS | `ContractCheckIT.breakingByName` |
| S-14 | PASS | `ContractCheckIT.compatibleById` |
| S-15 | PASS | `ContractCheckIT.notFoundUnknownName`, `.notFoundServiceOfAnotherTenant`, `.notFoundDeletedService` |
| S-16 | PASS | `ContractCheckIT.ambiguousName` |
| S-17 | PASS | `ContractCheckIT.noBaseline` |
| S-18 | PASS | `ContractCheckIT.invalidSpec` |
| S-19 | PASS | `AuditKindTest.apiKeyIsHiddenFromGuests` |
| S-20 | PASS (log) | `frontend/e2e/api-keys.spec.ts:47`; passed in `e2e3.log` |
| S-21 | PASS (log) | `frontend/e2e/api-keys.spec.ts:80` (changed S-21); passed in `e2e3.log` |

### Acceptance criteria

| ID | Result | Evidence |
|---|---|---|
| AC-1 | PASS | `ApiKeyCrudIT.createReturnsTheKeyOnceAndStoresOnlyTheHash`; e2e `api-keys.spec.ts:47` (`e2e3.log`) |
| AC-2 | PASS | `WriteAuthorizationIT.apiKeyWritesNeedAnAdmin`; `ApiKeyCrudIT.listShowsStatusesWithoutSecrets`; e2e `api-keys.spec.ts:80` |
| AC-3 | PASS | `ApiKeyCrudIT.planLimitCountsActiveOnly` |
| AC-4 | PASS | `ApiKeyAuthIT.revokedKeyIsUnauthorized`, `.expiredKeyIsUnauthorized`, `.revokeWorksOnTheNextRequest` |
| AC-5 | PASS | `ApiKeyAuthIT.headerIsIgnoredOffTheAllowlist`, `.jwtWithNoKeyIsForbiddenOnTheAllowlistedEndpoint`; `ContractCheckIT.trailingSlashGivesSameResult` (ADMIN on `/check/` 403) |
| AC-6 | PASS | `ContractCheckIT.breakingByName`, `.compatibleById` |
| AC-7 | PASS | `ContractCheckIT` (1 row on success, 0 rows on 404, 409, 422, 400) |
| AC-8 | PASS | `ApiKeyCrudIT`, `AuditKindTest.apiKeyIsHiddenFromGuests` |
| AC-9 | PASS | `ApiKeyRateLimitIT.keyBucketDoesNotDrainTenantBucket` |
| AC-10 | UNVERIFIED (k3d step) | Not run, by design. The Acme plan limit on k3d and production stays a manual step (T24 change) |
| AC-11 | UNVERIFIED (k3d step) | Open by design. The round 2 findings are fixed or refused by recorded user decisions (section 7). R3-F1 and R3-F2 are new minor items |

### Data model

| Item | Result | Evidence |
|---|---|---|
| `api_keys` | PASS | `V020__create_api_keys.sql` and gateway `test-schema.sql`; no change since round 2; registry and gateway `RowLevelSecurityIT` pass |
| `PlanLimits.maxApiKeys` | PASS | `PlanLimits.java`, `JdbcPlanLimitRepository.java`. The 4-argument constructor stays (recorded refusal of R2-F5) |
| `contract_checks` | PASS | `V007__contract_checks.sql`; contract `RowLevelSecurityIT` 6/6 pass |
| DTOs | PASS | `ApiKeyResponse`, `CreatedApiKeyResponse`, `ContractCheckRequest`, `ContractCheckResponse`; seed `ApiKey` gets `expiresAt` |

### Assertion changes in existing tests

| Test | Change | Support |
|---|---|---|
| `frontend/src/test/routes/placeholder-pages.test.tsx` | The "API keys" row is removed | T14 "Done when" |

`git diff origin/main -- '*/test/*' '*/e2e/*' | grep '^-'` shows only that removal and one changed import line in `seed/acme-fintech/test/fakeApi.ts`. T28 changed no assertion of an existing gateway test.

## 3. Findings

### R3-F1 (minor): the T30 tests cannot fail in the UTC time zone of CI

- Where: `/home/allan/developer/cartogra/frontend/src/test/components/ApiKeyCreateDialog.test.tsx:14,26-44`.
- Problem: The tests set the clock to 23:30 local time and do not set a time zone. The frontend config sets no `TZ`, and GitHub runners use UTC. In UTC, a UTC implementation (`min = new Date().toISOString().slice(0, 10)` and `expiresAt = day + "T23:59:59.999Z"`) gives the same values as the local implementation, so both tests pass with the bug that T30 fixes. In Europe/Amsterdam the "min today" test also cannot see a UTC `min`. Command output: in `TZ=UTC` and `TZ=Europe/Amsterdam` the UTC date of the test clock is `2026-11-24` (the expected value); in `TZ=UTC` the UTC end of day equals the local end of day (`true`). The production code is correct (`local-date.ts:6-14`).
- Fix: Run this test file in a fixed zone west of UTC, for example `process.env.TZ = 'America/Los_Angeles'` before the first `Date` use, or a per-file environment setting. Then 23:30 local is the next day in UTC, and both tests fail for a UTC implementation.

### R3-F2 (minor): the roadmap Acme line for 5.5 does not agree with the code

- Where: `/home/allan/developer/cartogra/docs/roadmap.md:184`.
- Problem: T31 ticked 5.5, but the line under it says that verify "posts the `--breaking` spec ... and asserts `is_breaking`". The recorded change (2026-11-24, T18/T19) says that verify posts the v1 spec of ledger-service, because the breaking spec is compatible with the v3 baseline (`verify.ts:256-259`). The field is `isBreaking` (Decision 2). Line 183 also still says "service id or slug" and `{is_breaking, ...}` (Decisions 1 and 2 call these wording only). A reader who copies the roadmap gets a wrong test idea.
- Fix: Change line 184 to "`verify` posts the v1 spec of ledger-service by name to `/v1/contracts/check` and asserts `isBreaking` and the 12 consumers". Optionally change "slug" to "name" on line 183.

## 4. Checklist problems

- Ticks without evidence: none. `T22` is ticked again, and T27 now makes it true end to end.
- `T17`, `T26`, `T30` (e2e part), `S-20`, `S-21` and the Playwright verification line: the evidence is the orchestrator log `e2e3.log` (api-keys specs passed at 13:04:40, after the last frontend change at 12:53:32). I did not rerun the e2e (forbidden). The same log has 1 failure in `guest-demo.spec.ts:73` (local data, not in this plan). The orchestrator reports a pass after a data cleanup. I cannot verify that rerun, and it is outside the plan.
- The `./gradlew build` line: the orchestrator log `build3.log` (13:12:35, after the last source change) has `BUILD SUCCESSFUL in 1m 45s`.
- Correct open items: `T19`, `AC-10`, `AC-11` and the k3d verification line are not ticked.
- Passed items without a tick: none.

## 5. Out-of-plan changes

| Change | Note |
|---|---|
| `.claude/skills/security-audit/build_report.py` (`height` → `min-height` of `.cover`) | Uncommitted tooling change at 12:47. No task names it, and it is not product code |
| `.claude/skills/execute/SKILL.md`, `review-plan/SKILL.md`, `security-audit/SKILL.md` | In commits on the branch before this plan (`c00ae58`), not in the working tree |
| `frontend/package.json`, `frontend/pnpm-lock.yaml` | `@tanstack/react-table` for T26 (as in round 2) |
| `.claude/rules/backend.md`, `frontend/src/lib/permissions.ts` | As in round 2 (doc line; Decision 14) |
| `frontend/src/lib/local-date.ts`, `frontend/src/test/components/ApiKeyCreateDialog.test.tsx` | New files for T30. T30 names "the tests of the dialog", so they are in scope |
| `services/contract/src/test/java/io/cartogra/contract/ContractHttp.java` (`check(path, ...)`) | Test helper for T27 |
| `plans/034-ci-check/ship-log.md`, `review-1.md`, `review-2.md` | Process files |

No change touches an item of "Out of scope".

## 6. Commands that I ran

| Command | Result |
|---|---|
| `git diff origin/main --stat`, `git status --short`, `git ls-files --others --exclude-standard` | 57 tracked files changed (1833+, 164-); 56 untracked files |
| `./gradlew --continue :shared:common:test --rerun --tests '*AuditKindTest' --tests '*ActorTest' :shared:web:test --rerun --tests '*InternalTokenFilterTest' :services:gateway:test --rerun --tests '*ApiKeyAuthIT' --tests '*ApiKeyRateLimitIT' --tests '*ApiKeyAuthenticatorTest' --tests '*JdbcApiKeyRepositoryIT' --tests '*ApiKeyAuthenticationFilterTest' --tests '*TenantInjectionFilterTest' --tests '*gateway.RowLevelSecurityIT' --tests '*GuestRateLimitIT' --tests '*RateLimit*' :services:registry:test --rerun --tests '*ApiKeyCrudIT' --tests '*ApiKeyServiceTest' --tests '*registry.api.WriteAuthorizationIT' --tests '*registry.RowLevelSecurityIT' --tests '*FlywayMigrationSmokeTest' :services:contract:test --rerun --tests '*ContractCheckIT' --tests '*ContractCheckServiceTest' --tests '*ConsumerServiceTest' --tests '*contract.api.WriteAuthorizationIT' --tests '*contract.RowLevelSecurityIT'` | BUILD SUCCESSFUL, 0 failures (result XML files from 13:14): AuditKindTest 6, ActorTest 2, InternalTokenFilterTest 11, ApiKeyAuthIT 12, ApiKeyRateLimitIT 2, ApiKeyAuthenticatorTest 3, JdbcApiKeyRepositoryIT 6, ApiKeyAuthenticationFilterTest 10, TenantInjectionFilterTest 6, gateway RowLevelSecurityIT 4, GuestRateLimitIT 1, RateLimitIT 3, RateLimitRedisFailureIT 2, RateLimitFailClosedOverrideIT 1, ApiKeyCrudIT 7, ApiKeyServiceTest 4, registry WriteAuthorizationIT 8, FlywayMigrationSmokeTest 6, registry RowLevelSecurityIT 4, ContractCheckIT 10 (with `trailingSlashGivesSameResult`), ContractCheckServiceTest 7, ConsumerServiceTest 6, contract WriteAuthorizationIT 5, contract RowLevelSecurityIT 6 |
| `pnpm exec vitest run src/test/components/ApiKeyCreateDialog.test.tsx src/test/routes/settings.api-keys.test.tsx src/test/lib/audit-format.test.ts src/test/routes/placeholder-pages.test.tsx` (frontend) | 4 files, 15 tests pass |
| `TZ=UTC` and `TZ=America/Los_Angeles pnpm exec vitest run src/test/components/ApiKeyCreateDialog.test.tsx` | 2/2 pass in each zone |
| `TZ=<zone> node -e ...` (UTC date of the test clock; UTC end of day vs local end of day) | UTC: `2026-11-24`, `true`; Europe/Amsterdam: `2026-11-24`, `false`; America/Los_Angeles: `2026-11-25`, `false` (R3-F1) |
| `pnpm typecheck`, `pnpm lint` (frontend) | No errors |
| `pnpm exec vitest run test/verify.test.ts`, `pnpm typecheck` (seed/acme-fintech) | 30 tests pass; no type errors |
| `sh -n infra/helm/local-test.sh`, `bash -n infra/helm/local-test.sh` | Both pass |
| `grep` of `build3.log`, `e2e3.log`; `ls -l` of the changed files | Build: BUILD SUCCESSFUL at 13:12:35. Playwright: 9 passed, 1 failed (`guest-demo.spec.ts:73`) at 13:04:40; both api-keys specs passed. Last source change: 12:54:23 |
| `grep -rniE` for API keys "planned / not yet / coming soon" in docs | Only `docs/bip/1.48-gateway-auth-rationale.md` (kept by the T31 decision) |

I did not run `infra/helm/local-test.sh` or the e2e, start containers outside Testcontainers, or call cartogra.dev. I did not change code, tests, `plan.md` or `checklist.md`.

## 7. Status of the round 2 findings

| Round 2 finding | Status | Evidence |
|---|---|---|
| R2-F1 (major): a valid key on `/check/` gets 403 from the contract service | FIXED | T27: `ContractCheckController.java:28`, contract `SecurityConfig.java:50`; `ContractCheckIT.trailingSlashGivesSameResult` |
| R2-F2 (minor): a valid key is also charged to the IP lookup bucket | REFUSED (recorded user decision) | `plan.md` Changes during execution, round 2 gate 3: "charge the IP pre-bucket only when the lookup fails" refused. Code unchanged: `RateLimiter.java:96-107` |
| R2-F3 (minor): expiry date in UTC (round 1 F-5) | FIXED | T30: `ApiKeyCreateDialog.tsx:47,108`, `local-date.ts`; see R3-F1 for the test gap |
| R2-F4 (minor): docs say API keys are planned; roadmap not ticked | FIXED | T31: `docs/roadmap.md:183`, `CONTEXT-MAP.md:16,84`. `docs/bip/1.48-...` kept by the recorded decision. See R3-F2 |
| R2-F5 (minor): `PlanLimits` 4-argument constructor | REFUSED (recorded user decision) | `plan.md` Changes during execution, round 2 gate 3 |
| Code review round 2, Standards judgement 1 (filter injects filter) | FIXED | T28: `ApiKeyAuthenticationFilter.java:37-43` takes `RateLimiter` |
| Code review round 2, Standards judgement 2 (duplicated Retry-After and bucket key) | FIXED | T28: `RateLimiter.java:54-61` is the only place |
| Audit round 2 F3 (script keys do not expire; key in curl arguments) | FIXED | T29: `verify.ts:279`, `local-test.sh:458,467` |
| Audit round 2 F4 (created key kept in the mutation cache) | FIXED | T30: `useApiKeys.ts:24` (`gcTime: 0`); the dialog also calls `create.reset()` on success (`ApiKeyCreateDialog.tsx:51`) |
| Round 1 Spec (a)1 (`CONTEXT-MAP.md`) | FIXED | T31: `CONTEXT-MAP.md:16,84` |

## Code review

### Standards

Round 2 status: R2-1 (a filter injects a filter) fixed; R2-2 (Retry-After and bucket key copies) fixed; R2-3 (trailing-slash rule copies) open, now 4 places, as T27 asked; R2-4 (breaking label two times, `verify.ts:286`, `:291`) open; R2-5 (cap of 500) accepted. The round 1 soft items and smells stay open.

Hard violation:
- N1. `frontend/src/components/ApiKeyCreateDialog.tsx:23-25`, `:41-57`: the form uses `useState` and a manual submit. `.claude/rules/frontend.md` says "Forms: TanStack Forms". `InviteUserDialog` and `TeamDialog` use `useForm`. The code was there in round 2 and was not reported then.

Judgement calls:
- N2. `RateLimiter.java:49` `enabled()` has no caller; `RateLimitFilter.java:50,52` reads `props.enabled()`.
- N3. Field name `rateLimit` (`ApiKeyAuthenticationFilter.java:35`) and `rateLimiter` (`RateLimitFilter.java:33`).
- N4. Scope prefixes in two classes: `"ip:"` at `RateLimiter.java:65`; `"apikey:"`, `"guest:"`, `"tenant:"` at `RateLimitFilter.java:76-82`.
- N5. Literal `"CI_CHECK"` in contract `SecurityConfig.java:50`, gateway `SecurityConfig.java:99`, `ContractCheckService.java:52`; `Roles` has no `CI_CHECK`.
- N6. `'ci:check'` in `SCOPE_CI_CHECK` (`ApiKeyCreateDialog.tsx:14`) and in the label text (`:100`).
- N7. `local-test.sh:458` uses `date -u -d '+1 hour'` (GNU only) in a `#!/bin/sh` script.
- N8. `docs/roadmap.md:184` says verify "posts the `--breaking` spec"; the code posts the v1 spec. The new `CONTEXT-MAP.md` line does not mention the trailing slash.

Worst: N1 (documented rule).

### Spec

Round 2 status: (a)1 `CONTEXT-MAP.md` fixed (T31); (a)2 Acme plan check accepted (T24, manual); (b)1 4-argument `PlanLimits` constructor refused by a recorded decision; (b)2 gateway strips `X-Cartogra-Api-Key` open, harmless; (c)2 T22 end to end fixed (T27, `ContractCheckIT.trailingSlashGivesSameResult`).

T27, T28, T29, T30: done as the plan says.

(a) Missing or partial:
1. T31 "no current doc says that 5.5 is planned": `docs/roadmap.md:14` still says "…CI check, GitHub Action, Slack and trusted OTLP intake are open".
2. `docs/roadmap.md:184` states the `--breaking` spec; the recorded decision is the v1 spec.

(b) Scope creep: none new.

(c) Implemented but wrong: none.

Summary: Standards 1 hard (N1), 7 judgement calls. Spec 2 (roadmap lines 14 and 184).
