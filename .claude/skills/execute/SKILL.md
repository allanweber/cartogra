---
name: execute
description: Implement a plan folder from the planner with module workers, run the unit, integration, and e2e tests, then wait for the user to say "commit".
disable-model-invocation: true
---

Argument: the plan number, for example `/execute 034`. The plan is `plans/034-<slug>/plan.md` with `checklist.md` beside it.

You are the **orchestrator**. The workers write the code and the tests. You read the plan, send the work, judge the results, and talk to the user. Keep the code out of your context: read the worker reports, not the full diffs.

## 1. Prepare

1. Find the plan folder. If it does not exist, stop and tell the user.
2. Read `plan.md` and `checklist.md`. Get the branch name from the header of `plan.md`.
3. Find the story worktree in `git worktree list`. If it does not exist, create `.claude/worktrees/<N.M>` on the branch, from `origin/main`. Run `pnpm install` in `frontend` and `seed/acme-fintech`.
4. Do all the next steps in the worktree.
5. **Resume.** If some lines in `checklist.md` are ticked, run the tests of the ticked tasks. If they pass, continue from the first unticked task. If they fail, go to step 4 (Failures).

## 2. Plan the workers

1. Group the unticked tasks by their `Module` field. One group is one **module**.
2. Order the modules from the `Depends on` fields of their tasks.
3. Two modules can run in parallel only when both conditions are true:
   - Neither module depends on the other.
   - Their `Files` lists do not overlap.

   Else, run them one after the other.

## 3. Run the workers

Send each worker with the Agent tool and its `subagent_type`. Give the worker:

- The worktree path, the plan path, and the task IDs of its module.
- The test names from `checklist.md` for those tasks.

| Module | Workers, in order |
|---|---|
| A backend service or a shared module | `backend-dev`, then `integration-tester` |
| `frontend` | `frontend-dev` |
| `docs`, `infra`, `seed`, OpenAPI files, `CONTEXT.md`, `.claude/rules` | You do these tasks yourself |
| `docs/bip` | Skip. Build-in-public content is not part of `/execute` |

For the tasks that you do yourself:

- Do them after the code tasks that they describe, so that the text matches the code.
- Run the check that the task names. Examples: `pnpm typecheck` and `pnpm test` in `seed/acme-fintech`, `helm lint` for a chart, `sh -n` for a shell script.
- Write a new `local-test.sh` step when the task asks for it, but do not run `local-test.sh`.

Before the first `frontend-dev` that has an e2e task, start the backend:

1. Make sure that all backend modules are complete.
2. If a process uses port `8080`, stop and ask the user. A process on that port is the bootRun of the user.
3. In the worktree, start `./gradlew bootRun` in the background. It uses the dev infra of Docker Compose and starts no containers.
4. Wait until `curl -sf http://localhost:8080/actuator/health` succeeds.

After each worker:

1. Read its report.
2. Examine that the change matches the tasks, the BRs, and the scenarios in the plan.
3. If the report lists an assertion change, examine that the named BR or scenario supports it.
4. Tick the lines in `checklist.md` for the tests that passed.

## 4. Failures

A worker makes a maximum of two fix attempts, then it reports a failure. Then do these steps:

1. Compare the plan with the code and the failed test.
2. If the code is wrong and the plan is clear, send a new worker with the failure and the plan text.
3. If the plan is wrong, unclear, or does not agree with the code, ask the user. Use `AskUserQuestion` with one plain example.

When the user decides a fix to the plan:

1. Update `plan.md` and `checklist.md`.
2. Add a line under `## Changes during execution` at the end of `plan.md`, with the date, the change, and the reason.
3. Continue.

## 5. Final checks

Run all these checks in the worktree:

1. `./gradlew build`. This runs the unit tests and the Testcontainers ITs.
2. In `frontend`: `pnpm lint && pnpm typecheck && pnpm test && pnpm build`.
3. In `frontend`: `pnpm test:e2e`. This is the full Playwright suite.

Stop the `bootRun` that you started. Tick the lines under `## Verification` in `checklist.md`.

If a check fails, go to step 4 (Failures).

## 6. Report and wait

The run is complete when each line of `checklist.md` is ticked and all the final checks pass.

Give the user:

- The tasks that are done, and the changed files grouped by module.
- Each assertion change and each change to the plan.
- The results of the final checks.

Then wait. Do not commit before the user says "commit". When the user says "commit", make one commit for each module, with Conventional Commit messages. Do not push.
