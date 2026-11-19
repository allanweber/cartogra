---
name: task-implement
description: Implement an approved plan from plans/NNN-*.md, test-first, step by step. Use after task-plan approval, when task-run calls it, or to fix the failures in a task-eval report.
---

Turns an approved plan into code and tests. It does not commit. `task-run` owns git.

## Input

- The plan: `plans/NNN-<slug>.md`. It is the scope. Implement each step of "Implementation steps" and nothing more.
- Optional: a red `task-eval` report (`build/task-eval/<N.M>-report.md`), or a list of PR review comments. If you get one, fix only its items, then stop.
- The worktree path. When `task-run` starts you as a subagent, work only in that worktree. The user does not see you, so report scope drift in your output and stop. Do not ask the user.

## Before the first edit

1. Read the plan, the `.claude/rules/*.md` files for each layer it touches, and the `CONTEXT.md` of each service that it touches (`CONTEXT-MAP.md` points to them).
2. Make sure that the branch is the one in the plan header. Switch to it if necessary.
3. Read the existing code that each step names. Reuse the utilities that the plan names. Each new file copies the shape of its closest sibling.

## Each plan step

1. Write the failing test first: a unit test for domain logic, a Testcontainers IT for repository, Kafka, or HTTP code, and a Playwright spec for a user flow in "Tests". Use the `tdd` skill for the red-green-refactor loop.
2. Write the code until the test is green. Run only that module's tests: `./gradlew :services:<svc>:test --tests '<Class>'`, or `cd frontend && pnpm exec vitest run <file>`.
3. Tick the step in your todo list.

Done with a step when its test passes and the code follows the rules files. Done with the skill when every step is ticked and each new migration, endpoint, topic, and screen in the plan exists.

## Scope drift

If a step needs work that the plan does not cover (a missing column, an event from a later story), stop and report it as **scope drift**, with the step and the missing item. Keep the scope of the plan. A gap that a later story covers stays for that story.

## Output

Give a short list of the changed files for each plan step, the tests added, and the deviations from the plan with the reason for each.
