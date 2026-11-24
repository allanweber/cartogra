---
name: execute
description: Implement a plan folder from the planner with module workers, run the unit, integration, and e2e tests, then wait for the user to say "commit".
---

Argument: the plan number, for example `/execute 034`. The plan is `plans/034-<slug>/plan.md` with `checklist.md` beside it.

You are the **orchestrator**. The workers write the code and the tests. You read the plan, send the work, judge the results, and talk to the user. Keep the code out of your context: read the worker reports, not the full diffs.

## 1. Prepare

1. Find the plan folder. If it does not exist, stop and tell the user.
2. Read `plan.md` and `checklist.md`. Get the branch name from the header of `plan.md`.
3. Read `.claude/project.md`. It gives the modules, the commands, the dev server, and the final checks. If it does not exist, stop and tell the user to run `/project-setup`.
4. Find the story worktree in `git worktree list`. If it does not exist, create it in the "Worktrees" folder, on the branch, from `origin/main`. Run the "Setup" commands.
5. Do all the next steps in the worktree.
6. **Resume.** If some lines in `checklist.md` are ticked, run the tests of the ticked tasks. If they pass, continue from the first unticked task. If they fail, go to step 4 (Failures).

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

Use the "Modules" table of `.claude/project.md`:

| Kind | Workers, in order |
|---|---|
| `backend` | `backend-dev`, then `integration-tester` |
| `frontend` | `frontend-dev` |
| `other`, and `CONTEXT.md` or `.claude/rules` files | You do these tasks yourself |
| The "Drafts folder" of "Build in public" | Skip. Build-in-public content is not part of `/execute` |

If a task module is not in the table, ask the user which kind it is.

For the tasks that you do yourself:

- Do them after the code tasks that they describe, so that the text matches the code.
- Run the check that the task names. Examples: a typecheck and the tests of a package, `helm lint` for a chart, `sh -n` for a shell script.
- Obey the "Forbidden actions". Example: write a new step of a cluster test script when the task asks for it, but do not run the script when it is forbidden.

Before the first `frontend-dev` that has an e2e task, start the "Dev server":

1. Make sure that all backend modules are complete.
2. If a process uses the "Port", stop and ask the user. That process belongs to the user.
3. In the worktree, run the "Start" command in the background.
4. Wait until `curl -sf <Health URL>` succeeds.

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

Run each command of "Final checks" in the worktree, in order.

Stop the dev server that you started. Tick the lines under `## Verification` in `checklist.md`.

If a check fails, go to step 4 (Failures).

## 6. Report and wait

The run is complete when each line of `checklist.md` is ticked and all the final checks pass.

Give the user:

- The tasks that are done, and the changed files grouped by module.
- Each assertion change and each change to the plan.
- The results of the final checks.

Then wait. Do not commit before the user says "commit". When the user says "commit", make one commit for each module, with Conventional Commit messages. Do not push.
