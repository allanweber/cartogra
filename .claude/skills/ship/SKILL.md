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

### Rounds

- **Round 1** is the full run: execute, full review, full security audit.
- **Round 2** is the only fix round. It examines only the **delta**: the changes that the fix tasks made. It does not review the complete branch again.

### Snapshots

The work is not committed before gate 4, so `git diff` cannot show the delta. Use a snapshot commit of the working tree, with the untracked files, in a separate index:

```sh
idx=$(mktemp -u) && GIT_INDEX_FILE=$idx git add -A && \
  GIT_INDEX_FILE=$idx git commit-tree "$(GIT_INDEX_FILE=$idx git write-tree)" -p HEAD -m "ship snapshot" ; rm -f "$idx"
```

It prints the SHA of the snapshot. It does not change the branch, the index, or the files. Keep the SHA in a ref: `git update-ref refs/ship/<NNN>/<name> <sha>`. Record the ref in `ship-log.md`.

The delta of round 2 is `git diff refs/ship/<NNN>/round-1 refs/ship/<NNN>/round-2`.

## 1. Execute

1. Call the Skill tool with `execute` and the plan number. Obey it.
   - In round 2, tell `execute` that this is a fix round. It then runs only the checks of the modules that the fix tasks change.
2. When `execute` reaches "Report and wait", record its report in `ship-log.md` and go to step 2. Do not commit. The commit is at gate 4.

If `execute` stops to ask the user about the plan, that question is part of this step. Continue when the user answers.

## 2. Review

1. Make a snapshot, and keep it as `refs/ship/<NNN>/round-<N>`.
2. Call the Skill tool with `review-plan`. Obey it. It starts the `plan-reviewer` agent in the background, then runs `code-review`.
   - Round 1: the argument is the plan number, for example `034`.
   - Round 2: the argument is the plan number and the delta, for example `034 refs/ship/034/round-1..refs/ship/034/round-2`. Give the IDs of the fix tasks too.
3. Make the decision about the security audit (below). If the result is yes, call the Skill tool with `security-audit`, with the same argument as `review-plan`. Obey it. In round 2, it runs only the change review on the delta, and makes no PDF.
4. Before round 2, rename the files of round 1: `review.md` to `review-1.md`. Each round keeps its own report.

### Security audit decision

Run `security-audit` when the diff of the round touches auth, queries, or config. In round 1, the diff is `<base>...HEAD` and the changes that are not committed. In round 2, the diff is the delta.

The diff touches **auth** when a changed file or line matches one of these:

- `SecurityConfig`, `*Filter*.java`, `Roles`, `Actor`, the gateway auth, API keys, JWT, cookies.
- `@PreAuthorize`, `SecurityContextHolder`, `@AuthenticationPrincipal`, `X-Tenant-Id`.
- The frontend auth: `routes/_authenticated`, the role gates, the login, the session.

The diff touches **queries** when a changed file or line matches one of these:

- `repository/`, `infrastructure/jdbc/`, `db/migration/`, `*.sql`.
- `NamedParameterJdbcTemplate`, `JdbcTemplate`, `@Query`, `POLICY`, `tenant_id`.

The diff touches **config** when a changed file matches one of these:

- `application*.yml`, `.env*`, `infra/**`, `docker-compose*.yml`, Helm `values*.yaml`, `.github/workflows/**`, `Dockerfile`.

Get the files with `git diff --name-only <base> refs/ship/<NNN>/round-<N>` (round 1) or `git diff --name-only <delta>` (round 2). Get the lines with `git diff -G '<pattern>'` and the same refs. Record the decision and the matched file in `ship-log.md`. Example: `security-audit: yes, infrastructure/jdbc/JdbcContractRepository.java`.

## 3. Gate: fix tasks

Collect the **fix candidates**:

- From `review.md`: each blocker and each major finding, and each plan item with the result FAIL or UNVERIFIED.
- From `review.md`, the code-review part: each hard violation of a documented standard, and each Spec finding of a requirement that is missing or wrong.
- From `findings.json`: each critical and each high finding.

In round 2, a finding is a fix candidate only when one of these conditions is true:

- It is on a line that the delta changed.
- It is a fix task with the result FAIL or UNVERIFIED.
- It is an item with the result FAIL or UNVERIFIED in round 1 that is still not fixed.

Other findings of round 2 are not fix candidates. Put them in the list of open items for gate 4. Code that did not change in round 2 was reviewed in round 1.

If there are no fix candidates, go to gate 4.

Else:

1. Write one fix task for each candidate, in the task format of `plan.md`: `Implements` (the BR, the scenario, or the finding ID), `Module`, `Files`, `Depends on`, `Change`, `Test`, and `Done when`. Put related candidates in one task when they change the same files.
2. Show the tasks to the user. Use one `AskUserQuestion` with `multiSelect`, and one option for each task. In each option, write the finding and the change in plain words.
3. Add only the approved tasks to `plan.md` and `checklist.md`. Under `## Changes during execution`, add a line for each task, with the date and the finding ID.
4. Record the tasks that the user did not approve in `ship-log.md`. Do not ask about them again.
5. If the user approved no task, go to gate 4.
6. Else, start round 2: go to step 1. `execute` continues from the first unticked task.

**Round limit:** run a maximum of 1 fix round. If round 2 has fix candidates, do not write tasks. Show the candidates to the user, and ask how to continue. If the user approves more fixes, run them as round 3, with the same rules as round 2. The delta of round 3 starts at the snapshot of round 2.

## Full checks before gate 4

If the last round was a fix round, run each command of "Final checks" of `.claude/project.md` one time, in order. A fix round runs only the checks of its modules, so this step finds a break in a different module.

If a check fails, show the failure to the user and ask how to continue.

## 4. Gate: commit

Give the user:

- The tasks that are done, and the changed files by module.
- The verdict of each round, and the findings that are not fixed: the medium and low findings, the minor findings, the code-review judgement calls, and the tasks that the user did not approve.
- The paths of `review.md`, `review-1.md`, and the security PDFs.

Then wait. When the user says "commit", make one commit for each module, with Conventional Commit messages, as `execute` describes. Do not push.

After the commits, delete the snapshot refs: `git for-each-ref --format='%(refname)' refs/ship/<NNN>/ | xargs -r -n1 git update-ref -d`.

## 5. Gate: push and PR

Ask the user if you can push the branch and open a pull request. If the user says yes:

1. Push the branch.
2. Call the Skill tool with `pr` to write the body. Link the issue from the header of `plan.md`.
3. Make the PR with `gh pr create`.
4. Watch `gh pr checks` live with the Monitor tool until all checks pass or one fails. If a check fails, show the failure and ask the user.

## 6. End

Tell the user that `/build-in-public <story>` can turn this work into a post. Then stop.
