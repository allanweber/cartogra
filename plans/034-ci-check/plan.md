# CI check + tenant API keys (5.5)
Issue: #135  ·  Branch: `feat/5.5-ci-check`

## Problem
A CI pipeline cannot ask Cartogra if a spec change breaks consumers. Cartogra has no machine credential: each call needs a browser JWT. The API Keys settings page is a "coming soon" stub.

## Solution
An admin creates a tenant API key with scope `ci:check` on Settings → API Keys and copies it one time. CI sends the candidate spec to `POST /api/v1/contracts/check` with `X-Cartogra-Api-Key`. The response tells if the change is breaking, which rules it hits, and which consumers it affects. Each completed check is recorded. A check never creates a version: a merge plus spec discovery (5.8) does that.

## User stories
1. As an admin, I want to create a named API key with the `ci:check` scope, so that CI can call the check.
2. As an admin, I want to see the full key only one time and copy it, so that the secret is not kept in the UI.
3. As an admin, I want to give a key an optional expiry date, so that a temporary key stops on its own.
4. As an admin, I want to revoke a key, so that a leaked key stops at once.
5. As an admin, I want a clear error when my plan has no more key slots, so that I know to revoke a key or upgrade.
6. As a viewer or member, I want to see the keys of my tenant (name, prefix, scopes, status, last used), so that I know what CI uses.
7. As an admin, I want to see when each key was last used, so that I can find stale keys.
8. As a CI pipeline, I want to send `{service, spec}` and get `isBreaking`, `changes` and `affectedConsumers`, so that I can fail the build on a breaking change.
9. As a CI pipeline, I want to name the service by its id or its name, so that the configuration is simple.
10. As a CI pipeline, I want a 404 when the service has no baseline, so that I know that the first version must come from a merge.
11. As a CI pipeline, I want a 422 with parser errors for a spec that is not valid, so that I can show the cause.
12. As a CI pipeline, I want my own rate-limit bucket, so that a burst of checks does not block the browser users, and they do not block me.
13. As a security reviewer, I want a key to reach only the endpoints of its scope, so that a leaked CI key cannot read the catalog.
14. As an auditor, I want key create and revoke in the audit timeline, so that I know who made each key.
15. As the Acme verify script, I want to create a temporary key, run the breaking check, and revoke the key, so that the k3d suite and prod verify the full flow.

## Business rules
- **BR-1**: Only ADMIN creates and revokes keys. All tenant roles (including guests) can list the keys. Guests cannot write (the existing guest write deny applies).
- **BR-2**: The full key (`cgk_` + 32 random bytes, base64url) is returned only in the create response. The database keeps the SHA-256 hex hash and a display prefix (`cgk_` + the first 8 characters of the random part). The list never returns the key or the hash.
- **BR-3**: A key has a name (1 to 100 characters, trimmed, not unique) and a non-empty set of scopes. Today the only valid scope is `ci:check`. An unknown scope or an empty set gives 400.
- **BR-4**: The expiry is optional. If it is present, it must be in the future at create time; else 400. A key cannot be edited after create.
- **BR-5**: Create is refused with `PLAN_LIMIT_EXCEEDED` when the count of **active** keys of the tenant is equal to or more than `max_api_keys`. `-1` means unlimited. Revoked and expired keys do not count. The count and the insert run under the tenant advisory lock, the same as for teams.
- **BR-6**: The status of a key is `revoked` if `revoked_at` is set, else `expired` if `expires_at <= now`, else `active`. Only an active key authenticates. Revoke sets `revoked_at` one time. A second revoke is a no-op 204 with no second audit event. An unknown key or a key of another tenant gives 404 `API_KEY_NOT_FOUND`.
- **BR-7**: The gateway reads `X-Cartogra-Api-Key` only on the endpoints of the API Key Allowlist. Today the allowlist has one entry: `POST /api/v1/contracts/check` → `ci:check`. On each other path the header is ignored: the request is handled as if the header is not there (JWT rules apply, or 401 with no JWT).
- **BR-8**: On an allowlisted endpoint, only an API key authenticates. With no key header the request gets 401, and with a JWT only it gets 403. If the key is unknown, revoked or expired, the request gets 401. If the key does not have the scope that the endpoint needs, it gets 403. The JWT is ignored when a key is present.
- **BR-9**: A request authenticated by a key uses its own rate-limit bucket `apikey:<id>`, with the rate numbers of the tenant's plan.
- **BR-10**: The gateway sets `last_used_at` on a successful key authentication, at most one time per minute for each key.
- **BR-11**: `service` in the check body is a UUID (matched against the service id) or a name (matched not case-sensitive), in the key's tenant only. An unknown service, a service of another tenant, or a deleted service gives 404 `SERVICE_NOT_FOUND`. If more than one live service has the name (projection lag after a rename), the check gives 409 `SERVICE_AMBIGUOUS`. In that case, CI must send the id.
- **BR-12**: The check compares the candidate with the Baseline (the latest stored version), with the existing `BreakingChangeEngine`. `affectedConsumers` lists all the Consumers of the service when `isBreaking` is true, and is empty when it is false.
- **BR-13**: A service with no contract or no version gives 404 `BASELINE_NOT_FOUND`. A spec that is not valid gives 422 `INVALID_SPEC` with the parser errors. A missing field gives 400. None of these writes a row.
- **BR-14**: A completed check writes exactly one `contract_checks` row and never creates a Contract Version, an audit event, or a Kafka event.
- **BR-15**: Key create and revoke publish an audit event of kind `api_key` (actor: the admin). The payload has the name, the prefix, the scopes and the expiry, and never the key or the hash. Guests never see this kind.

## Scenarios
### S-1: Admin creates a key (BR-1, BR-2, BR-3, BR-15)
```gherkin
Given an ADMIN of tenant A
When they POST /api/v1/registry/api-keys {name: "github-ci", scopes: ["ci:check"]}
Then the response is 201 with key "cgk_..." and the status "active"
And the row stores the SHA-256 of the key and the prefix, not the key
And an audit event "api_key" "created" is recorded with no key or hash in the payload
```
### S-2: Non-admin cannot write (BR-1)
```gherkin
Given a MEMBER, a VIEWER and a GUEST of tenant A
When each POSTs a key or DELETEs a key
Then each gets 403
```
### S-3: All roles list keys without secrets (BR-1, BR-2, BR-6)
```gherkin
Given tenant A has an active, an expired and a revoked key
When a VIEWER sends GET /api/v1/registry/api-keys
Then the response lists 3 keys with the statuses "active", "expired" and "revoked"
And no item has a key or a hash field
And the keys of tenant B are not in the list
```
### S-4: Scope and name validation (BR-3)
```gherkin
Given an ADMIN
When they create a key with scopes [], or ["admin"], or a blank name, or a name of 101 characters
Then each request gets 400
```
### S-5: Expiry in the past (BR-4)
```gherkin
Given an ADMIN
When they create a key with expiresAt yesterday
Then the response is 400
```
### S-6: Plan limit counts active keys only (BR-5)
```gherkin
Given a Free tenant (max 2) with 1 active key, 1 expired key and 1 revoked key
When an ADMIN creates a key
Then the response is 201
When the ADMIN creates one more key
Then the response is 409 PLAN_LIMIT_EXCEEDED
```
### S-7: Revoke (BR-6, BR-15)
```gherkin
Given an active key K
When an ADMIN sends DELETE /api/v1/registry/api-keys/K
Then the response is 204, K has the status "revoked", and one "revoked" audit event exists
When the ADMIN revokes K again
Then the response is 204 and no second audit event exists
When the ADMIN revokes an unknown id or a key of tenant B
Then the response is 404 API_KEY_NOT_FOUND
```
### S-8: Header ignored off the allowlist (BR-7)
```gherkin
Given an active ci:check key
When a client sends GET /api/v1/registry/services with only the key header
Then the response is 401
When a client sends the same request with a valid JWT and the key header
Then the JWT applies and the response is 200
```
### S-9: Allowlisted endpoint takes only keys (BR-8)
```gherkin
Given POST /api/v1/contracts/check
When the request has a valid JWT and no key
Then the response is 403
When the request has no key and no JWT
Then the response is 401
When the request has an unknown, revoked or expired key
Then the response is 401
When the request has an active key with no ci:check scope
Then the response is 403
When the request has an active ci:check key and also a JWT of tenant B
Then the request is proxied as tenant A of the key
```
### S-10: Revoke works at once (BR-6, BR-8)
```gherkin
Given an active key K that just passed a check
When an ADMIN revokes K
Then the next check with K gets 401
```
### S-11: Own rate-limit bucket (BR-9)
```gherkin
Given tenant A with a ci:check key K
When K uses all of its bucket
Then the next check with K gets 429
And a browser user of tenant A still gets 200 on GET /api/v1/registry/services
```
### S-12: Last used, throttled (BR-10)
```gherkin
Given key K with last_used_at empty
When K authenticates 2 times in 10 seconds
Then last_used_at is set one time, at the first request
```
### S-13: Breaking check by name (BR-11, BR-12, BR-14)
```gherkin
Given ledger-service has a baseline v2 and 12 consumers
When CI posts {service: "LEDGER-SERVICE", spec: <v2 minus one path, plus a required query parameter>}
Then the response is 200 with isBreaking true
And changes has path-removed and parameter-required-added
And affectedConsumers lists the 12 consumers
And one contract_checks row exists with is_breaking true and the api key id
And ledger-service still has 2 versions
```
### S-14: Compatible check by id (BR-11, BR-12)
```gherkin
Given ledger-service has a baseline and consumers
When CI posts {service: <its id>, spec: <baseline plus a new path>}
Then the response is 200 with isBreaking false and affectedConsumers empty
```
### S-15: Service not found (BR-11)
```gherkin
When CI posts a name that does not exist, the id of a service of tenant B, or the id of a deleted service
Then the response is 404 SERVICE_NOT_FOUND and no row is written
```
### S-16: Ambiguous name (BR-11)
```gherkin
Given two live projection rows named "billing" in tenant A
When CI posts {service: "billing"}
Then the response is 409 SERVICE_AMBIGUOUS and no row is written
```
### S-17: No baseline (BR-13)
```gherkin
Given a service with no contract
When CI posts a check for it
Then the response is 404 BASELINE_NOT_FOUND and no row is written
```
### S-18: Invalid candidate (BR-13)
```gherkin
When CI posts a spec that is not OpenAPI 3.x, or a body without spec
Then the response is 422 INVALID_SPEC with errors, or 400, and no row is written
```
### S-19: API key audit hidden from guests (BR-15)
```gherkin
Given an "api_key" audit event
When a GUEST reads the audit feed
Then the event is not in the feed
```
### S-20: UI create, show once, revoke (BR-1, BR-2, BR-6)
```gherkin
Given an ADMIN on Settings → API Keys
When they create "github-ci" with the scope ci:check
Then a dialog shows the full key one time with a copy button
And after the dialog closes, the list shows only the prefix
When they revoke the key and confirm
Then the row shows "revoked"
```
### S-21: UI read-only for non-admins (BR-1)
```gherkin
Given a VIEWER on Settings → API Keys
Then the list shows the keys
And there is no create button and no revoke button
```

## Data model
### Registry: `api_keys` (migration `V020__create_api_keys.sql`)
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | `gen_random_uuid()` |
| `tenant_id` | UUID NOT NULL | FK `tenants(id)` |
| `name` | TEXT NOT NULL | `CHECK (char_length(name) BETWEEN 1 AND 100)` |
| `key_hash` | CHAR(64) NOT NULL UNIQUE | SHA-256 hex of the full key |
| `key_prefix` | TEXT NOT NULL | `cgk_` + 8 characters, display only |
| `scopes` | TEXT[] NOT NULL | `CHECK (cardinality(scopes) > 0)`; values checked in code |
| `expires_at` | TIMESTAMPTZ | null = no expiry |
| `revoked_at` | TIMESTAMPTZ | set one time |
| `last_used_at` | TIMESTAMPTZ | written by the gateway |
| `created_by` | UUID NOT NULL | FK `users(id)`; not returned by the API |
| `created_at` / `updated_at` | TIMESTAMPTZ NOT NULL | |
| `deleted_at` | TIMESTAMPTZ | soft delete, not used by this story |

Indexes: `(tenant_id) WHERE revoked_at IS NULL AND deleted_at IS NULL`. The UNIQUE on `key_hash` serves the gateway lookup. RLS: `ENABLE` + `FORCE` + the `tenant_isolation` policy expression of V019 (`app.current_tenant_id` or `app.rls_bypass`). V019's loop ran one time, so V020 states the policy itself. The default privileges of V019 give the grants to `cartogra_app`. Existing data: no change.

Gateway ITs use `services/gateway/src/test/resources/db/test-schema.sql`, not the registry migrations. Add the same table there.

`PlanLimits` (registry) gets `maxApiKeys` from the existing `billing_plans.max_api_keys`. No schema change.

### Contract: `contract_checks` (migration `V007__contract_checks.sql`)
| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL | |
| `service_id` | UUID NOT NULL | |
| `contract_id` | UUID NOT NULL | FK `api_contracts(id)` |
| `baseline_version_id` | UUID NOT NULL | FK `contract_versions(id)` |
| `candidate_hash` | CHAR(64) NOT NULL | `spec_hash` of the canonical candidate |
| `is_breaking` | BOOLEAN NOT NULL | |
| `changes` | JSONB NOT NULL | the engine's `changes[]` |
| `affected_consumer_ids` | UUID[] NOT NULL | empty when compatible |
| `api_key_id` | UUID NOT NULL | the key that ran the check (no FK: other schema) |
| `created_at` | TIMESTAMPTZ NOT NULL | |

Index: `(tenant_id, service_id, created_at DESC)`. RLS: `ENABLE` + `FORCE` + the V003 policy expression. The same migration adds `CREATE INDEX ON services (tenant_id, lower(name)) WHERE deleted_at IS NULL` for the name lookup. There is no retention in this story.

## Decisions
1. **Service reference**: a UUID or a name, not case-sensitive. The roadmap says "slug", but services have no slug.
2. **Response names are camelCase** (`isBreaking`, `changes`, `affectedConsumers`, `baselineVersion`), as all contract responses are (for example `compatibility.isBreaking`). The roadmap's `is_breaking` is a wording only.
3. **Registry owns the key endpoints**: `GET/POST /api/v1/registry/api-keys` and `DELETE /api/v1/registry/api-keys/{id}`. The list returns `PageResult` of `{id, name, prefix, scopes, status, expiresAt, lastUsedAt, createdAt}`. Create returns the same fields plus `key`. Plan-limit and advisory-lock code already lives in registry (`TeamService`).
4. **The gateway reads `api_keys` directly** (it already shares the registry schema). It looks up by hash in `TenantDb.asSystem`, because the tenant is not known before the lookup. It writes only `last_used_at`. There is no cache, so a revoke works on the next request.
5. **API Key Allowlist** is a constant map in the gateway: `POST /api/v1/contracts/check` → `ci:check`. 5.9 adds `spans:write`.
6. **The internal token for a key** has the subject `apikey:<keyId>`, the tenant of the key, and one role for each scope (`ci:check` → `CI_CHECK`). There is no ADMIN/MEMBER role, so the services refuse each other write.
7. **`Actor` gets a nullable `apiKeyId`** with `Actor.apiKey(UUID)`. A 2-argument constructor stays for the existing calls. `InternalTokenFilter` maps the subject `apikey:<uuid>` to it. The contract controller takes `@AuthenticationPrincipal Actor` (actor rule: SecurityContextHolder only in filters).
8. **Contract security**: `POST /check` needs `hasRole('CI_CHECK')`. All other rules stay. A JWT never gets `CI_CHECK`, and the gateway also refuses it.
9. **Key format**: `cgk_` + 32 bytes from `SecureRandom`, base64url with no padding. The key is hashed with SHA-256 hex, not bcrypt: the key has high entropy and needs an indexed lookup.
10. **Rate limit**: `RateLimitFilter` uses the bucket `apikey:<id>` with the tenant plan numbers.
11. **`last_used_at`**: `UPDATE ... SET last_used_at = now() WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')`.
12. **Audit**: a new `AuditKind.API_KEY("api_key", Set.of())`, so it is hidden from guests. The frontend shows it in the "access" group as "API key".
13. **New error codes** in `ErrorCodes`: `API_KEY_NOT_FOUND`, `SERVICE_NOT_FOUND` (if it is not there yet), `SERVICE_AMBIGUOUS`, `BASELINE_NOT_FOUND`.
14. **UI**: the page replaces the stub and drops `underDevelopment`. It has a table, a create dialog (name, scope checkboxes with only `ci:check`, optional date), a show-once dialog with a copy button, and a revoke confirm. Buttons show for ADMIN only (`permissions.ts`).
15. **Acme verify `--breaking`**: it logs in as the admin, creates a temporary `ci:check` key, posts the breaking spec of ledger-service by name, and checks `isBreaking` and the consumer names. It revokes the key in a `finally` block.

## Test seams
- **Registry**: Testcontainers IT over HTTP with internal tokens, as in `TeamCrudIT` and `api/WriteAuthorizationIT`.
- **Gateway**: `AbstractGatewayIT` with `test-schema.sql`, Redis and a stub downstream, as in `RegistryCircuitBreakerIT` and `GuestRateLimitIT`. The test asserts the status codes and the internal token that the stub receives.
- **Contract**: `AbstractContractIT` plus `ContractHttp`, with a signed internal token that has the role `CI_CHECK`. Seed a contract, versions, `services` rows and `inbound_edges` rows.
- **shared/common, shared/web**: plain unit tests.
- **Frontend**: Vitest with mocked `api.ts`. Playwright in `frontend/e2e/api-keys.spec.ts`.
- **Acme**: `seed/acme-fintech/test/verify.test.ts` with the fake API.
- **k3d**: a new step in `infra/helm/local-test.sh`.

## Tasks
### T1: Audit kind and error codes
- Implements: BR-15, BR-6, BR-11, BR-13 (codes)
- Module: `shared/common`
- Files: `shared/common/src/main/java/io/cartogra/common/audit/AuditKind.java`, `shared/common/src/main/java/io/cartogra/common/api/ErrorCodes.java`, `shared/common/src/test/java/io/cartogra/common/audit/AuditKindTest.java`
- Depends on: none
- Change: add `API_KEY("api_key", Set.of())`; add the codes of Decision 13.
- Test: unit `AuditKindTest.apiKeyIsHiddenFromGuests`
- Done when: `api_key` is not in `guestVisibleEntityTypes()`.

### T2: Actor carries an API key id
- Implements: BR-14 (key id on the row)
- Module: `shared/common`
- Files: `shared/common/src/main/java/io/cartogra/common/identity/Actor.java`, `shared/common/src/test/java/io/cartogra/common/identity/ActorTest.java`
- Depends on: none
- Change: add the nullable component `apiKeyId`, a 2-argument constructor, and `Actor.apiKey(UUID)` (not admin, no user id).
- Test: unit `ActorTest.apiKeyActorHasNoUserAndIsNotAdmin`
- Done when: all modules compile with no change at the call sites.

### T3: Internal token subject `apikey:<id>`
- Implements: BR-8, BR-14
- Module: `shared/web`
- Files: `shared/web/src/main/java/io/cartogra/web/filter/InternalTokenFilter.java`, `shared/web/src/test/java/io/cartogra/web/filter/InternalTokenFilterTest.java`
- Depends on: T2
- Change: a subject `apikey:<uuid>` gives `Actor.apiKey(uuid)`. A user UUID subject is handled as before.
- Test: unit `InternalTokenFilterTest.apiKeySubjectBecomesApiKeyActor`
- Done when: the old subjects give the same actors as before.

### T4: `api_keys` table and plan limit
- Implements: BR-5 (limit read), data model
- Module: `services/registry`
- Files: `services/registry/src/main/resources/db/migration/V020__create_api_keys.sql`, `services/registry/src/main/java/io/cartogra/registry/domain/PlanLimits.java`, `services/registry/src/main/java/io/cartogra/registry/infrastructure/jdbc/JdbcPlanLimitRepository.java`, `services/registry/src/test/java/io/cartogra/registry/RowLevelSecurityIT.java`
- Depends on: none
- Change: add the migration of the data model; add `maxApiKeys` to `PlanLimits` and its query.
- Test: integration `RowLevelSecurityIT` (includes `api_keys`), `FlywayMigrationSmokeTest`
- Done when: tenant B cannot read the `api_keys` rows of tenant A.

### T5: Create a key
- Implements: BR-1, BR-2, BR-3, BR-4, BR-5, BR-15; S-1, S-2, S-4, S-5, S-6
- Module: `services/registry`
- Files: `services/registry/src/main/java/io/cartogra/registry/domain/ApiKey.java`, `.../domain/ApiKeyService.java`, `.../domain/ApiKeyScope.java`, `.../repository/ApiKeyRepository.java`, `.../infrastructure/jdbc/JdbcApiKeyRepository.java`, `.../api/ApiKeyController.java`, `.../api/dto/ApiKeyRequest.java`, `.../api/dto/ApiKeyResponse.java`, `.../api/dto/CreatedApiKeyResponse.java`, `.../api/GlobalExceptionHandler.java`, `services/registry/src/test/java/io/cartogra/registry/ApiKeyCrudIT.java`, `services/registry/src/test/java/io/cartogra/registry/api/WriteAuthorizationIT.java`, `services/registry/src/test/java/io/cartogra/registry/domain/ApiKeyServiceTest.java`
- Depends on: T1, T4
- Change: `POST /api-keys` with `@PreAuthorize(Roles.ADMIN_ONLY)`. Generate and hash the key (Decision 9). Check the active count under the advisory lock. Write an audit event `created`.
- Test: integration `ApiKeyCrudIT.create*`, `ApiKeyCrudIT.planLimitCountsActiveOnly`, `WriteAuthorizationIT` (guest and member get 403); unit `ApiKeyServiceTest.keyFormatAndHash`
- Done when: S-1, S-2 (create), S-4, S-5 and S-6 pass.

### T6: List keys with status
- Implements: BR-1, BR-2, BR-6; S-3
- Module: `services/registry`
- Files: `.../api/ApiKeyController.java`, `.../domain/ApiKeyService.java`, `.../infrastructure/jdbc/JdbcApiKeyRepository.java`, `services/registry/src/test/java/io/cartogra/registry/ApiKeyCrudIT.java`
- Depends on: T5
- Change: `GET /api-keys` (paginated `PageResult`, newest first) for all roles. Derive the status from the timestamps.
- Test: integration `ApiKeyCrudIT.listShowsStatusesWithoutSecrets`
- Done when: S-3 passes.

### T7: Revoke a key
- Implements: BR-1, BR-6, BR-15; S-2, S-7
- Module: `services/registry`
- Files: `.../api/ApiKeyController.java`, `.../domain/ApiKeyService.java`, `.../infrastructure/jdbc/JdbcApiKeyRepository.java`, `.../domain/exception/ApiKeyNotFoundException.java`, `.../api/GlobalExceptionHandler.java`, `services/registry/src/test/java/io/cartogra/registry/ApiKeyCrudIT.java`, `services/registry/src/test/java/io/cartogra/registry/api/WriteAuthorizationIT.java`
- Depends on: T5
- Change: `DELETE /api-keys/{id}` (ADMIN), idempotent. Write an audit event `revoked` the first time only.
- Test: integration `ApiKeyCrudIT.revoke*`, `WriteAuthorizationIT` (delete)
- Done when: S-7 and S-2 (revoke) pass.

### T8: Gateway key lookup
- Implements: BR-6, BR-10; S-12
- Module: `services/gateway`
- Files: `services/gateway/src/main/java/io/cartogra/gateway/domain/ApiKeyAuthenticator.java`, `.../repository/ApiKeyRepository.java`, `.../infrastructure/jdbc/JdbcApiKeyRepository.java`, `services/gateway/src/test/resources/db/test-schema.sql`, `services/gateway/src/test/java/io/cartogra/gateway/infrastructure/jdbc/JdbcApiKeyRepositoryIT.java`
- Depends on: T4
- Change: hash the raw key, find the active key by hash in `TenantDb.asSystem`, and touch `last_used_at` (Decision 11). Return the tenant, the key id and the scopes.
- Test: integration `JdbcApiKeyRepositoryIT` (active found; revoked and expired not found; touch throttled)
- Done when: S-12 passes.

### T9: API key filter and allowlist
- Implements: BR-7, BR-8; S-8, S-9, S-10
- Module: `services/gateway`
- Files: `.../config/ApiKeyAuthenticationFilter.java`, `.../config/ApiKeyAllowlist.java`, `.../infrastructure/security/ApiKeyAuthentication.java`, `.../config/SecurityConfig.java`, `.../config/SecurityFilterRegistrationConfig.java`, `.../config/TenantInjectionFilter.java`, `services/gateway/src/test/java/io/cartogra/gateway/api/ApiKeyAuthIT.java`
- Depends on: T8
- Change: the filter runs after `JwtAuthenticationFilter`. On an allowlisted endpoint it replaces each authentication with the key authentication, or with none. It denies a missing scope with 403, and a JWT with no key with 403. `TenantInjectionFilter` signs the internal token for an `ApiKeyAuthentication` (Decision 6). Off the allowlist, the filter does nothing.
- Test: integration `ApiKeyAuthIT` (each case of S-8, S-9 and S-10; the stub downstream receives the subject `apikey:<id>` and the role `CI_CHECK`)
- Done when: S-8, S-9 and S-10 pass.

### T10: Rate-limit bucket for each key
- Implements: BR-9; S-11
- Module: `services/gateway`
- Files: `.../config/RateLimitFilter.java`, `services/gateway/src/test/java/io/cartogra/gateway/api/ApiKeyRateLimitIT.java`
- Depends on: T9
- Change: `resolveBucketScope` returns `apikey:<id>` for an `ApiKeyAuthentication`, with the tenant plan numbers.
- Test: integration `ApiKeyRateLimitIT.keyBucketDoesNotDrainTenantBucket`
- Done when: S-11 passes.

### T11: `contract_checks` table
- Implements: BR-14 (storage), data model
- Module: `services/contract`
- Files: `services/contract/src/main/resources/db/migration/V007__contract_checks.sql`, `.../domain/ContractCheck.java`, `.../repository/ContractCheckRepository.java`, `.../infrastructure/jdbc/JdbcContractCheckRepository.java`, `services/contract/src/test/java/io/cartogra/contract/RowLevelSecurityIT.java`
- Depends on: none
- Change: add the migration and the repository (insert, count by service for the tests).
- Test: integration `RowLevelSecurityIT` (includes `contract_checks`)
- Done when: tenant isolation holds for the new table.

### T12: Check endpoint, success path
- Implements: BR-11, BR-12, BR-14; S-13, S-14
- Module: `services/contract`
- Files: `.../domain/ContractCheckService.java`, `.../domain/CheckResult.java`, `.../repository/ServiceProjectionRepository.java`, `.../infrastructure/jdbc/JdbcServiceProjectionRepository.java`, `.../api/ContractCheckController.java`, `.../api/dto/ContractCheckRequest.java`, `.../api/dto/ContractCheckResponse.java`, `.../config/SecurityConfig.java`, `services/contract/src/test/java/io/cartogra/contract/api/ContractCheckIT.java`
- Depends on: T2, T3, T11
- Change: `POST /check` with role `CI_CHECK`. Resolve the service (id, or name not case-sensitive). Parse and canonicalize the candidate with `SpecParser`. Run `BreakingChangeEngine.compare(baseline, candidate)`. Add all the consumers (from `ConsumerService`) when breaking. Insert the row with `actor.apiKeyId()`.
- Test: integration `ContractCheckIT.breakingByName`, `ContractCheckIT.compatibleById`, `ContractCheckIT.neverCreatesVersion`
- Done when: S-13 and S-14 pass.

### T13: Check endpoint, failures
- Implements: BR-11, BR-13; S-15, S-16, S-17, S-18
- Module: `services/contract`
- Files: `.../domain/ContractCheckService.java`, `.../domain/exception/BaselineNotFoundException.java`, `.../domain/exception/ServiceAmbiguousException.java`, `.../api/GlobalExceptionHandler.java`, `services/contract/src/test/java/io/cartogra/contract/api/ContractCheckIT.java`, `services/contract/src/test/java/io/cartogra/contract/api/WriteAuthorizationIT.java`
- Depends on: T12
- Change: map unknown, deleted and cross-tenant services to 404 `SERVICE_NOT_FOUND`; 2 or more live name matches to 409; no contract or version to 404 `BASELINE_NOT_FOUND`; parse errors to 422. No row in each case. Add `/check` to `WriteAuthorizationIT`: a JWT role (ADMIN) gets 403.
- Test: integration `ContractCheckIT.notFound*`, `ContractCheckIT.ambiguousName`, `ContractCheckIT.noBaseline`, `ContractCheckIT.invalidSpec`, `WriteAuthorizationIT`
- Done when: S-15 to S-18 pass and the row count stays at 0.

### T14: API key types, hooks and read-only list
- Implements: BR-1, BR-6; S-21
- Module: `frontend`
- Files: `frontend/src/lib/registry-types.ts`, `frontend/src/hooks/useApiKeys.ts`, `frontend/src/routes/_authenticated/settings.api-keys.tsx`, `frontend/src/test/routes/settings.api-keys.test.tsx`, `frontend/src/test/routes/placeholder-pages.test.tsx`
- Depends on: none (the API shape is fixed in Decision 3)
- Change: replace the stub with a table (name, prefix, scopes, status badge, expiry, last used, created). Remove `underDevelopment`. Show no buttons for non-admins.
- Test: unit (Vitest) `settings.api-keys.test.tsx` "viewer sees the list and no actions"
- Done when: the placeholder test no longer lists API Keys, and the new test passes.

### T15: Create, show once, revoke
- Implements: BR-1, BR-2, BR-3, BR-4, BR-5, BR-6; S-20
- Module: `frontend`
- Files: `frontend/src/routes/_authenticated/settings.api-keys.tsx`, `frontend/src/components/ApiKeyCreateDialog.tsx`, `frontend/src/components/ApiKeyRevealDialog.tsx`, `frontend/src/hooks/useApiKeys.ts`, `frontend/src/test/routes/settings.api-keys.test.tsx`
- Depends on: T14
- Change: an admin-only create dialog (name, `ci:check` checkbox, optional date). A reveal dialog with copy, which the user closes to clear the key from state. A revoke confirm. Show the plan-limit error text from the envelope.
- Test: unit (Vitest) "admin creates, sees key once, revokes", "plan limit error shown"
- Done when: the tests pass.

### T16: Audit label for API keys
- Implements: BR-15
- Module: `frontend`
- Files: `frontend/src/lib/audit-format.ts`, `frontend/src/lib/audit-types.ts`, `frontend/src/test/lib/audit-format.test.ts`
- Depends on: none
- Change: add `api_key` to the "access" group with the label "API key".
- Test: unit (Vitest) `audit-format.test.ts` "formats api_key events"
- Done when: the test passes.

### T17: E2E API keys page
- Implements: S-20, S-21
- Module: `frontend`
- Files: `frontend/e2e/api-keys.spec.ts`
- Depends on: T5, T6, T7, T15
- Change: spec 1: the admin creates a key, sees the key one time, closes the dialog, sees the prefix, and revokes the key. Spec 2: a VIEWER sees the list with no actions. The spec makes the viewer through the admin invite API, because no viewer fixture exists.
- Test: e2e `api-keys.spec.ts`
- Done when: both specs pass against the local stack.

### T18: Acme verify runs the CI check
- Implements: S-13 (end to end), BR-6
- Module: `seed/acme-fintech`
- Files: `seed/acme-fintech/src/api.ts`, `seed/acme-fintech/src/verify.ts`, `seed/acme-fintech/test/verify.test.ts`
- Depends on: T7, T9, T12
- Change: add `createApiKey`, `revokeApiKey` and `checkContract` (the last one sends `X-Cartogra-Api-Key` and no JWT). With `--breaking`, verify creates a key, checks ledger-service by name with the breaking spec, asserts `isBreaking` and the 12 consumer names, and revokes the key in `finally`.
- Test: unit `verify.test.ts` "breaking verify runs the CI check and revokes the key"
- Done when: the test passes and the key is revoked when the check fails.

### T19: k3d suite step
- Implements: S-9, S-10, S-13
- Module: `infra/helm`
- Files: `infra/helm/local-test.sh`
- Depends on: T18
- Change: a step "Check 5.5 CI check". As the acme admin with curl: create a key; assert a JWT-only check gets 403; assert that the key on `GET /api/v1/registry/services` gets 401; run the breaking check and assert `isBreaking`; revoke the key and assert 401. Each assert has pass/fail in the report.
- Test: `infra/helm/local-test.sh` step passes
- Done when: the HTML report shows the step green.

### T20: Docs
- Implements: documentation of BR-1 to BR-15
- Module: `docs`
- Files: `docs/api/registry.openapi.yaml`, `docs/api/contract.openapi.yaml`, `docs/api/gateway.openapi.yaml`, `docs/architecture/data-model.md`, `docs/architecture/system-overview.md`, `services/registry/CONTEXT.md` (API and DB sections), `services/contract/CONTEXT.md` (API and tables), `services/gateway/CONTEXT.md` (rate-limit row), `CONTEXT-MAP.md`
- Depends on: T7, T10, T13
- Change: document the key endpoints, `/check`, the allowlist, the `api_keys` and `contract_checks` tables, and the per-key bucket. Remove the "planned" notes for 5.5.
- Test: none (docs); `./gradlew` contract tests that read the openapi files still pass.
- Done when: no doc says that API keys are planned.

## Acceptance criteria
- [ ] AC-1: An admin creates a `ci:check` key on Settings → API Keys and sees the full key exactly one time.
- [ ] AC-2: Non-admins see the key list in read-only mode. Guests and members get 403 on writes.
- [ ] AC-3: The plan limit counts only active keys.
- [ ] AC-4: A revoked or expired key gets 401 on the next request.
- [ ] AC-5: The key header is ignored on each path that is not on the allowlist. A JWT on `/contracts/check` gets 403.
- [ ] AC-6: `POST /api/v1/contracts/check` returns `isBreaking`, `changes` and `affectedConsumers` (empty when compatible), by id or by name.
- [ ] AC-7: A completed check writes one `contract_checks` row and no version. Failed checks (404, 409, 422, 400) write no row.
- [ ] AC-8: Key create and revoke are in the audit feed for members and are hidden from guests. No payload has the key or the hash.
- [ ] AC-9: A key has its own rate-limit bucket.
- [ ] AC-10: `pnpm acme verify --breaking` passes with the CI check, on k3d.
- [ ] AC-11: `/code-review` and `/security-review` findings are fixed or answered.

## Out of scope
- The GitHub Action (5.6), Slack notifications (5.7), and the `spans:write` scope and OTLP intake (5.9).
- A UI to run a check or to see the check history.
- Key rotation, editing a key, and retention of `contract_checks`.
- Endpoint-level consumer matching (6.6) and the deployed version (6.8).
- A service slug.

## Notes
- Plan 029 (internal tokens) is merged. `TenantInjectionFilter` signs `X-Internal-Token` for each audience, and the services trust only that token.
- The gateway shares the registry schema and has no Flyway. Its ITs use `test-schema.sql`, so T8 must add `api_keys` there.
- In the contract projection, `services.name` can be null for old tombstone rows. Name matching skips null names.
- `SpecParser` and `ContractService` already canonicalize and hash specs. Reuse them; do not parse a second time.
- The Acme tenant plan must allow 1 more active key while verify runs. Check the plan of the acme tenant on k3d and prod before T18 is complete.
- An ADR for "API keys: allowlist in the gateway, SHA-256, owned by registry" is offered, not planned.
