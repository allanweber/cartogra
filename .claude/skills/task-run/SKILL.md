---
name: task-run
description: Run the full SDLC for one roadmap story, from the plan to a green PR and the production check (task-plan → task-implement → task-eval → PR → deploy watch).
disable-model-invocation: true
---

Orchestrator. It takes ONE story from `docs/roadmap.md` through the full SDLC. The user makes every design decision in `task-plan`. After the plan approval, the run continues alone until the PR is green.

```
/task-run               next available story
/task-run <N.M>         that story
/task-run resume <N.M>  continue a stopped run, fix PR review comments, or watch the deploy after the merge
```

## Authorization

The user started this skill by hand. That act authorizes these actions for this one story only: the claim on the issue, the worktree, the branch, the commits, the push, the PR, the replies on the PR, and the runs of `k3d-run.sh`. The authorization replaces the "no commit until I say so" memory and the last line of `task-plan` for this story. It does not carry over to the next story.

The guardrails stay:

- Use `.claude/skills/task-eval/k3d-run.sh` (which calls `infra/helm/local-test.sh`) as the only way to create containers.
- For the production cluster (`~/.kube/cartogra-demo`, cartogra.dev), only read. Do not merge the PR. Do not roll back a deploy. These actions stay with the user.
- Push to the story branch only. Do not use `--no-verify` or `--force`.
- Write conventional commits signed off with `git commit -s`, per `CONTRIBUTING.md`.

## State file

`<worktree>/build/task-run/state.md` holds the run. Update it at each phase change, before you start the next action. A new session reads it to continue.

```
story: 5.3   issue: #133   branch: feat/5.3-breaking-change-engine
worktree: .claude/worktrees/5.3   plan: plans/031-breaking-change-engine.md
phase: plan | implement | eval | ship | ci | pr-review | merged | deployed | stopped
round: 2   last-report: build/task-eval/5.3-report.md   pr: #240
notes: <one line for each blocker, decision or surprise>
```

## Notify

Send a `PushNotification` (one line, under 200 characters) only for these events:

- **Needs you**: a grilling question is open, the plan is ready for approval, scope drift was found, or the run stopped after 3 red rounds.
- **PR ready**: the PR is open and all required checks are green.
- **Lock wait**: `k3d-run.sh` prints `WAIT`. Include the branch that holds the lock.

## 1. Pick and claim

Run from the main checkout:

```sh
sh .claude/skills/task-run/claim.sh [<N.M>]
```

The script reads the GitHub issue as the source of truth, because the roadmap boxes can be wrong. It skips closed, taken, shipped, and blocked stories. It claims the first available story (assignee plus the `working` label) under a lock that all worktrees share. The last line is `CLAIMED <N.M> <issue> <slug>`. Exit code 3 means that no story is available: report the `SKIP` lines and stop. Keep the `STALE` lines for the report.

## 2. Worktree

```sh
git worktree add .claude/worktrees/<N.M> -b feat/<N.M>-<slug> origin/main
cd .claude/worktrees/<N.M> && (cd frontend && pnpm install --frozen-lockfile) && (cd seed/acme-fintech && pnpm install --frozen-lockfile)
```

Do all later steps inside the worktree. Write the state file (`phase: plan`).

## 3. Plan

Invoke the `task-plan` skill with the story `N.M`, issue `#<n>`, and branch `feat/<N.M>-<slug>`. The user answers the grilling questions and approves the plan. Notify **Needs you** when a question or the plan waits.

Done when `plans/NNN-<slug>.md` exists in the worktree and the user approved it with `ExitPlanMode`.

## 4. Implement and evaluate (loop)

Run each skill as a fresh subagent (`Agent`, general-purpose). Then the main session keeps only the plan, the state file, and the reports. Give the subagent the worktree path, the plan path, and the input. It invokes the skill and returns the skill's output.

1. Subagent: `task-implement` with the plan. In round 2 and later, give it the last red report.
2. If `task-implement` reports scope drift, notify **Needs you**, set `phase: stopped`, and wait for the user.
3. Subagent: `task-eval` with the plan. It writes `build/task-eval/<N.M>-report.md`, which is **green** or **red**.
4. If the report is red and the round is less than 3, increase the round and do step 1 again.

After 3 red rounds, set `phase: stopped`, notify **Needs you** with the open failures, and wait. A fourth round with no new information usually repeats the same fix.

## 5. Ship

1. Run `git fetch origin && git rebase origin/main`. A parallel run can use the same `plans/NNN` number. If `origin/main` or an open PR (`gh pr list --json files`) already has your `NNN`, rename the plan to the next free number and update the references to it.
2. Change the story's `- [ ]` to `- [x]` in `docs/roadmap.md`. Update its `*Acme test:*` line if the seed changed.
3. Commit in logical units: `feat(<scope>): … (N.M)`, with the plan in its own `docs(plans):` commit. The `(N.M)` suffix is how `claim.sh` finds shipped stories.
4. Run `git push -u origin feat/<N.M>-<slug>`.
5. Write the PR body with the `pr` skill. Add `Closes #<n>`, the plan link, the acceptance criteria with evidence, and the eval summary. Then run `gh pr create`.
6. CI: run `gh pr checks <pr> --watch`. If a check fails, read `gh run view <id> --log-failed`, fix the cause, run the checks of `task-eval` that cover it, commit, and push. The 3-round limit of step 4 applies here too.
7. When all required checks are green, run `gh issue edit <n> --remove-label working` (keep the assignee). Set `phase: pr-review` and notify **PR ready**.

## 6. Report and retro

Give the user:

- The PR URL and the CI state.
- The acceptance criteria, each with its evidence.
- Accepted risks from `/security-review`, and the `/code-review` findings answered with a reason.
- The `STALE` lines from step 1.
- The paths of the local test reports.
- **Retro**: each problem that the skills caused in this run (a wrong command, a check that failed with no relation to the code, a step that needed a user action that a skill could do), and a concrete edit to a `task-*` skill or a rule for each one. Write the retro in the state file as well. Make the edits only after the user says yes, in a separate `chore/` PR.

If the run stops before step 5, keep the claim when the branch has work that the user will continue. In other cases, release it: `gh issue edit <n> --remove-label working --remove-assignee @me`.

## Resume

`/task-run resume <N.M>`: `cd .claude/worktrees/<N.M>`, read the state file, and continue from its `phase`. Then do the first branch that applies:

- **Stopped or interrupted** (`plan`, `implement`, `eval`, `ship`, `ci`, `stopped`): continue that step. For `stopped`, first read the user's answer to the blocker.
- **PR review comments** (`pr-review` and the PR is open): read the unresolved review threads (`gh api graphql` on `pullRequest.reviewThreads`). If a comment changes the design (the data model, the API shape, a topic, or an acceptance criterion), ask the user. Give the other comments to `task-implement` as its input. Then run `task-eval`, commit, push, reply on each thread with the commit SHA, and notify **PR ready** again.
- **Merged** (the PR is merged): set `phase: merged`. Watch `gh run list --workflow images.yml --branch main` and then `deploy.yml` for the merge commit. Use `ScheduleWakeup` at about 300 s for each check. When the deploy succeeds, run `pnpm acme verify` against cartogra.dev (the steps are in the `reference-demo-acme-verify` memory). Report the result, set `phase: deployed`, and remove the worktree with `git worktree remove .claude/worktrees/<N.M>`. If the deploy or the verify fails, notify **Needs you** with the log. Do not roll back.

## Phase end

If `claim.sh` exits 3 and every story of the phase is done, tell the user: "Phase <N> has no open stories. Run `/task-gate`."
