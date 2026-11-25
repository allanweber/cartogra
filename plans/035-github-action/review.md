# Review: plan 035, GitHub Action for the contract check (5.6)

Reviewer: an independent plan review on 2026-11-25. The review covers the uncommitted working tree of `/home/allan/developer/cartogra` (branch `feat/5.6-github-action`), compared with `origin/main`. The branch has no commits after `origin/main`, so the change set is `git diff` plus the untracked files of `git status --short`. `plan.md` has no `## Changes during execution` section.

## 1. Verdict

**APPROVED**

- Blockers: 0
- Major: 0
- Minor: 5

All the unit tests that the plan names exist, test the behaviour, and pass in my run (Action 39/39, frontend 513/513, seed 91/91). Mutation probes on a scratch copy of `index.mjs` show that the Action tests fail when the mask, the pipe escape, the 429 retry, the `BASELINE_NOT_FOUND` pass, `fail-on-breaking`, the try count, or the https rule breaks.

These items are not proven. They stay open until their gate runs:

- T6: `actionlint` is not installed on this machine, and the PR CI job did not run yet.
- T8 and S-20: I did not run the e2e spec. It needs the user's dev stack on port 8080.
- T10, S-25, and AC-10: the k3d step is the `k3d-verify` step. `.claude/project.md` forbids me to run `local-test.sh`.
- S-19, AC-7, and AC-11: these are manual checks after the merge and the first release.

## 2. Plan coverage

### Tasks

| ID | Result | Evidence |
|---|---|---|
| T1 | PASS | `ci-extensions/github-action/action.yml:1-32` (name, 5 inputs, 2 outputs, branding, `node24`); `index.mjs:53-96` (inputs, url, spec); `package.json:3` version `1.0.0`; `.gitkeep` deleted. `inputs.test.mjs` 10/10 pass, and S-1, S-2, S-3, S-5, S-6 assert `requests.length === 0` |
| T2 | PASS | `index.mjs:29` (mask first), `:108` (header), `:144-187` (result mapping). `check.test.mjs` 11/11 pass. The S-7 test finds the key only in line 1 (`::add-mask::`) and in the header |
| T3 | PASS | `index.mjs:8,11,99-133`, `AbortSignal.timeout` at `:110`. `retry.test.mjs` 7/7 pass with a fake `sleep` (no real wait, 1-6 ms each) |
| T4 | PASS | `index.mjs:189-224` (summary, escape), `:178-182` (one `::error`). `summary.test.mjs` 4/4 pass. Gaps in F-1 and F-2 |
| T5 | PASS | `README.md` (usage, both refs, inputs, outputs, error table, "This repository is a copy"); `scripts/check-version.mjs:7-15`; `LICENSE` is equal to the root `LICENSE` (`diff -q`). `check-version.test.mjs` 4/4 pass |
| T6 | UNVERIFIED | `.github/workflows/ci.yml:482-516` (job `github-action`, change detection as `acme-seed`, node 24); `.github/workflows/action-release.yml:1-91` agrees with Decision 5 (SHA-pinned actions, `contents: read`, tag check before change, `rsync --delete --exclude .git`, `vX` force-moved, `vX.Y.Z` not forced, `gh release create`). The YAML parses. `actionlint` is not installed here, and the PR CI job did not run |
| T7 | PASS | `frontend/src/lib/workflow-snippet.ts:1-22`. `workflow-snippet.test.ts` 2/2 pass |
| T8 | UNVERIFIED | `ApiKeyRevealDialog.tsx:57` (snippet only for `ci:check`), `:36` (`sm:max-w-160`); `ApiKeyWorkflowSnippet.tsx` (own "Copy workflow" button). `ApiKeyRevealDialog.test.tsx` 2/2 pass. The e2e spec `api-keys.spec.ts` was not run |
| T9 | PASS | `seed/acme-fintech/src/repos.ts:64,67-86`; `cli.ts:49-51` sets the provider through `withRepoProvider`, so `--provider github` gives the file. `repos.test.ts` "writes the Cartogra check workflow only for GitHub repos" passes; `pnpm typecheck && pnpm test` pass (91/91). README section added |
| T10 | UNVERIFIED (intended) | `infra/helm/local-test.sh:489-530`; `bash -n` passes. The k3d step was not run |
| T11 | PASS | ADR-0033 exists and is in `docs/adr/README.md`; `system-overview.md`, `roadmap.md`, and `CONTEXT-MAP.md` updated. All relative links in the changed docs resolve (script in section 6). 5.6 is not ticked, as the plan requires. Gap in F-4 |

### Business rules

| ID | Result | Evidence (code / test that fails when the rule breaks) |
|---|---|---|
| BR-1 | PASS | `index.mjs:47-66`; `inputs.test.mjs` S-1, S-2 (two tests) |
| BR-2 | PASS | `index.mjs:68-80`; `inputs.test.mjs` S-3, S-4 (remote http, bad URL, localhost, 127.0.0.1, `//` trailer, default url). A mutation that allows remote http fails 1 test |
| BR-3 | PASS | `index.mjs:82-96`; `inputs.test.mjs` S-5, S-6 (limit and limit+1). JS `length` and Java `@Size` both count UTF-16 units |
| BR-4 | PASS | `index.mjs:29-30` (mask, then the key is replaced in each later line), `:108`; `check.test.mjs` S-7. A mutation without the mask fails 1 test |
| BR-5 | PASS (unit) | `index.mjs:167-187`; `check.test.mjs` S-8, S-9, S-10. The S-25 part is UNVERIFIED (k3d) |
| BR-6 | PASS (unit) | `index.mjs:150-155`; `check.test.mjs` S-11. S-25 has no `BASELINE_NOT_FOUND` case, so the k3d part adds no evidence for BR-6 |
| BR-7 | PASS | `index.mjs:157-165`, `:126`; `check.test.mjs` S-12 (6 codes, 200 with no data), S-13; `retry.test.mjs` S-15. Gap in F-3 (timeout in the body read) |
| BR-8 | PASS | `index.mjs:99-133`; `retry.test.mjs` S-14 (Retry-After 1, cap 30, missing 2 s), S-15 (3 tries, 2 s and 4 s, 429 last try, network error, timeout), S-16 |
| BR-9 | PASS (unit) | `index.mjs:151,170,231-234`; S-8, S-10, S-11 assert the outputs, S-12/S-13/S-15 assert an empty `GITHUB_OUTPUT`. S-25 is UNVERIFIED |
| BR-10 | PASS with gaps | `index.mjs:189-224`; `summary.test.mjs` S-8, S-10, S-17. Markdown link syntax is not escaped (F-1) |
| BR-11 | PARTIAL / UNVERIFIED | Version check: `check-version.mjs:9-15`, `check-version.test.mjs` (S-18) PASS. Release steps: `action-release.yml:47-91` read only. S-19 is manual. Partial failure gap in F-2 |
| BR-12 | PASS (unit) | `workflow-snippet.ts:20`, `ApiKeyRevealDialog.tsx:57`; `workflow-snippet.test.ts`, `ApiKeyRevealDialog.test.tsx`. The e2e part is UNVERIFIED |
| BR-13 | PASS | `repos.ts:64,67-86`; `repos.test.ts` "writes the Cartogra check workflow only for GitHub repos" (GitHub has the file and its 5 lines; Azure has no file) |

### Scenarios

| ID | Result | Evidence |
|---|---|---|
| S-1 | PASS | `inputs.test.mjs` "S-1: an empty required input fails before a call" (all 3 required inputs, 0 requests) |
| S-2 | PASS | `inputs.test.mjs` "S-2: a bad fail-on-breaking value fails before a call" |
| S-3 | PASS | `inputs.test.mjs` "S-3: plain http to a remote host fails before a call" |
| S-4 | PASS | `inputs.test.mjs` "S-4: http to localhost calls the check path with no double slash" (exact URL `http://localhost:8080/api/v1/contracts/check`) |
| S-5 | PASS | `inputs.test.mjs` "S-5: a missing spec file fails before a call and names the file" |
| S-6 | PASS | `inputs.test.mjs` "S-6: a spec of more than 1,048,576 characters fails before a call" (message "1 MiB limit") |
| S-7 | PASS | `check.test.mjs` "S-7: ..." (line 1 is the mask, header, no key in other stdout, summary, output, or URL). The Action writes nothing to stderr |
| S-8 | PASS | `check.test.mjs` "S-8: ..." (exit 1, outputs); `summary.test.mjs` "S-8: the summary has the changes table..." (2 rows, 12 names, one `::error` with the service and "2 breaking changes") |
| S-9 | PASS | `check.test.mjs` "S-9: ..." |
| S-10 | PASS | `check.test.mjs` "S-10: ..."; `summary.test.mjs` "S-10: ... No changes" |
| S-11 | PASS | `check.test.mjs` "S-11: no baseline passes with a warning" |
| S-12 | PASS | `check.test.mjs` 6 tests "S-12: <status> <code> fails ..." (status, code, message, empty output) |
| S-13 | PASS | `check.test.mjs` "S-13: ..." (the fake repeats the 502 HTML on each try) |
| S-14 | PASS | `retry.test.mjs` "S-14: a 429 waits Retry-After seconds, then retries" (2 requests, fake clock gap 1000 ms) |
| S-15 | PASS | `retry.test.mjs` "S-15: three tries on 5xx ..." |
| S-16 | PASS | `retry.test.mjs` "S-16: a 4xx does not retry" (6 statuses, 1 request each) |
| S-17 | PASS | `summary.test.mjs` "S-17: ..." (no `<img`, `&#124;` in place of `|`, 3 cells) |
| S-18 | PASS | `check-version.test.mjs` 4 tests. The workflow runs the check at `action-release.yml:33-34`, before the clone at `:47` |
| S-19 | UNVERIFIED (manual) | Manual check after the first release, per the plan |
| S-20 | PARTIAL | Function: `workflow-snippet.test.ts` (no `url:` on `https://cartogra.dev`). Dialog: `ApiKeyRevealDialog.test.tsx` (snippet, no key). e2e `api-keys.spec.ts` not run |
| S-21 | PASS | `workflow-snippet.test.ts` "has a url line on a self-hosted origin" |
| S-22 | PASS | `ApiKeyRevealDialog.test.tsx` "shows no snippet without ci:check" |
| S-23 | PASS | `repos.test.ts` "writes the Cartogra check workflow only for GitHub repos" (ledger-service, org `acme-fintech`) |
| S-24 | PASS | Same test, last assertion (Azure repo has no file) |
| S-25 | UNVERIFIED (intended) | `local-test.sh:489-530`. The compatible run uses the latest stored ledger-service spec (the `--breaking` v3), not "the stored v1 spec of a compatible service" of the scenario text. This agrees with T10 "a spec equal to the baseline" (F-5) |

### Acceptance criteria

| ID | Result | Evidence |
|---|---|---|
| AC-1 | PASS (unit) | S-8 tests. Not ticked in the checklist |
| AC-2 | PASS (unit) | S-10 tests |
| AC-3 | PASS | S-11, S-12, S-13 tests |
| AC-4 | PASS with gap | S-8, S-17 tests; F-1 |
| AC-5 | PASS | S-3, S-4, S-7 tests |
| AC-6 | PASS | S-14, S-15, S-16 tests |
| AC-7 | UNVERIFIED (manual) | Version-mismatch part: S-18 PASS. Publish part: manual first release |
| AC-8 | PARTIAL | Unit tests pass; e2e not run |
| AC-9 | PASS (unit) | `repos.test.ts`; the real mirror is updated by a manual `pnpm acme repos --provider github` |
| AC-10 | UNVERIFIED | k3d not run |
| AC-11 | UNVERIFIED (manual) | Manual PR on the mirror |

### Data model

PASS. No migration and no DTO change (`git diff --name-only origin/main -- '*.java' '*.sql'` is empty). The Action reads `data.isBreaking`, `data.baselineVersion`, `data.changes[].{rule, breaking, method, path, location}`, `data.affectedConsumers[].{serviceName, serviceId}`, and `error.{code, message}`. These agree with `services/contract/src/main/java/io/cartogra/contract/api/dto/ContractCheckResponse.java:7-12`, `VersionDiffResponse.java:27-28`, `ConsumerResponse.java:9-14`, `shared/common/src/main/java/io/cartogra/common/api/ApiResponse.java:3`, and `ApiErrorResponse.java` / `ApiError.java`.

### Assertion changes in existing tests

- `frontend/e2e/api-keys.spec.ts:60`: `{ name: 'Copy' }` became `{ name: 'Copy', exact: true }`. BR-12 supports it: the new "Copy workflow" button also matches the name "Copy". The other e2e and `repos.test.ts` changes add assertions and remove none.

## 3. Findings

### F-1 (minor): the summary does not escape Markdown link and emphasis syntax

- `/home/allan/developer/cartogra/ci-extensions/github-action/index.mjs:216-224`
- Problem: `escapeMarkdown` escapes `& < > | \`` and line breaks only (the list of T4). A consumer name or a rule location such as `[fix here](https://example.com)` or `**x**` renders as a link or as bold text in the job summary. BR-10 says "escapes Markdown and HTML in each value". Service names can come from observed data. GitHub sanitizes `javascript:` links, so the risk is low.
- Fix: also escape `\`, `[`, `]`, `*`, `_`, `(`, `)`, `!`, and `#` (for example with a backslash), and add a case to the S-17 test.

### F-2 (minor): a partial failure of the release leaves the Marketplace repo in a state that a re-run cannot repair

- `/home/allan/developer/cartogra/.github/workflows/action-release.yml:81-83`, `:54`, `:90`
- Problem: the three pushes are separate. If the tag push or `gh release create` fails after `main` or `vX.Y.Z` is pushed, a re-run stops at the "tag exists" check (`:54`). Then the Release or the `vX` move must be done by hand. BR-11 says that the workflow "changes nothing" on a failure only for an existing tag, so this is not a rule break.
- Fix: push `main`, `vX.Y.Z`, and `vX` with one `git push --atomic --force-with-lease` (or push the tags before `main`), and document the manual recovery (`gh release create`) in the README.

### F-3 (minor): a timeout or a network error during the body read does not retry

- `/home/allan/developer/cartogra/ci-extensions/github-action/index.mjs:122`, `:136`
- Problem: `AbortSignal.timeout` also covers the body read. `response.text()` is outside the `try` of the fetch, so a timeout after the headers arrive goes to the generic catch. A probe (section 6) gives `exit 1`, 1 request, and `::error ...::Unexpected error: timed out`. BR-8 requires a retry on a timeout. The response is small, so the risk is low.
- Fix: read the body inside the `try` of the try loop, and treat a read error as a network error or a timeout.

### F-4 (minor): the roadmap Acme test line still has the old test

- `/home/allan/developer/cartogra/docs/roadmap.md:186`
- Problem: the 5.6 entry has the correction (a removed response field or path), but the `*Acme test:*` line under it still says "deleting a required field fails the Action". The two lines disagree.
- Fix: change line 186 to "a PR on the Acme GitHub mirror that removes a response field or a path fails the Action".

### F-5 (minor): the S-25 implementation differs from the scenario text, with no record

- `/home/allan/developer/cartogra/infra/helm/local-test.sh:493-500`
- Problem: S-25 says "the stored v1 spec of a compatible service". The step uses the latest stored ledger-service spec (`openapiSpec(s, breakingVersion(s), { breaking: true })`), which is equal to the baseline. This agrees with T10 and is a valid test, but `plan.md` has no `## Changes during execution` entry for it.
- Fix: add a `## Changes during execution` entry, or change the S-25 text.

## 4. Checklist problems

- Tick without evidence: "`actionlint .github/workflows/ci.yml .github/workflows/action-release.yml` passes" (`checklist.md:78`). `actionlint` is not on this machine (`which actionlint` gives nothing, no `go`). T6 at `checklist.md:10`, which has the same condition, is not ticked. The two lines disagree.
- Tick without full evidence: BR-9 (`checklist.md:53`) lists S-25, and S-25 is not run. BR-5 and BR-6, with the same dependency on S-25, are correctly not ticked.
- Tick without evidence from this review: "`./gradlew build` passes" (`checklist.md:74`). I did not run it. No backend file changed, so the risk is low.
- Passed item without a tick: AC-1 (`checklist.md:60`). The S-8 unit tests prove it, the same as AC-2, which is ticked.
- Passed parts without a tick: T8 has its 2 unit tests passed; only the e2e part is open. S-20 has its unit parts passed.

## 5. Out-of-plan changes

- `.claude/agents/backend-dev.md`, `frontend-dev.md`, `integration-tester.md`, `plan-reviewer.md`, `security-auditor.md`, `.claude/project.md`, `.claude/skills/README.md`, `.claude/skills/execute/SKILL.md`, `.claude/skills/planner/SKILL.md`, `.claude/skills/project-setup/PROJECT-TEMPLATE.md`, `.claude/skills/project-setup/SKILL.md`, `.claude/skills/review-plan/SKILL.md`, `.claude/skills/security-audit/SKILL.md`: these 13 files remove the worktree flow from the skills and agents. No task of plan 035 names them. Put them in a separate commit or PR.
- `ci-extensions/github-action/test/helpers.mjs`: a test harness that no task names. It supports T1-T4 and is in scope.
- The `ci.yml` change detection also triggers on `.github/workflows/action-release.yml` (`ci.yml:503`). This is a small addition to Decision 6, and it is reasonable.
- No item of "Out of scope" is touched: no Azure Pipelines, no PR comment or label, one spec for each step, no guess of the service, no AsyncAPI, no Slack.

## 6. Commands that I ran

| Command | Result |
|---|---|
| `git diff --stat origin/main...HEAD` | Empty (no commits on the branch) |
| `git diff --stat`, `git status --short` | 25 modified or deleted files, 14 untracked paths |
| `cd ci-extensions/github-action && node --test` (node v24.19.0) | 39 pass, 0 fail |
| Mutation probes on a scratch copy of `index.mjs` (7 mutations: no mask, no pipe escape, no 429 retry, no baseline pass, ignore `fail-on-breaking`, 4 tries, remote http) | Each mutation fails 1-3 tests |
| Probe: fake `fetch` whose `text()` throws `TimeoutError` on try 1 | `code: 1, calls: 1`, `Unexpected error: timed out` (F-3) |
| `cd frontend && pnpm exec vitest run src/test/lib/workflow-snippet.test.ts src/test/components/ApiKeyRevealDialog.test.tsx` | 2 files, 4 tests pass |
| `cd frontend && pnpm typecheck` | exit 0 |
| `cd frontend && pnpm lint` | exit 0 |
| `cd frontend && pnpm test` | 58 files, 513 tests pass |
| `cd frontend && pnpm build` | exit 0 |
| `cd seed/acme-fintech && pnpm typecheck && pnpm test` | exit 0; 7 files, 91 tests pass |
| `bash -n infra/helm/local-test.sh` | exit 0 |
| `python3 -c "yaml.safe_load(...)"` on `ci.yml`, `action-release.yml`, `action.yml` | All parse |
| `which actionlint` | Not found. `actionlint` not run |
| `diff -q ci-extensions/github-action/LICENSE LICENSE` | Equal |
| Relative-link check on the 7 changed docs | No broken link |
| `git diff --name-only origin/main -- '*.java' '*.sql'` | Empty |
| Not run: `pnpm test:e2e` (user's dev stack on port 8080), `infra/helm/local-test.sh` (forbidden, `k3d-verify` step), `./gradlew build` (no backend change), any call to cartogra.dev (forbidden) | — |

## Code review

Fixed point: `origin/main` (f040006). The diff includes the changes that are not committed.

### Standards

I found no hard violations. The frontend uses shadcn `Button`, named exports, one component in each file, and Tailwind only. The workflow actions are pinned to SHAs. I found no AI attribution. All findings below are judgement calls.

**Documented standards (soft breaches)**

1. `docs/roadmap.md:294`: "Correction for the Acme test: under the 5.3 rules a removed request field is compatible, so the test pull request removes a response field or a path." This sentence has about 27 words. The user STE rule allows a maximum of 25 words in a descriptive sentence. Split it.
2. `docs/adr/0033-github-action-source-and-marketplace-copy.md:31,37`: "One source, tested in the same CI…" and "A second repository and a PAT to keep." These are fragments with no verb. STE says: do not remove words to make a sentence shorter.
3. `.github/workflows/ci.yml:208` (new job `GitHub Action — Test`): the job is not in the "Required status checks" table at `CONTRIBUTING.md:99`, and it is not in the `needs` of `container-build`. Thus a failed test of the Action does not block a merge. Add it to the ruleset and the table, or record that you decided not to.
4. `ci-extensions/github-action/index.mjs:163-164` (with `README.md:72`): the error messages contain the status, the code, and the message, but they drop `traceId` from the envelope. `.claude/rules/frontend.md` ("Surface `X-Trace-Id`… in user-visible error messages") states the support convention of the repo. It applies only to the frontend, but a CI client also needs the trace for support.

**Baseline smells**

5. Duplicated Code: the same workflow YAML is in three places:
   - `frontend/src/lib/workflow-snippet.ts:5-19`
   - `seed/acme-fintech/src/repos.ts:442-454` (`checkWorkflow`)
   - `ci-extensions/github-action/README.md:14-25`

   They are separate packages, so one shared module is maybe not worth it. If one copy changes (for example `@v1` to `@v2`, or `actions/checkout@v4`), the other copies drift. The e2e test and the seed test pin only some of the lines.
6. Duplicated Code: the copy button logic of `frontend/src/components/ApiKeyWorkflowSnippet.tsx:9-28` is the same as `ApiKeyRevealDialog.tsx:16-26,51-54`. Both have the `copied` state, the `try`/`writeText`/`catch setCopied(false)` block, and the `Check`/`Copy` icon toggle. A small shared `CopyButton({ text, label })` would remove it.
7. Duplicated Code (minor): `.github/workflows/action-release.yml:43-45` parses the version from the tag again, after `scripts/check-version.mjs` already parsed and validated it.
8. Data Clumps (minor): in `index.mjs`, the values `env` and `out` travel together through `report`, `reportResult`, `writeOutputs`, and `writeSummary` (`:144,167,231,236`). One small "step io" object would bundle them. This is acceptable as it is for a file of about 250 lines.
9. Speculative Generality (minor): `index.mjs:7-9` exports `MAX_SPEC_CHARS`, `MAX_TRIES`, and `TRY_TIMEOUT_MS` only for the tests. This is acceptable.

The tracked changes to `.claude/skills/*`, `.claude/agents/*`, `.claude/project.md`, and `CONTEXT-MAP.md` agree with the conventions of the repo. They remove the worktree steps, and that agrees with the memory "Story work in checkout". The new prose in them uses STE.

### Spec

The plan has no `## Changes during execution` section, so no deviation is recorded as accepted. BR-1 to BR-13 are implemented, and each scenario S-1 to S-24 has a named test.

**(a) Missing or partial**

1. Spec: S-25 "Implements: S-25 (BR-5, BR-6, BR-9)". The k3d step does not run a `BASELINE_NOT_FOUND` case, so BR-6 has no k3d proof. The step has only a breaking run and a compatible run, at `infra/helm/local-test.sh:516-530`. The gherkin of S-25 also omits BR-6, so this is low severity.
2. Spec: Decision 12 "Inside the 'Check 5.5 CI check' step ... before the key is revoked". The diff adds a new `step "Check 5.6 GitHub Action"` at `infra/helm/local-test.sh:489`. T10 names that step, so the plan contradicts itself. The run comes before the revoke, which is correct. Record the choice.

**(b) Scope creep**

1. The diff changes `.claude/agents/*.md`, `.claude/project.md`, `.claude/skills/{README,execute,planner,project-setup,review-plan,security-audit}`. These changes remove the worktree flow, for example `.claude/skills/execute/SKILL.md:17` and `.claude/project.md:17` (the "Worktrees" section is deleted). No task, BR, or decision in plan 035 asks for this. Move it to its own change or record it.
2. This one is minor. The `github-action` CI job also starts when `.github/workflows/action-release.yml` changes (`.github/workflows/ci.yml:503`). Spec: Decision 6 "when `ci-extensions/github-action/` changed. It uses the same change detection as `acme-seed`." The change is harmless.

**(c) Implemented but different from the spec**

1. Spec: S-25 "runs index.mjs with the breaking spec of ledger-service ... with the stored v1 spec of a compatible service". T10 adds "Run with the breaking spec of ledger-service (from `pnpm acme` `--breaking` output ...)". The code does the opposite:
   - The breaking run uses `openapiSpec(s, 1)` (v1).
   - The compatible run uses the `--breaking` spec of the same ledger-service.
   - The evidence is at `infra/helm/local-test.sh:493-500`, and the comment there says "v1 is breaking against the stored v3".

   The code is probably right: `acme load --breaking` stores v3 as the baseline, so the plan text is wrong. But the deviation is not recorded. Add it to `## Changes during execution`.
2. Spec: BR-10 "a table of `changes` (rule, severity, location)". `RuleHit` has no severity field. The code shows `breaking` or `non-breaking`, and it joins `method path location` into the location cell (`ci-extensions/github-action/index.mjs:204-205`). The interpretation is reasonable. Record it, because the spec names a field that does not exist.

**Matches the spec (checked)**
- The mask comes first, and the key is redacted from all output (`index.mjs:29-30`).
- The https/localhost rule and the trailing-slash rule are implemented (`:68-80`).
- The retry waits (2 s and 4 s, and Retry-After with a maximum of 30 s) and the 30 s timeout on each try are implemented (`:99-133`).
- `BASELINE_NOT_FOUND` is the only error that passes (`:150`).
- No outputs are written on a failure.
- The summary escapes the values.
- The release workflow does the version check, the tests, and the check for an existing tag before it changes anything. It never force-moves `vX.Y.Z`, it force-moves `vX`, and it has `contents: read`.
- The snippet has the `url:` line only when the origin is not `https://cartogra.dev`. It never contains the key, and it shows only for `ci:check`.
- The seed workflow is written only for the GitHub target.
- The LICENSE is equal to the root LICENSE.
- The README says that the repo is a copy.
- 5.6 in the roadmap is not ticked, as the plan says.

**Summary:** Standards has 9 findings, all of them judgement calls. The worst is that the new `GitHub Action — Test` job is not a required check (`.github/workflows/ci.yml:208`). Spec has 6 findings. The worst is that the k3d breaking and compatible runs are the opposite of the S-25 text, and that this deviation is not recorded (`infra/helm/local-test.sh:493-500`).
