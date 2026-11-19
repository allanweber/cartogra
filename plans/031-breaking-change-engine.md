# 5.3 — Breaking-change engine
Issue: #133  ·  Branch: `feat/5.3-breaking-change-engine`

## Context
5.1 stores contracts and immutable versions. 5.2 shows a structural diff between versions (`SpecDiffer`, a blind tree walk with RFC 6901 pointers). Nothing tells a user whether a version breaks consumers. 5.3 adds a direction-aware rule engine. Its result is binary (`breaking` | `compatible`) and it gives a list of classified changes. The list page gets a status badge and a filter. 5.5 (CI check) and 5.7 (Slack) will reuse the engine and its `changes[]` shape.

Constraints found:
- `spec_canonical` is TEXT (sorted compact JSON). `SpecParser` does not resolve `$ref` (`setResolve(false)`), so the engine must resolve local `#/...` refs itself.
- A shared component schema can be used in a request and in a response. Direction is therefore known only at the use site, not from a pointer.
- `listLive` (`infrastructure/jdbc/JdbcContractRepository.java:128`) uses a lateral join to the latest version. `countLive` (`LIVE_FROM`, `:47`) has no such join, so the status filter must add it to both queries.
- The Acme seed specs are bare `200: OK` operations with no schemas. Only `ledger-service` has v2, which adds a `/{id}/history` path.

## Decisions
| # | Decision | Why |
|---|---|---|
| 1 | Store only `contract_versions.is_breaking BOOLEAN NULL` (V004). Set it in `ContractService.publishVersion` (and in create for v1) against the previous latest version, in the same transaction. v1 is compared with an empty spec and stores `false`. NULL means "unchecked" (a version from before 5.3). | The list filter needs SQL. Classified changes are not stored: the diff endpoint computes them live, so a copy would duplicate it. |
| 2 | No backfill. A version from before 5.3 stays NULL. It has no badge and both filters exclude it. The diff endpoint always computes `compatibility` live, so the detail page is correct for old versions too. | A backfill needs a cross-tenant job under FORCE RLS. The prod gap is one demo contract. |
| 3 | A new `domain/BreakingChangeEngine` (`@Component`), not built on `SpecDiffer`. It compares old and new specs operation by operation. Parameters and the requestBody have the **request** direction, responses have the **response** direction. It resolves local `$ref` at each use site with a cycle guard. Refs it cannot resolve and external refs are compared by their ref string. A change in a shared schema is reported once for each affected operation and direction. | The pointer level cannot know the direction of a shared component. |
| 4 | Schema walk: nested `properties`, array `items` (location `.items[]`), and `allOf` merged into one object (union of properties and required). `oneOf`/`anyOf` are not walked. A change of type to or from a composition counts as a type change. Path templates match with the parameter names normalized (`/a/{id}` == `/a/{accountId}`). A parameter is keyed by `(name, in)`. | It covers real nested specs without the ambiguity of branch matching. |
| 5 | Rules. Each change has `breaking: true|false`. **Breaking:** `path-removed`, `operation-removed`, `response-field-removed`, `request-field-type-changed`, `response-field-type-changed`, `parameter-type-changed`, `request-field-required-added` (a new required field, or optional → required), `parameter-required-added` (likewise), `request-enum-value-removed`, `parameter-enum-value-removed`, `response-enum-value-added`. **Compatible mirrors:** `path-added`, `operation-added`, `response-field-added`, `request-field-optional-added`, `parameter-optional-added`, `request-field-removed`, `request-field-required-removed`, `parameter-required-removed`, `request-enum-value-added`, `parameter-enum-value-added`, `response-enum-value-removed`. If a version has no rule hits (for example, only description edits), the result is compatible with an empty `changes[]`. | A compatible badge comes with a reason. The rule set has limits and has golden tests. |
| 6 | API (camelCase, like the existing API): `ContractVersion.breaking: boolean|null` appears in the list `latestVersion`, the versions timeline, and the diff `from`/`to`. The diff response gains `compatibility: {isBreaking, changes[{rule, breaking, direction (request\|response\|operation), method, path, location, message}]}`. `GET /contracts?status=breaking|compatible` filters on the latest version's `is_breaking`. A bad value gives 400 `VALIDATION_ERROR`. `info.version` changes to 0.4.0. | The roadmap's `is_breaking` + `changes[]` concept, in the API's own naming. |
| 7 | UI: a `CompatibilityBadge` (Breaking = `text-critical` + icon, like `SpofBadge`; Compatible = muted; unchecked = nothing). The list gets a Status column and a `ToggleGroup` All/Breaking/Compatible (from `risks.tsx`) in the URL search (`status`, which resets the page). The timeline shows a badge on each version. The detail page shows a "Compatibility" section above the raw change list, breaking changes first. | Matches the existing severity badge and filter patterns. |
| 8 | Acme: `load --breaking` rewrites ledger-service's `openapi.yaml`. It removes one existing v1 path and adds a required query parameter to another GET, then publishes it as v3. `verify --breaking` asserts that v3 is breaking with `path-removed` and `parameter-required-added`, that v2 is compatible (`path-added`), and that `?status=breaking` lists ledger. The default runs (the bootstrap Job, prod) do not pass the flag. | It is the only end-to-end engine test in 5.3. In 5.5, the publish changes to a `/check` call. |
| 9 | No Kafka changes, no new topic, no change to the audit payload. | No consumer needs them (roadmap rule 4). |

## Implementation steps
**Backend: `services/contract`**
1. `src/main/resources/db/migration/V004__version_breaking.sql`: `ALTER TABLE contract_versions ADD COLUMN is_breaking BOOLEAN`. No index (the filter is on the latest row of each contract, which the lateral join reaches by the existing unique `(contract_id, version_number)`).
2. `domain/Compatibility.java` (`record Compatibility(boolean isBreaking, List<CompatibilityChange> changes)`) and `domain/CompatibilityChange.java` (`rule, breaking, direction enum, method, path, location, message`). The rule ids are an enum `CompatibilityRule` with a fixed `breaking` flag.
3. `domain/BreakingChangeEngine.java`: `Compatibility compare(@Nullable JsonNode from, JsonNode to)`. It has these internal helpers (private methods or a small package-private `SchemaResolver` class):
   - Path normalization.
   - Matching of operations by method.
   - Matching of parameters by `(name, in)` (path-level and operation-level parameters merged, operation-level wins).
   - Comparison of the requestBody content by media type and of responses by status code and media type.
   - A recursive schema comparison with the direction, a `$ref` resolver (`#/components/...`, JSON pointer unescape, a visited set), and an `allOf` merge.
   - Parse the canonical text with the same Jackson 3 `JsonMapper` that `SpecDiffer` uses.
4. `domain/VersionHeader` (+ `ContractVersion` if it is separate) gets `@Nullable Boolean breaking`. `VersionDiff` gets `Compatibility compatibility`.
5. `repository/ContractVersionRepository.insert` takes `isBreaking`. The Jdbc impl reads and writes the column in all the version selects. `JdbcContractRepository.listLive`/`countLive` get `@Nullable Boolean breaking`. Both queries use the lateral latest-version join, and the filter adds `AND v.is_breaking = :breaking`.
6. `ContractService`:
   - `create` and `publishVersion` call `engine.compare(previousCanonical | null, newCanonical)` and store `isBreaking`. A spec with the same hash stores nothing, as before.
   - `diff` adds `engine.compare(from, to)`.
   - `list` takes `@Nullable Boolean breaking`.
7. `api/ContractController.list`: `@RequestParam(required=false) String status` maps `breaking` → true and `compatible` → false. Another value throws the existing validation exception (400 `VALIDATION_ERROR`). Update `api/dto/ContractVersionResponse`, `ContractSummaryResponse`, and `VersionDiffResponse` (nested `Compatibility`/`Change`, lowercase enums like the existing `op`), each with `static from`.
8. `docs/api/contract.openapi.yaml`: add the `status` parameter, `ContractVersion.breaking` (boolean|null), and the `Compatibility` + `CompatibilityChange` schemas (with a `rule` enum), and set `info.version` to `0.4.0`.
9. `services/contract/CONTEXT.md`: add the 5.3 status, the Ubiquitous Language terms **Compatibility Rule** and **Unchecked** (a version from before 5.3), the API table (`status`, `compatibility`), and the `contract_versions.is_breaking` note.

**Frontend**
10. `frontend/src/lib/contract-types.ts`: add `breaking: boolean | null` on `ContractVersion`, and add `Compatibility` and `CompatibilityChange` on `VersionDiff`.
11. `frontend/src/hooks/useContracts.ts`: `useContracts(q, page, status)`. Add `status` to the query key and the URL params.
12. `frontend/src/components/CompatibilityBadge.tsx` (a named export; the pattern is from `SpofBadge.tsx`).
13. `routes/_authenticated/contracts.index.tsx`: zod `status: z.enum(['breaking','compatible']).optional()`, a `ToggleGroup`, and a Status column (`colSpan` 4 in the skeleton and empty rows).
14. `components/ContractTimeline.tsx`: add a badge on each version. Add `components/ContractCompatibility.tsx`, which shows the rule hits grouped breaking first with method, path, location, and message. Render it in `contracts.$contractId.tsx` above `ContractChangeList`.

**Acme seed: `seed/acme-fintech`**
15. `src/repos.ts`: `openapiSpec(s, version, {breaking})`. The breaking variant has version = contractVersions+1 and label `1.{n}.0`. It removes one v1 path and adds a required query parameter `currency` to one GET. `writeRepoFiles` writes it when `breaking` is set.
16. `src/load.ts`: `LoadOptions.breaking` publishes the breaking spec as the next version for ledger-service (the seed service with `contractVersions`). `src/cli.ts`: parse `--breaking` for `load`, `verify`, and `bootstrap`, and update the usage string.
17. `src/api.ts`: types for `breaking`, `compatibility`, and `listContracts(q, status)`. `src/verify.ts` → `verifyContracts`:
    - Always assert that v2 has `breaking: false` and `compatibility.changes` holds `path-added`.
    - With `--breaking`, assert that v3 is breaking with the two rule hits and that `?status=breaking` lists ledger-service.
    - `test/fakeApi.ts`: add the fields and the filter.

**k3d suite**
18. `infra/helm/local-test.sh`: add `step "Check 5.3 breaking-change engine"` after the ownership step. It reuses `admin()`.
    - Register a throwaway service, post v1, then post a v2 with a path removed.
    - Assert that `diff.compatibility.isBreaking == true` and that the `path-removed` rule is present.
    - Assert that `GET /contracts?status=breaking` contains the contract and `?status=compatible` does not.
    - Assert that `?status=nope` → 400.
    - Assert that the seeded ledger-service latest has `breaking == false`.
    - Each check prints a `pass`/`fail` line.

**Docs**
19. `docs/roadmap.md`: tick 5.3, and update its *Acme test:* line to ✅ `load --breaking` + `verify --breaking`. `docs/architecture/data-model.md`: add the `is_breaking` column.

## Acceptance criteria
- [ ] Each rule in decision 5 has a golden case. Each case gives the expected `rule`, `breaking`, `direction`, `method`, `path`, and `location`. Each request rule has a response twin of the same edit with the opposite verdict where one applies (field removed, field required added, enum value added, enum value removed).
- [ ] A shared `$ref` component used in a request and a response gives one change for each operation and direction, each with the correct verdict. Cyclic refs end. An unresolvable ref does not crash.
- [ ] A spec that only changes descriptions, the key order, or the YAML/JSON format is compatible, with no changes. A renamed path template parameter is not a removed path.
- [ ] `POST /contracts` stores `is_breaking=false` for v1. `POST /versions` stores the engine verdict against the previous latest. Versions from before 5.3 return `breaking: null`.
- [ ] `GET /contracts?status=breaking|compatible` filters on the latest version and pages correctly (`total` matches). An invalid `status` → 400 `VALIDATION_ERROR` with a traceId. Tenant isolation is kept.
- [ ] `GET /versions/{v}/diff` returns `compatibility` for every version, old ones included. The response validates against `contract.openapi.yaml` 0.4.0.
- [ ] The list shows a Status column with the badge and the All/Breaking/Compatible toggle (in the URL, it resets the page). The timeline shows a badge on each version. The detail page shows the Compatibility section, breaking changes first.
- [ ] `pnpm acme load --breaking && pnpm acme verify --breaking` passes locally. The default `verify` still passes.
- [ ] The k3d step "Check 5.3 breaking-change engine" passes in `build/local-test-report.html`.

## Tests
- **Golden** (`BreakingChangeEngineGoldenTest`, parameterized): `src/test/resources/compat/<case>/{old.yaml,new.yaml,expected.json}`. The cases cover every breaking rule and every compatible mirror, the request and response twins, a shared `$ref` used in both directions, nested objects, array items, an `allOf` merge, a renamed path parameter, a cyclic ref, a no-op reformat, and only description edits.
- **Unit:**
  - `BreakingChangeEngineTest`: ref resolution edge cases (an escaped pointer, an external ref, a missing ref), path-level and op-level parameter merge, and a null `from`.
  - `ContractServiceTest`: publish stores the verdict, and the same hash stores nothing.
- **IT (Testcontainers):**
  - `ContractReadIT`: the `status` filter with pagination and the count, the invalid status → 400, `compatibility` in the diff, and `breaking` in the versions list.
  - `ContractControllerIT`: v1 → false, a breaking v2 → true.
  - `RowLevelSecurityIT` unchanged.
  - `ContractEnvelopeContractTest` covers the new fields.
- **Frontend (Vitest + RTL):** `CompatibilityBadge` states, the list toggle writes `status` and resets the page, the Status column, the timeline badge, and the `ContractCompatibility` grouping.
- **E2E:** extend `frontend/e2e/contracts.spec.ts` (or add a second test in it):
  - Post a v1 and a v2 with a path removed.
  - Assert the Breaking badge in the list, that the Breaking toggle keeps the row and Compatible hides it, and that the detail page shows `path-removed`.
- **Acme:** seed `pnpm test` for the `--breaking` spec generation and the `verify` checks against `fakeApi`.
- **k3d:** step 18.

## Verification
- `./gradlew build --continue`
- `cd frontend && pnpm test && pnpm lint && pnpm exec playwright test e2e/contracts.spec.ts` (check the target DB first: the e2e proxy is hardcoded to :8080)
- `cd seed/acme-fintech && pnpm test && pnpm typecheck`
- `.claude/skills/task-eval/k3d-run.sh` (it calls `infra/helm/local-test.sh --e2e`). Then open `build/local-test-report.html` and confirm the 5.3 step.
- Local app: `pnpm acme load --breaking && pnpm acme verify --breaking`. Then open `/contracts`, toggle Breaking, open ledger-service v3, and confirm the Compatibility section.

## Out of scope
- `oneOf`/`anyOf` branch matching, a removed response status code or media type, a response field that changes from required to optional, a removed request body, and `format`/`nullable`/constraint changes (min/max/pattern).
- A backfill of versions from before 5.3 (decision 2).
- Storage of the classified changes, `contract_checks`, and `POST /contracts/check` (5.5). Consumers (5.4). Slack (5.7).
- A change to the audit payload, and any Kafka topic.
