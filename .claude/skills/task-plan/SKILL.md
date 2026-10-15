---
name: task-plan
description: Plan a non-trivial implementation task (roadmap item, issue, feature, refactor) by exploring the codebase, grilling the user one question at a time until the design tree is fully resolved, then writing an implementation plan for approval. Use when the user says "task-plan", "plan this task", or wants a plan before coding.
---

Built-in plan mode + grilling. Explore → grill → plan → approval. NEVER write code, edit project files, or commit during this skill.

## 1. Enter plan mode

Call `EnterPlanMode` first. Plan mode supplies the plan file path and keeps the session read-only. If the user declines plan mode, keep going read-only anyway and write the plan into the scratchpad directory.

## 2. Explore (facts are your job)

- Read the task source: roadmap item in `docs/roadmap.md`, GitHub issue (`gh issue view <n> --comments`), or the user's description.
- Read `CONTEXT-MAP.md` → the relevant `CONTEXT.md`, the `.claude/rules/*.md` that apply, and relevant ADRs.
- Find existing patterns, the files likely to be touched, current tests, and the e2e setup (look for Playwright/Cypress config, don't assume one).
- Find the linked GitHub issue(s): `gh issue list --search "<keywords>"`.
- NEVER ask the user for a fact you could look up. Send parallel `Explore` sub-agents for broad searches. While one is running, only the questions that depend on its result wait; ask the rest now.

## 3. Grill: work the design tree

Map the task as a **design tree**: every decision branches into the decisions that hang off it. The **frontier** is every open decision whose prerequisites are already settled.

**Asking rules (non-negotiable):**
- Ask ONE question per `AskUserQuestion` call, then wait for the answer before the next. NEVER post a numbered markdown "round" of questions, even when there is a lot of researched context to share. Put that context in the question text and option descriptions.
- Put your recommended answer first, labelled `(Recommended)`, with a one-line rationale.
- Use `preview` for code, schema, or UI comparisons.
- After each answer, recompute the frontier. A question whose answer depends on another open question waits until that one is settled.
- Challenge weak answers. If an answer conflicts with the rules, an ADR, or something found while exploring, say so plainly before moving on.

**Mandatory branches.** Put these in the tree from the start, never as an afterthought:
1. **Implementation decisions**: data model / migrations, API contract, layering per `backend.md`, frontend per `frontend.md`, Kafka events, multi-tenancy, error codes.
2. **Acceptance criteria**: explicit and checkable, per task item. Draft them even when the roadmap has none.
3. **Unit/integration tests**: what each layer tests.
4. **E2E coverage**: which user flows need an e2e test, using the repo's existing framework.
5. **Branch name**: `feat/<name>` or `fix/<name>` per `infra.md`.
6. **Linked GitHub issue(s)**: the exact `#numbers`. Offer to create one if none exists. Don't create it without a yes.
7. **Out of scope**: what is explicitly deferred.

Grilling is done when the frontier is empty: every branch visited, nothing silently assumed. Give a short summary of the settled decisions and get the user's confirmation that you share an understanding before writing the plan.

## 4. Write the plan

The plan always lives in the project at `plans/NNN-<slug>.md`, where NNN is the next zero-padded number in `plans/`. Write it to the plan-mode file with the sections below. Then, before calling `ExitPlanMode`, ask the user to let you leave plan mode long enough to save the same content to `plans/NNN-<slug>.md`. If plan mode was declined, write straight to `plans/`. Sections:

```
# <Task title>
Issue: #<n>  ·  Branch: <branch>

## Context
Why this change, what problem it solves, constraints found.

## Decisions
Each settled decision + the chosen option + one-line rationale.

## Implementation steps
Ordered, file-level steps (path → change). Reuse existing utilities, and name them with paths.

## Acceptance criteria
- [ ] checkable criterion …

## Tests
Unit / integration / e2e: what is covered where.

## Verification
Exact commands to run (./gradlew build, frontend tests, e2e) and manual checks.

## Out of scope
```

## 5. Exit

Call `ExitPlanMode` for approval. NEVER ask "is this plan ok?" with AskUserQuestion, because ExitPlanMode is that request. After approval, implement only what the plan covers. Don't commit or create branches until the user says so for that specific change.
