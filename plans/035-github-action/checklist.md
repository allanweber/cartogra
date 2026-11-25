# Checklist: GitHub Action for the contract check (5.6)
Plan: [plan.md](./plan.md)

## Tasks
- [x] T1 Action skeleton and input checks: test `inputs.test.mjs` passes
- [x] T2 Call, result, and errors: test `check.test.mjs` passes
- [x] T3 Retry and timeout: test `retry.test.mjs` passes
- [x] T4 Job summary: test `summary.test.mjs` passes
- [x] T5 Action README and version check script: test `check-version.test.mjs` passes
- [ ] T6 CI job and release workflow: `actionlint` clean and the `github-action` CI job passes
- [x] T7 Workflow snippet function: test `workflow-snippet.test.ts` passes
- [x] T8 Snippet in the show-once dialog: tests `ApiKeyRevealDialog.test.tsx` and e2e `api-keys.spec.ts` pass
- [x] T9 Workflow in the Acme GitHub mirror: test `repos.test.ts` "writes the Cartogra check workflow only for GitHub repos" passes
- [x] T11 ADR-0033 and docs: the links resolve

## Scenarios
- [x] S-1 Missing required input: unit `inputs.test.mjs`
- [x] S-2 Bad fail-on-breaking value: unit `inputs.test.mjs`
- [x] S-3 Plain http to a remote host: unit `inputs.test.mjs`
- [x] S-4 http to localhost: unit `inputs.test.mjs`
- [x] S-5 Missing spec file: unit `inputs.test.mjs`
- [x] S-6 Spec too large: unit `inputs.test.mjs`
- [x] S-7 Key is masked and sent in the header: unit `check.test.mjs`
- [x] S-8 Breaking check fails the step: unit `check.test.mjs`, `summary.test.mjs`
- [x] S-9 Breaking check with fail-on-breaking false: unit `check.test.mjs`
- [x] S-10 Compatible check passes: unit `check.test.mjs`, `summary.test.mjs`
- [x] S-11 No baseline passes with a warning: unit `check.test.mjs`
- [x] S-12 Other errors fail: unit `check.test.mjs`
- [x] S-13 Response is not JSON: unit `check.test.mjs`
- [x] S-14 Retry on 429 with Retry-After: unit `retry.test.mjs`
- [x] S-15 Three tries on 5xx, then fail: unit `retry.test.mjs`
- [x] S-16 No retry on 4xx: unit `retry.test.mjs`
- [x] S-17 Summary escapes response text: unit `summary.test.mjs`
- [x] S-18 Version mismatch blocks the release: unit `check-version.test.mjs`
- [ ] S-19 Release publishes and moves the major tag: manual, first release `action-v1.0.0`
- [x] S-20 Snippet for a ci:check key: unit `workflow-snippet.test.ts`, `ApiKeyRevealDialog.test.tsx`; e2e `api-keys.spec.ts`
- [x] S-21 Snippet on a self-hosted origin: unit `workflow-snippet.test.ts`
- [x] S-22 No snippet without ci:check: unit `ApiKeyRevealDialog.test.tsx`
- [x] S-23 Mirror repo gets the workflow: unit `repos.test.ts`
- [x] S-24 Azure repo gets no workflow: unit `repos.test.ts`

## Business rules
- [x] BR-1: scenarios S-1, S-2 pass
- [x] BR-2: scenarios S-3, S-4 pass
- [x] BR-3: scenarios S-5, S-6 pass
- [x] BR-4: scenario S-7 passes
- [x] BR-5: scenarios S-8, S-9, S-10 pass
- [x] BR-6: scenarios S-11 pass
- [x] BR-7: scenarios S-12, S-13, S-15 pass
- [x] BR-8: scenarios S-14, S-15, S-16 pass
- [x] BR-9: scenarios S-8, S-10, S-11, S-12 pass
- [x] BR-10: scenarios S-8, S-10, S-17 pass
- [ ] BR-11: scenarios S-18, S-19 pass
- [x] BR-12: scenarios S-20, S-21, S-22 pass
- [x] BR-13: scenarios S-23, S-24 pass

## Acceptance criteria
- [x] AC-1 A breaking spec fails the step with an error annotation
- [x] AC-2 A compatible spec passes
- [x] AC-3 No baseline passes with a warning; other errors fail
- [x] AC-4 The summary lists the changes and the consumers, escaped
- [x] AC-5 The key is masked, in the header only, https only
- [x] AC-6 Retry on 429 and 5xx, 3 tries
- [ ] AC-7 The tag release updates the Marketplace repo, tags, and Release
- [x] AC-8 The dialog shows the snippet for a ci:check key
- [x] AC-9 The Acme GitHub mirror repos have the workflow
- [ ] AC-11 A mirror pull request that removes a response field fails (manual)

## Verification
- [x] `cd ci-extensions/github-action && node --test` passes
- [x] `./gradlew build` passes
- [x] `cd frontend && pnpm lint && pnpm typecheck && pnpm test && pnpm build` passes
- [x] `cd seed/acme-fintech && pnpm typecheck && pnpm test` passes
- [ ] `cd frontend && pnpm test:e2e` passes
- [ ] `actionlint .github/workflows/ci.yml .github/workflows/action-release.yml` passes
