---
name: review-plan
description: Verify the implemented work of a plan against plan.md and checklist.md with an independent agent, and review the code with the code-review skill.
---

Argument: the plan number, for example `/review-plan 034`.

The review has two parts:

- **Plan verification:** the `plan-reviewer` agent finds evidence for each task, test, BR, scenario, and checklist tick.
- **Code review:** the `code-review` skill reviews the diff on two axes, Standards and Spec.

You start both parts and give the result to the user. Do not review the code yourself, so that the review stays independent of the work in this session.

## 1. Prepare

1. Find the plan folder `plans/<NNN>-<slug>/`. Look in each worktree of `git worktree list`. If it does not exist, stop and tell the user.
2. Get the branch from the header of `plan.md`. Find the worktree of that branch. If the branch has no worktree, use the main checkout and tell the user which branch is checked out.
3. Use `origin/main` as the base ref. If the user gives a different base, use it.
4. Make sure that `git -C <worktree> diff <base>...HEAD` is not empty. If it is empty, stop and tell the user.

## 2. Start the plan verification

Send the `plan-reviewer` agent with the Agent tool. Give it the worktree path, the plan folder path, and the base ref. It runs in the background.

## 3. Run the code review

While the agent runs, call the Skill tool with `code-review`, and obey it with these inputs:

- The fixed point is the base ref.
- The spec is `plan.md`. Give its path to the Spec sub-agent, and tell it to examine `## Changes during execution` and `Out of scope` too.
- Run each git command in the worktree: `git -C <worktree> ...`. Give the worktree path to each sub-agent.
- The standards sources include `.claude/rules/*.md`, `DESIGN.md` when the frontend changed, and the `CONTEXT.md` of each changed service.

## 4. Record

1. When the `plan-reviewer` agent returns, read `review.md` in the plan folder.
2. Append the code-review report to `review.md` under `## Code review`, with its `### Standards` and `### Spec` sections. Do not merge or re-rank the findings.

## Result

Give the user:

- The verdict of the plan verification.
- The blockers and the major findings of the plan verification, each with its `path:line`.
- The plan items with the result FAIL or UNVERIFIED.
- The summary line of the code review: the number of findings on each axis and the worst finding on each axis.
- The path of `review.md`.

Then stop. Do not fix the findings, and do not change `checklist.md`. The user decides the next step. For example, the user can run `/execute <NNN>` again with the findings.
