# Skills

These skills change an idea into a plan, then implement the plan with worker agents. The plan has business rules, scenarios, a data model, and atomic tasks.

## Workflow

```
/planner
  ├── design-interview   interview, one question at a time
  ├── domain-language    CONTEXT.md terms and ADRs, during the interview
  └── plans/NNN-<slug>/
        ├── plan.md        rules, scenarios, data model, tasks
        └── checklist.md   test record for each task, scenario, and rule

/execute NNN
  ├── worktree + branch from plan.md
  ├── for each backend module:  backend-dev → integration-tester
  ├── bootRun from the worktree (dev infra, port 8080)
  ├── frontend:                 frontend-dev (code, Vitest, Playwright e2e)
  ├── final checks: gradle build, frontend checks, full e2e suite
  └── report, then wait for "commit"

/review-plan NNN
  ├── plan-reviewer (Opus, high effort): plan coverage, with evidence  ┐
  └── code-review skill: Standards + Spec axes                         ┴→ review.md

/ship NNN   (runs the other commands, stops at your gates)
  ├── execute → review-plan (+ security-audit when auth, queries, or config change)
  ├── gate: you approve fix tasks → next round (max 2)
  ├── gate: "commit"
  └── gate: push + PR, watch CI

/security-audit [NNN]
  ├── security-auditor (Opus, high effort): five categories      ┐
  └── security-review skill: changes on the branch              ┴→ findings.json → PDF
```

1. Type `/planner` and describe the feature or the issue.
2. Answer the questions. The agent asks one question at a time and gives a recommended answer first.
3. Confirm the summary of the decisions.
4. Review `plan.md` and `checklist.md` in the new `plans/NNN-<slug>/` folder.
5. Type `/execute NNN`. Or type `/ship NNN` to run steps 5 to 9 with the review rounds, and continue at its gates.
6. Answer the questions about plan problems, if the executor asks.
7. Review the report.
8. Optional: type `/review-plan NNN` for an independent review. Read `review.md` in the plan folder.
9. Say "commit" to the executor to get one commit for each module. The executor does not push.

## Skills

| Skill | Start | Purpose |
|---|---|---|
| `planner` | You only (`/planner`) | Runs the interview, then writes the plan folder. |
| `design-interview` | You or the agent | Interviews you about a design. It covers the design decisions and these mandatory topics: business rules, scenarios, data model, test seams, e2e, acceptance criteria, branch name, and linked issue. |
| `domain-language` | You or the agent | Keeps the domain terms in `CONTEXT.md` and `CONTEXT-MAP.md` correct. It records ADRs only for decisions that are hard to reverse, surprising, and a real trade-off. |
| `execute` | You only (`/execute NNN`) | Sends the tasks to the workers, judges the results against the plan, runs the final checks, and asks you when the plan is wrong. It continues from the checklist after a stop. |
| `ship` | You only (`/ship NNN`) | Runs `execute`, `review-plan`, and `security-audit` (only when the diff touches auth, queries, or config). It changes the findings into fix tasks for your approval, with a maximum of 2 fix rounds. Then it stops for the commit, and for the push and the PR. Its log is `ship-log.md` in the plan folder. |
| `review-plan` | You only (`/review-plan NNN`) | Sends `plan-reviewer` to verify each task, BR, scenario, and checklist tick against the code and the tests. At the same time, it runs the `code-review` skill with `plan.md` as the spec. Both results go into `review.md`. It changes no code. |
| `security-audit` | You only (`/security-audit [NNN]`) | Sends `security-auditor` to audit tenant isolation, browser-only permissions, IDOR, hard-coded secrets, and XSS. At the same time, it runs the `security-review` skill. It merges both into `findings.json`, then makes a PDF in `docs/security-audit/` with GitHub issue texts. Git ignores that folder. It changes no code. |
| `build-in-public` | You only (`/build-in-public`) | Writes build-in-public drafts in `docs/bip/` from a roadmap item, a story, or a topic, with SVG diagrams and copy-ready text. It does not post. `published <id> <links>` records the publication. |
| `k3d-verify` | You only | Placeholder. It will run `local-test.sh` on k3d. It does nothing yet. |

## Agents

The agents are in `../agents/`. The workers use Sonnet at medium effort, so the implementation costs less than the planning. The reviewer uses Opus at high effort, because it must find the errors of the workers.

| Agent | Purpose |
|---|---|
| `backend-dev` | Code and JUnit unit tests for one backend module. Starts no containers. |
| `integration-tester` | Testcontainers ITs for one backend module. It must run them. |
| `frontend-dev` | Frontend code, Vitest tests, and Playwright e2e tests. Reads `.claude/rules/frontend.md` and `DESIGN.md`. |
| `security-auditor` | Read-only audit of five security categories, adapted to the stack. It records the findings and the verified strengths in `findings.json`, and masks each secret. |
| `plan-reviewer` | Read-only verification of a plan's work. It runs the named tests, marks each plan item PASS, FAIL, or UNVERIFIED with evidence, finds behaviour defects, and writes `review.md`. |

## Rules for the workers

- A worker makes a maximum of two fix attempts, then reports to the executor.
- A worker changes an assertion only when it does not match a BR or a scenario of the plan. The report names the BR or the scenario.
- When the plan is wrong, the executor asks you. Then it updates `plan.md` and `checklist.md`, and logs the change under `## Changes during execution`.
- The executor does the tasks in `docs`, `infra`, `seed`, the OpenAPI files, `CONTEXT.md`, and `.claude/rules` itself. It skips `docs/bip`: use `/build-in-public` for that content.
- Modules run in parallel only when they do not depend on each other and their `Files` lists do not overlap.

## Files in `planner`

- `PLAN-TEMPLATE.md`: the sections of `plan.md`.
- `CHECKLIST-TEMPLATE.md`: the sections of `checklist.md`.

## Atomic tasks

Each task in `plan.md` obeys these rules:

- It changes files in one module. It names the module in `Module` and the files in `Files`.
- It changes one behaviour, or one part of the data model.
- It fits in one short session.
- It names its test.
- The build and the tests pass after the task.
- It names the tasks that it depends on, and its `BR-n` and `S-n` IDs.

## Origin

`planner`, `design-interview`, and `domain-language` are changed copies of `grill-with-docs`, `grilling`, `domain-modeling`, and `to-spec` from [mattpocock/skills](https://github.com/mattpocock/skills).
