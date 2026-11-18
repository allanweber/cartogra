---
name: task-eval
description: Evaluate a story branch against its plan and the definition of done (build, tests, e2e on k3d, /code-review, /security-review, acceptance criteria), with a green/red report. Use after task-implement, when task-run calls it, or before a PR.
---

Gives a **green** or **red** verdict for the current branch, and evidence for each check. It does not fix code. A red report goes back to `task-implement`.

## Input

The plan `plans/NNN-<slug>.md`, with its "Acceptance criteria", "Tests", and "Verification" sections. The diff base is `origin/main`.

## Running long commands

Run each long command through the Monitor tool. Use a filter for task starts, failures, and the result. Write the full output with `tee` to `<scratchpad>/task-eval/<check>.log`. The user must see the run, not only the result.

## Checks

Do all checks in this order, also when an earlier check fails. The one exception: while steps 5–7 wait for the k3d lock, do checks 8–11. A cheap failure must not hide a slow one. If a check cannot run (for example, the tool is missing or the port is in use), record it as **blocked** with the reason. A blocked check makes the verdict red.

1. **Backend**: `./gradlew build --continue`.
2. **Frontend**: `cd frontend && pnpm lint && pnpm typecheck && pnpm test`.
3. **Seed**: `cd seed/acme-fintech && pnpm typecheck && pnpm test`.
4. **Plan verification**: run each command in the plan's "Verification" section that the checks above do not cover.
5–7. **k3d stack, E2E, teardown**: run all three in ONE command under the k3d lock (see "k3d lock"):
   - `local-test.sh` does a fresh build and a fresh cluster. Do not use `--reuse` or `--no-build`, because the `cartogra-<svc>:k3d` images and the `cartogra-local` cluster can come from a different worktree. The script writes `build/local-test-report.html`.
   - E2E: `E2E_BASE_URL=http://localhost:8088 pnpm exec playwright test` in `frontend/`. The global setup uses the k3d stack, not the persistent stack on :8080. Before the run, do a check with `docker ps --filter name=cartogra-`. The Vite proxy is hardcoded to :8080, so the run must not reach the persistent stack. If you cannot prove that, record the check as blocked.
   - Teardown: `local-test.sh --down` runs also when the test or the E2E fails. Then open `build/local-test-report.html` and `frontend/playwright-report/index.html` for the user with `xdg-open`.

## k3d lock

All worktrees share one k3d cluster (`cartogra-local`, port 8088) and one Docker image tag (`:k3d`). Only one worktree at a time can hold steps 5–7. The lock file is in the git dir that all worktrees share, so every worktree sees the same lock.

Run steps 5–7 from the worktree root as one Monitor command:

```sh
LOCK="$(git rev-parse --path-format=absolute --git-common-dir)/cartogra-k3d.lock"
[ -s "$LOCK.owner" ] && echo "waiting for k3d lock, holder: $(cat "$LOCK.owner")"
flock "$LOCK" sh -c '
  echo "$(git branch --show-current) $(date -Is)" > "$0.owner"
  sh infra/helm/local-test.sh; t=$?
  (cd frontend && E2E_BASE_URL=http://localhost:8088 pnpm exec playwright test); e=$?
  sh infra/helm/local-test.sh --down
  : > "$0.owner"
  echo "local-test=$t e2e=$e"; [ $t -eq 0 ] && [ $e -eq 0 ]
' "$LOCK" 2>&1 | tee "<scratchpad>/task-eval/k3d.log"
```

- `flock` waits with no time limit, and it releases the lock when the shell exits, also after a crash. The lock never stays stuck.
- While the command waits, tell the user which branch holds the lock (from `$LOCK.owner`). Do other checks (8–11) in the meantime, then come back for the result.
- Put the docker-ps safety check before `flock`, or inside the locked shell before the E2E line.
8. **Code review**: invoke `/code-review` with the diff since `origin/main` and the plan as the spec. Each correctness finding is red, unless the finding is wrong. Write the reason for a wrong finding in the report. The PR will show that reason.
9. **Security review**: invoke `/security-review` on the same diff. Each finding above informational is red, unless you record it as an accepted risk with its compensating control.
10. **Acceptance criteria**: for each `- [ ]` in the plan, give evidence: a test name, a command output, or a `file:line`. A criterion with no evidence is red.
11. **UI**: if the diff touches `frontend/src/routes` or `frontend/src/components`, run `/impeccable audit` on the changed screens. Record its findings. They do not make the verdict red, because the phase gate owns them.

## Report

Write `build/task-eval/<N.M>-report.md`:

```
# task-eval <N.M> — GREEN | RED
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
```

The verdict is green only if checks 1–10 pass. Done when the report exists and each check has a result.
