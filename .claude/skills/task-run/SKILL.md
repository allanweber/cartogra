---
name: task-run
description: Run the full SDLC for the next open roadmap story, from the plan to a green PR (task-plan → task-implement → task-eval → PR).
disable-model-invocation: true
---

Orchestrator. It takes ONE story from `docs/roadmap.md` through the full SDLC and ends on a PR with green CI.

## Authorization

The user started this skill by hand. That act authorizes these actions for this one story only: the claim on the issue, the branch, the commits, the push, the PR, and the approved local runs. The authorization replaces the "no commit until I say so" memory and the last line of `task-plan` ("Don't commit or create branches…") for this story. It does not carry over to the next story or the next session.

The guardrails stay:

- Use `docker compose` (`infra/docker-compose`) and `sh infra/helm/local-test.sh` as the only ways to create containers. Use `cartogra-local` as the only k3d cluster.
- Do not change the production cluster (`~/.kube/cartogra-demo`, cartogra.dev). Read it only.
- Push to the story branch only. Do not push to `main`. Do not use `--no-verify`. Do not use `--force` on a shared branch.
- Write conventional commits signed off with `git commit -s`, per `CONTRIBUTING.md`.

## 1. Pick the story

The roadmap checkboxes are not reliable (5.2 shipped but stays `[ ]`). Make the GitHub issue the source of truth.

1. Find the current phase: the first `## ⬜` phase in `docs/roadmap.md`. Read its stories in the listed order. The listed order is the execution order, not the number order.
2. For each `- [ ] **N.M — …**` story, read its issue: `gh issue list --state all --search "[N.M] in:title" --json number,title,state,assignees,labels`.
3. Pick the first story that meets all of these conditions:
   - The issue is OPEN.
   - The issue has no assignee and no `working` label.
   - `git log origin/main --oneline` has no commit that ends in `(N.M)`.
   - Every story that it names with "Depends on" or "Runs before" is done.
4. Record each skipped story and the reason. If an issue is closed or a commit exists but the box is `[ ]`, record it as a **stale checkbox**. Do not fix stale checkboxes in this story's PR.

Done when one story is picked, or when no story is available. If no story is available, stop and report the skipped list.

## 2. Claim

1. Run `gh issue edit <n> --add-assignee @me --add-label working`.
2. Run `git fetch origin && git switch -c feat/<N.M>-<slug> origin/main`. The slug is 2–4 kebab words from the story title.

Do the claim before any other write. Other sessions read the `working` label as the lock.

## 3. Plan

Invoke the `task-plan` skill with the story `N.M`, issue `#<n>`, and branch `feat/<N.M>-<slug>`. The grilling and the approval need the user. Wait for them.

Done when `plans/NNN-<slug>.md` exists and the user approved it with `ExitPlanMode`.

## 4. Implement and evaluate (loop)

1. Invoke `task-implement` with the plan path.
2. Invoke `task-eval` with the plan path. It writes `build/task-eval/<N.M>-report.md` and ends **green** or **red**.
3. If the result is red, invoke `task-implement` again with the report as its input. Then do step 2 again.

Stop the loop after 3 red rounds. Then report the open failures to the user and wait. A fourth round with no new information usually repeats the same fix.

Done when `task-eval` is green (all local checks).

## 5. Ship

0. Run `git fetch origin && git rebase origin/main`. Other worktrees run `task-run` in parallel and can use the same `plans/NNN` number. If `origin/main` or an open PR (`gh pr list --json files`) already has your `NNN`, rename the file to the next free number. Also update the references to it.
1. Change the story's `- [ ]` to `- [x]` in `docs/roadmap.md`. Update its `*Acme test:*` line if the story changed the seed.
2. Commit in logical units: `feat(<scope>): … (N.M)`. Put the plan file in its own `docs(plans):` commit.
3. Push with `git push -u origin feat/<N.M>-<slug>`.
4. Write the PR body with the `pr` skill. Add `Closes #<n>`, a link to the plan, the acceptance-criteria checklist with evidence, and the eval report summary. Then run `gh pr create`.
5. Watch CI with `gh pr checks <pr> --watch`. If a check fails, read its log (`gh run view <id> --log-failed`), fix the cause, commit, and push. The 3-round limit of step 4 applies here too.
6. Remove the claim label: `gh issue edit <n> --remove-label working`. Keep the assignee.

Done when every required check is green: `Gitleaks`, `Backend — Build & Test`, `Frontend — Lint & Test`, `E2E — Playwright`, `Acme seed — Typecheck & Test`, and `Container — Build checks`.

Do not merge the PR. The user merges.

## 6. Report

Give the user:

- The PR URL and the CI state.
- The acceptance criteria, each with its evidence.
- Accepted risks from `/security-review`, and `/code-review` findings answered with a reason.
- The stale checkboxes from step 1.
- The paths of the local test reports.

If the run stops before step 5, release the claim: `gh issue edit <n> --remove-label working --remove-assignee @me`, unless the branch holds work that the user will continue.
