# GitHub Action for the contract check (5.6)
Issue: #136  ·  Branch: `feat/5.6-github-action`

## Problem
The CI check (5.5) exists, but each team must write its own curl call, parse the JSON, and fail the job. Nothing blocks a pull request on a breaking change without that work. The developer also sees only raw JSON in the log, and not the changes or the affected consumers.

## Solution
A GitHub Action "Cartogra Contract Check" sends the spec of a repository to `POST /api/v1/contracts/check` and fails the step when the change is breaking. It writes a table of the changes and the affected consumers to the job summary. The source is in `ci-extensions/github-action/`. A tag workflow publishes a copy to the Marketplace repo `allanweber/cartogra-contract-check`. When an admin creates a `ci:check` key, the show-once dialog shows a workflow snippet to copy. The Acme GitHub mirror gets the workflow, so a test pull request proves the full loop.

## User stories
1. As a developer, I want my pull request to fail when my spec change is breaking, so that I do not break the consumers.
2. As a developer, I want a table of the changes and the affected consumers in the job summary, so that I know what to fix and whom to tell.
3. As a developer, I want an error annotation on a breaking check, so that the failure shows on the pull request.
4. As a developer of a new service, I want the check to pass when Cartogra has no baseline yet, so that my first spec can merge.
5. As a team lead, I want to set `fail-on-breaking: false`, so that a planned break with agreed consumers can merge.
6. As a workflow author, I want outputs `is-breaking` and `changes-count`, so that later steps can use the result.
7. As a self-hosted user, I want to set `url`, so that the Action calls my own Cartogra.
8. As a workflow author, I want the step to fail on a bad key, an unknown service, or a bad spec, so that a broken setup does not pass silently.
9. As a monorepo owner, I want the Action to retry on 429 and 5xx, so that parallel checks and short outages do not fail the job.
10. As a security engineer, I want the key masked in the log and sent only over https, so that it does not leak.
11. As a user, I want to pin `allanweber/cartogra-contract-check@v1` from the Marketplace, or `allanweber/cartogra/ci-extensions/github-action@<ref>`, so that I can select the source.
12. As the maintainer, I want one tag `action-vX.Y.Z` to test, copy, tag, and release the Marketplace repo, so that the copy never drifts by hand.
13. As an admin, I want a ready workflow snippet when I create a `ci:check` key, so that I can set up CI in one copy.
14. As the maintainer, I want each Acme GitHub mirror repo to have the workflow, so that a test pull request on the mirror fails on a breaking change.
15. As the k3d suite, I want to run the Action script against the local gateway, so that the full flow has a pass/fail result in the report.

## Business rules
- **BR-1**: The inputs are `api-key` (required), `service` (required, an id or a name), `spec` (required, a file path relative to the workspace), `url` (default `https://cartogra.dev`), and `fail-on-breaking` (default `true`). An empty required input fails the step before a call. `fail-on-breaking` accepts only `true` or `false`.
- **BR-2**: `url` must use `https`. The only exception is `http` to `localhost` or `127.0.0.1`. Any other `url` fails the step before a call. The Action sends a trailing slash nowhere: it calls `<url>/api/v1/contracts/check`.
- **BR-3**: The Action reads the spec file as UTF-8 text. A missing file fails the step. A file of more than 1,048,576 characters fails the step before a call (the server limit, `ContractRequest.MAX_SPEC_CHARS`).
- **BR-4**: The Action masks `api-key` with `::add-mask::` before any other output. It sends the key only in the header `X-Cartogra-Api-Key`. No log line, summary, or error text contains the key.
- **BR-5**: A 200 response with `isBreaking: true` fails the step when `fail-on-breaking` is `true`. When `fail-on-breaking` is `false`, the step passes with a warning annotation. A 200 with `isBreaking: false` passes.
- **BR-6**: A 404 with the error code `BASELINE_NOT_FOUND` passes the step with a warning annotation. The outputs are `is-breaking=false` and `changes-count=0`.
- **BR-7**: Each other error fails the step with the HTTP status and the error code and message of the envelope: 400, 401, 403, 404 `SERVICE_NOT_FOUND`, 409 `SERVICE_AMBIGUOUS`, 422, a 429 or 5xx after the last try, a network error, a timeout, and a response that is not JSON.
- **BR-8**: The Action makes a maximum of 3 tries. It retries only on 429, 5xx, a network error, and a timeout. Each try has a timeout of 30 s. On 429 it waits `Retry-After` seconds (maximum 30 s), else 2 s. After a 5xx or network error it waits 2 s, then 4 s. Each other status does not retry.
- **BR-9**: After a 200 or a `BASELINE_NOT_FOUND`, the Action sets the outputs `is-breaking` (`true` or `false`) and `changes-count` (the number of items in `changes`). After a failure in BR-7, it sets no outputs.
- **BR-10**: After a 200, the job summary shows the service, the baseline version, the result (breaking or compatible), a table of `changes` (rule, severity, location), and the names of `affectedConsumers`. A compatible check with no changes shows "No changes". A breaking check also gives one error annotation that names the service and the number of breaking changes. The summary escapes Markdown and HTML in each value from the response.
- **BR-11**: A tag `action-vX.Y.Z` on this repo starts the release workflow. The workflow fails when the tag version is not equal to `version` in `ci-extensions/github-action/package.json`, or when `node --test` fails. Then it replaces the content of `allanweber/cartogra-contract-check` with the folder, commits, pushes the tag `vX.Y.Z`, moves the tag `vX` to that commit, and creates a GitHub Release `vX.Y.Z`. A tag that exists already in the Marketplace repo fails the workflow, and the workflow changes nothing.
- **BR-12**: The show-once dialog shows the workflow snippet only when the new key has the scope `ci:check`. The snippet contains `uses: allanweber/cartogra-contract-check@v1`, `api-key: ${{ secrets.CARTOGRA_API_KEY }}`, `service: <your-service-name>`, and `spec: openapi.yaml`. It has a line `url: <origin>` only when `window.location.origin` is not `https://cartogra.dev`. The snippet never contains the key.
- **BR-13**: `renderRepo` writes `.github/workflows/cartogra-check.yml` only when the repo is pushed to GitHub. The workflow runs on `pull_request`, uses `allanweber/cartogra-contract-check@v1`, sets `service` to the service name and `spec` to `openapi.yaml`, and reads the key from `secrets.CARTOGRA_API_KEY`. A repo on Azure DevOps gets no workflow file.

## Scenarios
### S-1: Missing required input (BR-1)
```gherkin
Given the input "service" is empty
When the Action runs
Then the step fails with "Input required: service"
And no HTTP call is made
```

### S-2: Bad fail-on-breaking value (BR-1)
```gherkin
Given "fail-on-breaking" is "yes"
When the Action runs
Then the step fails with a message that names "fail-on-breaking"
And no HTTP call is made
```

### S-3: Plain http to a remote host (BR-2)
```gherkin
Given "url" is "http://cartogra.example.com"
When the Action runs
Then the step fails with a message that https is required
And no HTTP call is made
```

### S-4: http to localhost (BR-2)
```gherkin
Given "url" is "http://localhost:8080/"
When the Action runs
Then the call goes to "http://localhost:8080/api/v1/contracts/check"
```

### S-5: Missing spec file (BR-3)
```gherkin
Given "spec" is "missing.yaml" and the file does not exist
When the Action runs
Then the step fails with a message that names "missing.yaml"
And no HTTP call is made
```

### S-6: Spec too large (BR-3)
```gherkin
Given the spec file has 1,048,577 characters
When the Action runs
Then the step fails with a message about the 1 MiB limit
And no HTTP call is made
```

### S-7: Key is masked and sent in the header (BR-4)
```gherkin
Given "api-key" is "ck_secret"
When the Action runs and the server returns 401
Then the first stdout line is "::add-mask::ck_secret"
And the request has the header "X-Cartogra-Api-Key: ck_secret"
And no other stdout, stderr, or summary text contains "ck_secret"
```

### S-8: Breaking check fails the step (BR-5, BR-9, BR-10)
```gherkin
Given the server returns 200 with isBreaking true, 2 changes, and 12 affected consumers
When the Action runs with fail-on-breaking true
Then the exit code is 1
And GITHUB_OUTPUT has "is-breaking=true" and "changes-count=2"
And the summary has a table with 2 rows and the 12 consumer names
And stdout has one "::error" annotation that names the service and "2 breaking changes"
```

### S-9: Breaking check with fail-on-breaking false (BR-5)
```gherkin
Given the server returns 200 with isBreaking true
When the Action runs with fail-on-breaking false
Then the exit code is 0
And stdout has a "::warning" annotation
And GITHUB_OUTPUT has "is-breaking=true"
```

### S-10: Compatible check passes (BR-5, BR-9, BR-10)
```gherkin
Given the server returns 200 with isBreaking false and 0 changes
When the Action runs
Then the exit code is 0
And GITHUB_OUTPUT has "is-breaking=false" and "changes-count=0"
And the summary says "No changes"
```

### S-11: No baseline passes with a warning (BR-6, BR-9)
```gherkin
Given the server returns 404 with code BASELINE_NOT_FOUND
When the Action runs
Then the exit code is 0
And stdout has a "::warning" annotation
And GITHUB_OUTPUT has "is-breaking=false" and "changes-count=0"
```

### S-12: Other errors fail (BR-7, BR-9)
```gherkin
Given the server returns <status> with code <code>
When the Action runs
Then the exit code is 1
And the error text has <status> and <code>
And GITHUB_OUTPUT is empty
Examples: 401 UNAUTHORIZED, 403 FORBIDDEN, 404 SERVICE_NOT_FOUND, 409 SERVICE_AMBIGUOUS, 422 INVALID_SPEC, 400 VALIDATION_ERROR
```

### S-13: Response is not JSON (BR-7)
```gherkin
Given the server returns 502 with an HTML body on each try
When the Action runs
Then the exit code is 1
And the error text has 502
```

### S-14: Retry on 429 with Retry-After (BR-8)
```gherkin
Given the first try returns 429 with Retry-After 1
And the second try returns 200 with isBreaking false
When the Action runs
Then 2 requests are made, the second one at least 1 s after the first
And the exit code is 0
```

### S-15: Three tries on 5xx, then fail (BR-7, BR-8)
```gherkin
Given each try returns 503
When the Action runs
Then 3 requests are made
And the exit code is 1
```

### S-16: No retry on 4xx (BR-8)
```gherkin
Given the server returns 422
When the Action runs
Then 1 request is made
```

### S-17: Summary escapes response text (BR-10)
```gherkin
Given a consumer name is "<img src=x>|svc"
When the Action writes the summary
Then the summary has no raw "<img" and no unescaped "|" in that cell
```

### S-18: Version mismatch blocks the release (BR-11)
```gherkin
Given package.json has version "1.0.0"
When the tag "action-v1.0.1" is pushed
Then the release workflow fails before it touches the Marketplace repo
```

### S-19: Release publishes and moves the major tag (BR-11)
```gherkin
Given package.json has version "1.1.0" and the tests pass
When the tag "action-v1.1.0" is pushed
Then the Marketplace repo has a commit with the folder content
And the tags v1.1.0 and v1 point to that commit
And a Release v1.1.0 exists
```

### S-20: Snippet for a ci:check key (BR-12)
```gherkin
Given an admin on https://cartogra.dev creates a key with scope ci:check
When the show-once dialog opens
Then the dialog shows a snippet with "uses: allanweber/cartogra-contract-check@v1" and "secrets.CARTOGRA_API_KEY"
And the snippet has no "url:" line
And the snippet does not contain the key
```

### S-21: Snippet on a self-hosted origin (BR-12)
```gherkin
Given the origin is "http://localhost:5173"
When the dialog shows the snippet
Then the snippet has the line "url: http://localhost:5173"
```

### S-22: No snippet without ci:check (BR-12)
```gherkin
Given a new key with no ci:check scope
When the show-once dialog opens
Then no snippet shows
```

### S-23: Mirror repo gets the workflow (BR-13)
```gherkin
Given ledger-service is pushed to GitHub org "acme-fintech"
When renderRepo runs
Then the files have ".github/workflows/cartogra-check.yml"
And the file has "service: ledger-service" and "spec: openapi.yaml" and "secrets.CARTOGRA_API_KEY"
```

### S-24: Azure repo gets no workflow (BR-13)
```gherkin
Given a service on Azure DevOps
When renderRepo runs
Then the files have no ".github/workflows/cartogra-check.yml"
```

### S-25: Full flow on k3d (BR-5, BR-6, BR-9) (removed 2026-11-25)
```gherkin
Given the k3d stack with the Acme data and a temporary ci:check key
When local-test.sh runs index.mjs with the breaking spec of ledger-service
Then the exit code is 1 and GITHUB_OUTPUT has "is-breaking=true"
When it runs index.mjs with the stored v1 spec of a compatible service
Then the exit code is 0 and GITHUB_OUTPUT has "is-breaking=false"
```

## Data model
No change. No migration. The Action reads the existing response of `POST /api/v1/contracts/check`:

```ts
// ApiResponse envelope, data on 200
{ isBreaking: boolean, baselineVersion: number,
  changes: RuleHit[],          // VersionDiffResponse.RuleHit
  affectedConsumers: Consumer[] } // ConsumerResponse
// error: { error: { code: string, message: string } }
```

The implementer reads the exact fields of `RuleHit`, `ConsumerResponse`, and the error envelope in `services/contract/.../api/dto/` and `shared/`. The Action uses only these fields.

## Decisions
1. **Node action, no dependencies.** `action.yml` uses `runs: using: node24, main: index.mjs`. One `index.mjs` with built-in `fetch` and `AbortSignal.timeout`. It writes `GITHUB_OUTPUT` and `GITHUB_STEP_SUMMARY` and the workflow commands (`::add-mask::`, `::error::`, `::warning::`) itself. There is no `node_modules`, no bundling, and no `dist/`.
2. **Testable core.** `index.mjs` exports `run({ env, fetch, readFile, stdout, sleep })` and returns the exit code. The bottom of the file calls `run` with the real values only when the file is the entry point. The tests inject a fake `fetch`, a fake `sleep`, and temporary files.
3. **Tests with `node:test`.** `node --test` in the folder runs `test/*.test.mjs`. `package.json` has `"type": "module"`, `"version"`, `"engines": { "node": ">=24" }`, a `test` script, and no dependencies.
4. **Two refs, one source (ADR-0033).** The source is `ci-extensions/github-action/`. The Marketplace copy is `allanweber/cartogra-contract-check`, with the action name "Cartogra Contract Check". Nobody edits the copy by hand.
5. **Release workflow** `.github/workflows/action-release.yml`. Trigger: `push` of tags `action-v*`. It uses the secret `ACTION_REPO_TOKEN`: a fine-grained PAT with `contents: write` on `cartogra-contract-check` only. It uses no other secret, and it has `permissions: contents: read` in this repo. The `vX` tag is force-moved; the `vX.Y.Z` tag is never force-moved. The Release is created with `gh release create` and the token.
6. **CI job.** `ci.yml` gets a job `github-action` that runs `node --test` in the folder when `ci-extensions/github-action/` changed. It uses the same change detection as `acme-seed`.
7. **Error handling.** `BASELINE_NOT_FOUND` is the only error that passes. The reason: a new service has nothing to break yet, and spec discovery (5.8) stores the first version after the merge.
8. **Override.** Only the input `fail-on-breaking`. There is no PR-label logic and no PR comment, so the Action needs no `GITHUB_TOKEN` permissions.
9. **UI snippet.** A new component `ApiKeyWorkflowSnippet` in `ApiKeyRevealDialog`, with its own copy button. The snippet comes from a pure function `workflowSnippet(origin)` in `frontend/src/lib/`. The `url:` line rule is in BR-12.
10. **Seed workflow.** `renderRepo` adds the file only for the GitHub push target. You create the org secret `CARTOGRA_API_KEY` on the Acme org with a production `ci:check` key, and you open the test pull request by hand.
11. **Acme test correction.** Under the 5.3 rules, a removed request field is compatible. The test pull request removes a response field or a path of the spec.
12. **k3d step (removed 2026-11-25).** Inside the "Check 5.5 CI check" step of `infra/helm/local-test.sh`, before the key is revoked, run `node ci-extensions/github-action/index.mjs` with the `INPUT_*` env vars, a temporary `GITHUB_OUTPUT`, and a temporary `GITHUB_STEP_SUMMARY`. One pass/fail line for the breaking run, and one for the compatible run.

## Test seams
- **Action:** the exported `run(...)` of `index.mjs`, with a fake `fetch`. This is the one seam for BR-1 to BR-10. The tests check the exit code, stdout, `GITHUB_OUTPUT`, and the summary file, not internal functions.
- **Release:** the version check is a small script `ci-extensions/github-action/scripts/check-version.mjs <tag>`, tested by `node:test`. The push, tag, and Release steps have no automated test; S-19 is a manual check of the first release.
- **Frontend:** vitest on `workflowSnippet` and on `ApiKeyRevealDialog` (prior art: `src/test/components/ApiKeyCreateDialog.test.tsx`). Playwright: extend `e2e/api-keys.spec.ts`.
- **Seed:** vitest on `renderRepo` in `test/repos.test.ts` (prior art: the CODEOWNERS test).
- **Full flow:** the k3d step in `infra/helm/local-test.sh`.

## Tasks
### T1: Action skeleton and input checks
- Implements: BR-1, BR-2, BR-3, S-1, S-2, S-3, S-4, S-5, S-6
- Module: `ci-extensions/github-action`
- Files: `ci-extensions/github-action/action.yml`, `ci-extensions/github-action/index.mjs`, `ci-extensions/github-action/package.json`, `ci-extensions/github-action/test/inputs.test.mjs`, remove `ci-extensions/github-action/.gitkeep`
- Depends on: none
- Change: `action.yml` with the name "Cartogra Contract Check", the inputs and outputs of BR-1 and BR-9, `branding`, and `runs: node24`. `index.mjs` with `run(...)`: read the `INPUT_*` env vars, check the inputs, the url, and the spec file. Build the request URL. Version `1.0.0`.
- Test: unit `inputs.test.mjs` (S-1 to S-6)
- Done when: `node --test` passes in the folder, and no fake `fetch` call happens in S-1, S-2, S-3, S-5, S-6.

### T2: Call, result, and errors
- Implements: BR-4, BR-5, BR-6, BR-7, BR-9, S-7, S-8 (exit code and outputs), S-9, S-10 (exit code and outputs), S-11, S-12, S-13
- Module: `ci-extensions/github-action`
- Files: `ci-extensions/github-action/index.mjs`, `ci-extensions/github-action/test/check.test.mjs`
- Depends on: T1
- Change: mask the key first. POST `{service, spec}` with `X-Cartogra-Api-Key` and `Content-Type: application/json`. Map the response to the exit code, the annotations, and the outputs.
- Test: unit `check.test.mjs` (S-7 to S-13)
- Done when: the tests pass, and the S-7 test finds the key only in the mask line and the header.

### T3: Retry and timeout
- Implements: BR-8, S-14, S-15, S-16
- Module: `ci-extensions/github-action`
- Files: `ci-extensions/github-action/index.mjs`, `ci-extensions/github-action/test/retry.test.mjs`
- Depends on: T2
- Change: a maximum of 3 tries with the waits of BR-8 through the injected `sleep`, and `AbortSignal.timeout(30000)` on each try.
- Test: unit `retry.test.mjs` (S-14 to S-16; the fake `sleep` records the waits)
- Done when: the tests pass with no real wait.

### T4: Job summary
- Implements: BR-10, S-8 (summary and annotation), S-10 (summary), S-17
- Module: `ci-extensions/github-action`
- Files: `ci-extensions/github-action/index.mjs`, `ci-extensions/github-action/test/summary.test.mjs`
- Depends on: T2
- Change: append the Markdown summary to `GITHUB_STEP_SUMMARY`. Escape `|`, `<`, `>`, `&`, and backticks in each value. One `::error` annotation on a breaking check.
- Test: unit `summary.test.mjs` (S-8, S-10, S-17)
- Done when: the tests pass.

### T5: Action README and version check script
- Implements: BR-11, S-18
- Module: `ci-extensions/github-action`
- Files: `ci-extensions/github-action/README.md`, `ci-extensions/github-action/scripts/check-version.mjs`, `ci-extensions/github-action/test/check-version.test.mjs`, `ci-extensions/github-action/LICENSE`
- Depends on: T1
- Change: README with the usage, both refs, the inputs, the outputs, the error table, and "this repo is a copy, open issues on allanweber/cartogra". `check-version.mjs <tag>` exits 1 when `action-vX.Y.Z` does not equal `package.json` `version`. Copy the Apache-2.0 `LICENSE` of the root (the Marketplace repo needs one).
- Test: unit `check-version.test.mjs` (S-18)
- Done when: the test passes.

### T6: CI job and release workflow
- Implements: BR-11, S-18, S-19
- Module: `.github`
- Files: `.github/workflows/ci.yml`, `.github/workflows/action-release.yml`
- Depends on: T5
- Change: a `github-action` job in `ci.yml` that runs `node --test` on node 24 when the folder changed. `action-release.yml` per Decision 5: check the version, run the tests, clone the Marketplace repo with `ACTION_REPO_TOKEN`, stop when the tag exists, replace the content (`rsync --delete`, keep `.git`), commit, tag `vX.Y.Z`, force-move `vX`, push, `gh release create`. Pin each third-party action by SHA, as in `ci.yml`.
- Test: `actionlint` on both files, and the `github-action` job is green on the PR. S-19 is a manual check after the merge (see Notes).
- Done when: `actionlint` reports no error, and the CI job passes on the PR.

### T7: Workflow snippet function
- Implements: BR-12, S-20 (content), S-21
- Module: `frontend`
- Files: `frontend/src/lib/workflow-snippet.ts`, `frontend/src/test/lib/workflow-snippet.test.ts`
- Depends on: none
- Change: `workflowSnippet(origin: string): string` per BR-12.
- Test: unit `workflow-snippet.test.ts` (S-20 content, S-21)
- Done when: the tests pass.

### T8: Snippet in the show-once dialog
- Implements: BR-12, S-20, S-22
- Module: `frontend`
- Files: `frontend/src/components/ApiKeyWorkflowSnippet.tsx`, `frontend/src/components/ApiKeyRevealDialog.tsx`, `frontend/src/test/components/ApiKeyRevealDialog.test.tsx`, `frontend/e2e/api-keys.spec.ts`
- Depends on: T7
- Change: show the snippet with a "Copy workflow" button when `created.scopes` has `ci:check`. Obey `DESIGN.md` for the code block. The dialog can become wider if the snippet needs it.
- Test: unit `ApiKeyRevealDialog.test.tsx` "shows the workflow snippet for a ci:check key" and "shows no snippet without ci:check"; e2e `api-keys.spec.ts` "an admin creates a key, sees it one time, sees only the prefix, then revokes it" also checks the snippet.
- Done when: the unit tests and the e2e spec pass.

### T9: Workflow in the Acme GitHub mirror
- Implements: BR-13, S-23, S-24
- Module: `seed/acme-fintech`
- Files: `seed/acme-fintech/src/repos.ts`, `seed/acme-fintech/test/repos.test.ts`, `seed/acme-fintech/README.md`
- Depends on: none
- Change: `renderRepo` adds `.github/workflows/cartogra-check.yml` for the GitHub push target (BR-13). README: the org secret `CARTOGRA_API_KEY`, and the manual test pull request that removes a response field.
- Test: unit `repos.test.ts` "writes the Cartogra check workflow only for GitHub repos" (S-23, S-24)
- Done when: `pnpm typecheck && pnpm test` pass in `seed/acme-fintech`.

### T10: k3d step runs the Action (removed 2026-11-25)
- Implements: S-25 (BR-5, BR-6, BR-9)
- Module: `infra/helm`
- Files: `infra/helm/local-test.sh`
- Depends on: T4
- Change: per Decision 12. Run with the breaking spec of ledger-service (from `pnpm acme` `--breaking` output or `openapiSpec`), and with a spec equal to the baseline of one service. Assert the exit codes and `GITHUB_OUTPUT`. Each assert has pass/fail in the report.
- Test: k3d `local-test.sh` step "Check 5.6 GitHub Action" (run by the `k3d-verify` step, not by the implementer)
- Done when: `bash -n infra/helm/local-test.sh` passes, and the k3d step is green in the report.

### T11: ADR-0033 and docs
- Implements: Decision 4; docs for BR-1 to BR-13
- Module: `docs`
- Files: `docs/adr/0033-github-action-source-and-marketplace-copy.md`, `docs/adr/README.md`, `docs/architecture/system-overview.md`, `docs/roadmap.md`, `CONTEXT-MAP.md`
- Depends on: T6, T9
- Change: the ADR (source in `ci-extensions/`, copy synced by tag, PAT scope, why not one ref only). Add it to the ADR index. `system-overview.md` names the Action as the CI client of `/contracts/check`. The roadmap 5.6 entry gets the Acme test correction (a removed response field). Tick 5.6 only after the manual checks in Notes pass.
- Test: none (docs); the links resolve.
- Done when: each link in the changed files resolves.

## Acceptance criteria
- [ ] AC-1: A breaking spec fails the step, with an error annotation, when `fail-on-breaking` is `true`.
- [ ] AC-2: A compatible spec passes the step.
- [ ] AC-3: `BASELINE_NOT_FOUND` passes with a warning. Each other error fails with its status and code.
- [ ] AC-4: The job summary lists the changes and the affected consumers, with escaped values.
- [ ] AC-5: The key is masked, sent only in `X-Cartogra-Api-Key`, and only over https (http only to localhost).
- [ ] AC-6: 429 and 5xx retry up to 3 tries. Other 4xx do not retry.
- [ ] AC-7: A tag `action-vX.Y.Z` with a matching version updates `cartogra-contract-check`, its `vX.Y.Z` and `vX` tags, and its Release. A version mismatch changes nothing.
- [ ] AC-8: The show-once dialog shows the workflow snippet for a `ci:check` key, with no key in it.
- [ ] AC-9: Each Acme GitHub mirror repo has `.github/workflows/cartogra-check.yml`.
- [ ] AC-10 (removed 2026-11-25): The k3d step "Check 5.6 GitHub Action" passes.
- [ ] AC-11: A pull request on the Acme GitHub mirror that removes a response field fails the Action (manual).

## Out of scope
- Azure Pipelines and other CI systems.
- A PR comment, PR-label override, or check runs through the Checks API.
- More than one spec in one step. A repo with two specs uses two steps.
- A guess of the service from the repo name.
- AsyncAPI specs.
- Slack notifications (5.7).

## Notes
- **Manual steps for the user, before the first release:**
  1. Create the public repo `allanweber/cartogra-contract-check` with no content.
  2. Create a fine-grained PAT with `contents: write` on that repo only.
  3. Add the PAT as the secret `ACTION_REPO_TOKEN` in `allanweber/cartogra`.
  4. After the merge, push the tag `action-v1.0.0`.
  5. Open the Release v1.0.0 in the Marketplace repo and select "Publish this Action to the GitHub Marketplace".
- **Manual Acme test (AC-11):**
  1. Create a production `ci:check` key for the Acme tenant.
  2. Add the key as the org secret `CARTOGRA_API_KEY` on the Acme GitHub org.
  3. Run `pnpm acme repos --provider github`.
  4. Open a pull request that removes a response field.
  The seed workflow uses `@v1`, so the release must exist first.
- Do not run the Action or a test against cartogra.dev from the implementation. The k3d step is the automated proof.
- `ci-extensions/` is not in the module table of `.claude/project.md`. The orchestrator does T1 to T5 as "other" work.
- `CONTEXT.md` gets no new term. The Action is a client of the Contract Check, not a domain concept.

## Changes during execution
- 2026-11-25: BR-10 "severity" shows `breaking` or `non-breaking`, and the location cell joins the method, the path, and the location. Reason: `RuleHit` has no severity field.
- 2026-11-25: The `github-action` CI job also starts when `.github/workflows/action-release.yml` changes. Reason: the release workflow runs the same tests.
- 2026-11-25: Fix round after the review: a timeout while the body is read now retries (BR-8). The summary also escapes the link and emphasis syntax (BR-10). The release pushes `main` and the two tags in one atomic push. `GitHub Action — Test` is a required check and a need of `container-build`.
- 2026-11-25: The user removed the k3d suite from the project: `infra/helm/local-test.sh`, its report, and the `k3d-verify` skill. T10, S-25, Decision 12, and AC-10 are removed with it. Reason: the k3d runs and their fixes cost too much time. The unit tests of the Action cover BR-5, BR-6, and BR-9, and the CI job runs them. One k3d run on 2026-11-25 passed the step "Check 5.6 GitHub Action" before the removal.
