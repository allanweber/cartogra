---
name: task-eval
description: Evaluate a story branch against its plan and the definition of done (build, tests, e2e on k3d, docs drift, /code-review, /security-review, acceptance criteria), with a green/red report. Use after task-implement, when task-run calls it, or before a PR.
---

Gives a **green** or **red** verdict for the current branch, and evidence for each check. It does not fix code. A red report goes back to `task-implement`.

## Input

The plan `plans/NNN-<slug>.md`, with its "Acceptance criteria", "Tests", and "Verification" sections. The diff base is `origin/main`. Run from the worktree root.

## Running long commands

Run each long command through the Monitor tool. Use a filter for task starts, failures, and the result. Write the full output with `tee` to `<scratchpad>/task-eval/<check>.log`. The user must see the run, not only the result.

## Checks

Do all checks, also when an earlier check fails. A cheap failure must not hide a slow one. If a check cannot run (for example, the tool is missing or the port is in use), record it as **blocked** with the reason. A blocked check makes the verdict red.

Start check 5 first, because it can wait for the k3d lock. Do checks 1–4 and 6–10 while it runs or waits.

1. **Backend**: `./gradlew build --continue`.
2. **Frontend**: `cd frontend && pnpm lint && pnpm typecheck && pnpm test`.
3. **Seed**: `cd seed/acme-fintech && pnpm typecheck && pnpm test`.
4. **Plan verification**: run each command in the plan's "Verification" section that the checks above do not cover.
5. **k3d and E2E**: `sh .claude/skills/task-eval/k3d-run.sh`. The script holds the k3d lock that all worktrees share. It builds fresh images, runs `local-test.sh --e2e` (all k3d checks, then the Playwright suite against the k3d gateway and database, one report line for each spec file), and always tears down the cluster. If it prints `WAIT`, send a `PushNotification`: "<N.M> waits for k3d lock, held by <branch>". As a subagent, you are the only one who sees that line. When it ends, open the two `REPORT` paths that it prints with `xdg-open`. The result is the `K3D` line.
6. **Docs drift**: for each change in `git diff origin/main...`, the matching doc must change in the same diff:

   | Change in the diff | Doc that must change |
   |---|---|
   | A new or changed endpoint, request, response, or error code | `docs/api/<service>.openapi.yaml` |
   | A new or changed Kafka topic, event type, or payload | `docs/architecture/kafka-topics.md` |
   | A new or changed table or column | `docs/architecture/data-model.md` |
   | A new domain term, or a changed meaning of a term | `services/<svc>/CONTEXT.md`, and `CONTEXT-MAP.md` if the service status changed |
   | A decision that contradicts or amends an ADR | a new or amended file in `docs/adr/` |
   | A seed change (`seed/acme-fintech`) | the story's `*Acme test:*` line in `docs/roadmap.md` |

   Each missing doc is red. Give the file and the change that needs it.
7. **Code review**: invoke `/code-review` with the diff since `origin/main` and the plan as the spec. Each correctness finding is red, unless the finding is wrong. Write the reason for a wrong finding in the report. The PR will show that reason.
8. **Security review**: invoke `/security-review` on the same diff. Each finding above informational is red, unless you record it as an accepted risk with its compensating control.
8a. **k3d suite coverage**: `git diff origin/main... -- infra/helm/local-test.sh` must add the `step "Check <N.M> …"` block that the plan's "Tests" section names. The `build/local-test-report.html` of check 5 must show each of its checks as passed. A missing step or a check missing from the report is red. The exception is a plan that states why the story has nothing to check on k3d.
9. **Acceptance criteria**: for each `- [ ]` in the plan, give evidence: a test name, a command output, or a `file:line`. A criterion with no evidence is red.
10. **UI**: if the diff touches `frontend/src/routes` or `frontend/src/components`, run `/impeccable audit` on the changed screens. Record its findings. They do not make the verdict red, because the phase gate owns them.

## Report

Write `build/task-eval/<N.M>-report.md`:

```
# task-eval <N.M> — GREEN | RED   (round <n>)
| # | Check | Result | Evidence |
|---|-------|--------|----------|
| 1 | Backend build | pass/fail/blocked | log path, failing test names |
…
## Findings to fix
- <file:line> — <finding> — <source check>
## Answered findings
- <finding> — <reason it is wrong, or accepted risk + control>
## Acceptance criteria
- [x] <criterion> — <evidence>
## Skill problems
- <a problem in a task-* skill or script, not in the code> — for the task-run retro
```

The verdict is green only if checks 1–9 (8a included) pass. Done when the report exists and each check has a result.
