# Checklist: CI check + tenant API keys (5.5)
Plan: [plan.md](./plan.md)

## Tasks
- [ ] T1 Audit kind and error codes: test `AuditKindTest.apiKeyIsHiddenFromGuests` passes
- [ ] T2 Actor carries an API key id: test `ActorTest.apiKeyActorHasNoUserAndIsNotAdmin` passes
- [ ] T3 Internal token subject `apikey:<id>`: test `InternalTokenFilterTest.apiKeySubjectBecomesApiKeyActor` passes
- [ ] T4 `api_keys` table and plan limit: tests `RowLevelSecurityIT`, `FlywayMigrationSmokeTest` (registry) pass
- [ ] T5 Create a key: tests `ApiKeyCrudIT.create*`, `ApiKeyCrudIT.planLimitCountsActiveOnly`, `WriteAuthorizationIT`, `ApiKeyServiceTest.keyFormatAndHash` pass
- [ ] T6 List keys with status: test `ApiKeyCrudIT.listShowsStatusesWithoutSecrets` passes
- [ ] T7 Revoke a key: tests `ApiKeyCrudIT.revoke*`, `WriteAuthorizationIT` pass
- [ ] T8 Gateway key lookup: test `JdbcApiKeyRepositoryIT` passes
- [ ] T9 API key filter and allowlist: test `ApiKeyAuthIT` passes
- [ ] T10 Rate-limit bucket for each key: test `ApiKeyRateLimitIT.keyBucketDoesNotDrainTenantBucket` passes
- [ ] T11 `contract_checks` table: test `RowLevelSecurityIT` (contract) passes
- [ ] T12 Check endpoint, success path: tests `ContractCheckIT.breakingByName`, `.compatibleById`, `.neverCreatesVersion` pass
- [ ] T13 Check endpoint, failures: tests `ContractCheckIT.notFound*`, `.ambiguousName`, `.noBaseline`, `.invalidSpec`, `WriteAuthorizationIT` (contract) pass
- [ ] T14 API key types, hooks and read-only list: test `settings.api-keys.test.tsx` "viewer sees the list and no actions" passes
- [ ] T15 Create, show once, revoke: tests `settings.api-keys.test.tsx` "admin creates, sees key once, revokes", "plan limit error shown" pass
- [ ] T16 Audit label for API keys: test `audit-format.test.ts` "formats api_key events" passes
- [ ] T17 E2E API keys page: test `frontend/e2e/api-keys.spec.ts` passes
- [ ] T18 Acme verify runs the CI check: test `verify.test.ts` "breaking verify runs the CI check and revokes the key" passes
- [ ] T19 k3d suite step: step "Check 5.5 CI check" of `infra/helm/local-test.sh` passes
- [ ] T20 Docs: no doc says API keys are planned; openapi-based tests pass

## Scenarios
- [ ] S-1 Admin creates a key: integration `ApiKeyCrudIT.create*`
- [ ] S-2 Non-admin cannot write: integration `WriteAuthorizationIT` (registry)
- [ ] S-3 All roles list keys without secrets: integration `ApiKeyCrudIT.listShowsStatusesWithoutSecrets`
- [ ] S-4 Scope and name validation: integration `ApiKeyCrudIT.create*`
- [ ] S-5 Expiry in the past: integration `ApiKeyCrudIT.create*`
- [ ] S-6 Plan limit counts active keys only: integration `ApiKeyCrudIT.planLimitCountsActiveOnly`
- [ ] S-7 Revoke: integration `ApiKeyCrudIT.revoke*`
- [ ] S-8 Header ignored off the allowlist: integration `ApiKeyAuthIT`
- [ ] S-9 Allowlisted endpoint takes only keys: integration `ApiKeyAuthIT`
- [ ] S-10 Revoke works at once: integration `ApiKeyAuthIT`
- [ ] S-11 Own rate-limit bucket: integration `ApiKeyRateLimitIT`
- [ ] S-12 Last used, throttled: integration `JdbcApiKeyRepositoryIT`
- [ ] S-13 Breaking check by name: integration `ContractCheckIT.breakingByName`
- [ ] S-14 Compatible check by id: integration `ContractCheckIT.compatibleById`
- [ ] S-15 Service not found: integration `ContractCheckIT.notFound*`
- [ ] S-16 Ambiguous name: integration `ContractCheckIT.ambiguousName`
- [ ] S-17 No baseline: integration `ContractCheckIT.noBaseline`
- [ ] S-18 Invalid candidate: integration `ContractCheckIT.invalidSpec`
- [ ] S-19 API key audit hidden from guests: unit `AuditKindTest.apiKeyIsHiddenFromGuests`
- [ ] S-20 UI create, show once, revoke: e2e `api-keys.spec.ts` (admin)
- [ ] S-21 UI read-only for non-admins: e2e `api-keys.spec.ts` (viewer)

## Business rules
- [ ] BR-1: scenarios S-1, S-2, S-3, S-20, S-21 pass
- [ ] BR-2: scenarios S-1, S-3, S-20 pass
- [ ] BR-3: scenarios S-1, S-4 pass
- [ ] BR-4: scenario S-5 passes
- [ ] BR-5: scenario S-6 passes
- [ ] BR-6: scenarios S-3, S-7, S-10 pass
- [ ] BR-7: scenario S-8 passes
- [ ] BR-8: scenarios S-9, S-10 pass
- [ ] BR-9: scenario S-11 passes
- [ ] BR-10: scenario S-12 passes
- [ ] BR-11: scenarios S-13, S-14, S-15, S-16 pass
- [ ] BR-12: scenarios S-13, S-14 pass
- [ ] BR-13: scenarios S-17, S-18 pass
- [ ] BR-14: scenario S-13 passes
- [ ] BR-15: scenarios S-1, S-7, S-19 pass

## Acceptance criteria
- [ ] AC-1 Admin creates a `ci:check` key and sees it one time
- [ ] AC-2 Non-admins read-only; guest and member writes get 403
- [ ] AC-3 Plan limit counts active keys only
- [ ] AC-4 Revoked or expired key gets 401 at once
- [ ] AC-5 Header ignored off the allowlist; JWT on `/check` gets 403
- [ ] AC-6 Check returns `isBreaking`, `changes`, `affectedConsumers`, by id or name
- [ ] AC-7 One row for each completed check, no version; no row on failure
- [ ] AC-8 Key create and revoke audited, hidden from guests, no secrets
- [ ] AC-9 Own rate-limit bucket for each key
- [ ] AC-10 `pnpm acme verify --breaking` passes on k3d
- [ ] AC-11 `/code-review` and `/security-review` findings fixed or answered

## Verification
- [ ] `./gradlew build` passes
- [ ] `cd frontend && pnpm test && pnpm lint` passes
- [ ] `cd frontend && pnpm exec playwright test e2e/api-keys.spec.ts` passes (check the `:8080` proxy target first)
- [ ] `cd seed/acme-fintech && pnpm test` passes
- [ ] `infra/helm/local-test.sh --e2e` passes, the cluster is stopped, and the HTML report is opened
