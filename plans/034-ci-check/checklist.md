# Checklist: CI check + tenant API keys (5.5)
Plan: [plan.md](./plan.md)

## Tasks
- [x] T1 Audit kind and error codes: test `AuditKindTest.apiKeyIsHiddenFromGuests` passes
- [x] T2 Actor carries an API key id: test `ActorTest.apiKeyActorHasNoUserAndIsNotAdmin` passes
- [x] T3 Internal token subject `apikey:<id>`: test `InternalTokenFilterTest.apiKeySubjectBecomesApiKeyActor` passes
- [x] T4 `api_keys` table and plan limit: tests `RowLevelSecurityIT`, `FlywayMigrationSmokeTest` (registry) pass
- [x] T5 Create a key: tests `ApiKeyCrudIT.create*`, `ApiKeyCrudIT.planLimitCountsActiveOnly`, `WriteAuthorizationIT`, `ApiKeyServiceTest.keyFormatAndHash` pass
- [x] T6 List keys with status: test `ApiKeyCrudIT.listShowsStatusesWithoutSecrets` passes
- [x] T7 Revoke a key: tests `ApiKeyCrudIT.revoke*`, `WriteAuthorizationIT` pass
- [x] T8 Gateway key lookup: test `JdbcApiKeyRepositoryIT` passes
- [x] T9 API key filter and allowlist: test `ApiKeyAuthIT` passes
- [x] T10 Rate-limit bucket for each key: test `ApiKeyRateLimitIT.keyBucketDoesNotDrainTenantBucket` passes
- [x] T11 `contract_checks` table: test `RowLevelSecurityIT` (contract) passes
- [x] T12 Check endpoint, success path: tests `ContractCheckIT.breakingByName`, `.compatibleById`, `.neverCreatesVersion` pass
- [x] T13 Check endpoint, failures: tests `ContractCheckIT.notFound*`, `.ambiguousName`, `.noBaseline`, `.invalidSpec`, `WriteAuthorizationIT` (contract) pass
- [x] T14 API key types, hooks and read-only list: test `settings.api-keys.test.tsx` "viewer sees the list and no actions" passes
- [x] T15 Create, show once, revoke: tests `settings.api-keys.test.tsx` "admin creates, sees key once, revokes", "plan limit error shown" pass
- [x] T16 Audit label for API keys: test `audit-format.test.ts` "formats api_key events" passes
- [x] T17 E2E API keys page: test `frontend/e2e/api-keys.spec.ts` passes
- [x] T18 Acme verify runs the CI check: test `verify.test.ts` "breaking verify runs the CI check and revokes the key" passes
- [ ] T19 k3d suite step: step "Check 5.5 CI check" of `infra/helm/local-test.sh` passes
- [x] T20 Docs: no doc says API keys are planned; openapi-based tests pass

- [x] T21 Rate limit before the key lookup: tests `ApiKeyRateLimitIT.badKeysAreRateLimitedByIp`, `.keyBucketDoesNotDrainTenantBucket` pass
- [x] T22 Allowlist trailing slash: test `ApiKeyAuthIT.trailingSlashIsOnTheAllowlist` passes
- [x] T23 Bounded queries: tests `ApiKeyServiceTest.listClampsLimit`, `ConsumerServiceTest.consumersAreBounded` pass
- [x] T24 Verify failed key create: test `verify.test.ts` "a failed key create is one failed check" passes
- [x] T25 Contract CONTEXT intro: no doc says the CI check is planned
- [x] T26 TanStack Table for keys: tests `settings.api-keys.test.tsx`, `e2e/api-keys.spec.ts` pass
- [x] T27 Contract trailing slash: test `ContractCheckIT.trailingSlashGivesSameResult` passes
- [x] T28 One RateLimiter: `ApiKeyRateLimitIT`, `ApiKeyAuthIT`, `ApiKeyAuthenticationFilterTest` and the gateway rate-limit ITs pass, no filter injects another filter
- [x] T29 Script keys expire: test `verify.test.ts` "the verify key expires within 1 hour" passes, `sh -n infra/helm/local-test.sh` passes
- [x] T30 Create dialog cache and expiry: Vitest "the expiry is the end of the local day", "the date input has min today" pass, `e2e/api-keys.spec.ts` passes
- [x] T31 Roadmap and context map: no current doc says 5.5 is planned

## Scenarios
- [x] S-1 Admin creates a key: integration `ApiKeyCrudIT.create*`
- [x] S-2 Non-admin cannot write: integration `WriteAuthorizationIT` (registry)
- [x] S-3 All roles list keys without secrets: integration `ApiKeyCrudIT.listShowsStatusesWithoutSecrets`
- [x] S-4 Scope and name validation: integration `ApiKeyCrudIT.create*`
- [x] S-5 Expiry in the past: integration `ApiKeyCrudIT.create*`
- [x] S-6 Plan limit counts active keys only: integration `ApiKeyCrudIT.planLimitCountsActiveOnly`
- [x] S-7 Revoke: integration `ApiKeyCrudIT.revoke*`
- [x] S-8 Header ignored off the allowlist: integration `ApiKeyAuthIT`
- [x] S-9 Allowlisted endpoint takes only keys: integration `ApiKeyAuthIT`
- [x] S-10 Revoke works at once: integration `ApiKeyAuthIT`
- [x] S-11 Own rate-limit bucket: integration `ApiKeyRateLimitIT`
- [x] S-12 Last used, throttled: integration `JdbcApiKeyRepositoryIT`
- [x] S-13 Breaking check by name: integration `ContractCheckIT.breakingByName`
- [x] S-14 Compatible check by id: integration `ContractCheckIT.compatibleById`
- [x] S-15 Service not found: integration `ContractCheckIT.notFound*`
- [x] S-16 Ambiguous name: integration `ContractCheckIT.ambiguousName`
- [x] S-17 No baseline: integration `ContractCheckIT.noBaseline`
- [x] S-18 Invalid candidate: integration `ContractCheckIT.invalidSpec`
- [x] S-19 API key audit hidden from guests: unit `AuditKindTest.apiKeyIsHiddenFromGuests`
- [x] S-20 UI create, show once, revoke: e2e `api-keys.spec.ts` (admin)
- [x] S-21 UI admin-only, API read for non-admins: e2e `api-keys.spec.ts` (viewer)

## Business rules
- [x] BR-1: scenarios S-1, S-2, S-3, S-20, S-21 pass
- [x] BR-2: scenarios S-1, S-3, S-20 pass
- [x] BR-3: scenarios S-1, S-4 pass
- [x] BR-4: scenario S-5 passes
- [x] BR-5: scenario S-6 passes
- [x] BR-6: scenarios S-3, S-7, S-10 pass
- [x] BR-7: scenario S-8 passes
- [x] BR-8: scenarios S-9, S-10 pass
- [x] BR-9: scenario S-11 passes
- [x] BR-10: scenario S-12 passes
- [x] BR-11: scenarios S-13, S-14, S-15, S-16 pass
- [x] BR-12: scenarios S-13, S-14 pass
- [x] BR-13: scenarios S-17, S-18 pass
- [x] BR-14: scenario S-13 passes
- [x] BR-15: scenarios S-1, S-7, S-19 pass

## Acceptance criteria
- [x] AC-1 Admin creates a `ci:check` key and sees it one time
- [x] AC-2 Non-admins read through the API only; guest and member writes get 403
- [x] AC-3 Plan limit counts active keys only
- [x] AC-4 Revoked or expired key gets 401 at once
- [x] AC-5 Header ignored off the allowlist; JWT on `/check` gets 403
- [x] AC-6 Check returns `isBreaking`, `changes`, `affectedConsumers`, by id or name
- [x] AC-7 One row for each completed check, no version; no row on failure
- [x] AC-8 Key create and revoke audited, hidden from guests, no secrets
- [x] AC-9 Own rate-limit bucket for each key
- [ ] AC-10 `pnpm acme verify --breaking` passes on k3d
- [ ] AC-11 `/code-review` and `/security-review` findings fixed or answered

## Verification
- [x] `./gradlew build` passes
- [x] `cd frontend && pnpm test && pnpm lint` passes
- [x] `cd frontend && pnpm exec playwright test e2e/api-keys.spec.ts` passes (check the `:8080` proxy target first)
- [x] `cd seed/acme-fintech && pnpm test` passes
- [ ] `infra/helm/local-test.sh --e2e` passes, the cluster is stopped, and the HTML report is opened
