---
name: ship
description: Run the full delivery of an approved plan - execute, review, security audit when needed, fix rounds with approved tasks, then commit, push, and PR at the gates of the user.
disable-model-invocation: true
---

Argument: the plan number, for example `/ship 034`. The plan must exist. Use `/planner` first.

You are the **conductor**. The other skills and their agents do the work. You read their files on disk, decide the next step, and stop at the gates. Do not write code, review code, or audit code yourself.

## State

Keep `ship-log.md` in the plan folder. After each step, add one line: the date, the round, the step, and the result. Example: `2026-11-24 · round 1 · review-plan · CHANGES REQUIRED, 2 major`.

At the start, read `ship-log.md` if it exists. Continue from the first step that has no line for the current round. The other skills continue from their own files: `checklist.md`, `review.md`, and `findings.json`.

## 1. Execute

1. Call the Skill tool with `execute` and the plan number. Obey it.
2. When `execute` reaches "Report and wait", record its report in `ship-log.md` and go to step 2. Do not commit. The commit is at gate 5.

If `execute` stops to ask the user about the plan, that question is part of this step. Continue when the user answers.

## 2. Review

1. Call the Skill tool with `review-plan` and the plan number. Obey it. It starts the `plan-reviewer` agent in the background, then runs `code-review`.
2. Make the decision about the security audit (below). If the result is yes, call the Skill tool with `security-audit` and the plan number. Obey it.
3. Before round 2, rename the files of round 1: `review.md` to `review-1.md`. Each round keeps its own report.

### Security audit decision

Run `security-audit` when the diff of the round touches auth, queries, or config. In round 1, the diff is `<base>...HEAD`. In a later round, the diff is the commits and the changes of the fix tasks only.

The diff touches **auth** when a changed file or line matches one of these:

- `SecurityConfig`, `*Filter*.java`, `Roles`, `Actor`, the gateway auth, API keys, JWT, cookies.
- `@PreAuthorize`, `SecurityContextHolder`, `@AuthenticationPrincipal`, `X-Tenant-Id`.
- The frontend auth: `routes/_authenticated`, the role gates, the login, the session.

The diff touches **queries** when a changed file or line matches one of these:

- `repository/`, `infrastructure/jdbc/`, `db/migration/`, `*.sql`.
- `NamedParameterJdbcTemplate`, `JdbcTemplate`, `@Query`, `POLICY`, `tenant_id`.

The diff touches **config** when a changed file matches one of these:

- `application*.yml`, `.env*`, `infra/**`, `docker-compose*.yml`, Helm `values*.yaml`, `.github/workflows/**`, `Dockerfile`.

Get the files with `git diff --name-only`, and the lines with `git diff -G '<pattern>'`. Record the decision and the matched file in `ship-log.md`. Example: `security-audit: yes, infrastructure/jdbc/JdbcContractRepository.java`.

## 3. Gate: fix tasks

Collect the **fix candidates**:

- From `review.md`: each blocker and each major finding, and each plan item with the result FAIL or UNVERIFIED.
- From `review.md`, the code-review part: each hard violation of a documented standard, and each Spec finding of a requirement that is missing or wrong.
- From `findings.json`: each critical and each high finding.

If there are no fix candidates, go to gate 4.

Else:

1. Write one fix task for each candidate, in the task format of `plan.md`: `Implements` (the BR, the scenario, or the finding ID), `Module`, `Files`, `Depends on`, `Change`, `Test`, and `Done when`. Put related candidates in one task when they change the same files.
2. Show the tasks to the user. Use one `AskUserQuestion` with `multiSelect`, and one option for each task. In each option, write the finding and the change in plain words.
3. Add only the approved tasks to `plan.md` and `checklist.md`. Under `## Changes during execution`, add a line for each task, with the date and the finding ID.
4. Record the tasks that the user did not approve in `ship-log.md`. Do not ask about them again.
5. If the user approved no task, go to gate 4.
6. Else, start the next round: go to step 1. `execute` continues from the first unticked task.

**Round limit:** run a maximum of 2 fix rounds. If round 3 has fix candidates, do not write tasks. Show the candidates to the user, and ask how to continue.

## 4. Gate: commit

Give the user:

- The tasks that are done, and the changed files by module.
- The verdict of each round, and the findings that are not fixed: the medium and low findings, the minor findings, the code-review judgement calls, and the tasks that the user did not approve.
- The paths of `review.md`, `review-1.md`, and the security PDFs.

Then wait. When the user says "commit", make one commit for each module, with Conventional Commit messages, as `execute` describes. Do not push.

## 5. Gate: push and PR

Ask the user if you can push the branch and open a pull request. If the user says yes:

1. Push the branch.
2. Call the Skill tool with `pr` to write the body. Link the issue from the header of `plan.md`.
3. Make the PR with `gh pr create`.
4. Watch `gh pr checks` live with the Monitor tool until all checks pass or one fails. If a check fails, show the failure and ask the user.

## 6. End

Tell the user that `/build-in-public <story>` can turn this work into a post. Then stop.
