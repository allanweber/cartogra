---
name: planner
description: Interview the user about a feature, then write a plan folder with business rules, BDD scenarios, a data model, atomic tasks, and a test checklist.
disable-model-invocation: true
---

## 1. Interview

Call the Skill tool two times, for `design-interview` and `domain-language`. Obey both skills until the user confirms the shared understanding.

## 2. Find the plan number

1. Run `git worktree list`.
2. In the `plans/` folder of each worktree, find the highest `NNN` prefix. Count files and folders.
3. Add 1 to the highest number. Use three digits, for example `034`.

## 3. Write the plan folder

Create `plans/NNN-<slug>/` with two files:

- `plan.md`: use [PLAN-TEMPLATE.md](./PLAN-TEMPLATE.md).
- `checklist.md`: use [CHECKLIST-TEMPLATE.md](./CHECKLIST-TEMPLATE.md).

Write only what the interview decided and what you found in the code. Do not interview the user again. Use the terms of `CONTEXT.md`, and obey the ADRs in the area that you change.

### Atomic tasks

Divide the work into **atomic tasks**. Each task obeys these rules:

- **One module.** The task changes files in one module only. Write the module path in `Module`, and each file in `Files`. The `execute` skill groups the tasks by module and uses `Files` to run modules in parallel.
- **No build-in-public content.** Do not add tasks for `docs/bip`. That content has its own skill.
- **One behaviour.** The task adds or changes one behaviour, or one part of the data model.
- **Small.** One agent can do the task in one short session.
- **Testable.** The task names its test. The test passes after the change.
- **Green.** After the task, the build and all the tests pass.
- **Ordered.** The task comes after each task that it depends on. Write the dependencies.
- **Traced.** The task names the business rules (`BR-n`) and the scenarios (`S-n`) that it implements.

Each business rule has a minimum of one scenario. Each scenario has a minimum of one task that tests it. If a rule or a scenario has no task, the plan is not complete.

## 4. Completion

The plan is complete when:

- Each mandatory branch of `design-interview` has a section in `plan.md`.
- Each `BR-n` has a scenario, and each `S-n` has a task.
- Each task in `plan.md` has one line in `checklist.md`.

Give the user the two file paths and a short summary. Then stop. The user decides when to start the implementation and when to commit.
