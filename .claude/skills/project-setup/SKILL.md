---
name: project-setup
description: Fill .claude/project.md and AGENTS.md for a project, from the code first and from questions second. Run it again to update stale values.
disable-model-invocation: true
---

You set up a project for the other skills. The output is two files:

- `.claude/project.md`: the project facts, in the format of [PROJECT-TEMPLATE.md](./PROJECT-TEMPLATE.md).
- `AGENTS.md`: the instructions for each agent tool, in the format of [AGENTS-FORMAT.md](./AGENTS-FORMAT.md).

Facts are your job. Decisions belong to the user. Get each value from the code if you can. Ask the user only for a value that the code cannot tell.

## 1. Read what exists

1. Read `.claude/project.md`, `AGENTS.md`, `CLAUDE.md`, and each file in `.claude/rules/`, if they exist.
2. If `.claude/project.md` exists, you are in **update mode**. Keep each value that is still true. Change only the values that are missing or stale.

## 2. Find the facts

Fill each value of the template from the environment. For each value, write down its **source**: the file and the line, or the command output.

| Template section | Where to look |
|---|---|
| Product | `README.md`, `package.json` `name`, the git remote, the deploy config (Helm values, Dockerfiles, CI deploy jobs) for the hosts |
| Modules | `settings.gradle*`, `pom.xml` modules, `pnpm-workspace.yaml`, `package.json` workspaces, `go.work`, `Cargo.toml` workspace, the top folders |
| Worktrees | existing `git worktree list`, `.gitignore`, the install command of each package manager |
| Backend and frontend commands | `package.json` scripts, `build.gradle*` tasks, `Makefile`, `justfile`, CI workflow steps. CI steps show the real commands |
| Integration test marker | the base classes and the names of existing tests, Testcontainers or Docker use in tests |
| Dev server | the run script, the server port in the app config, the health endpoint, the e2e config (`baseURL`, `webServer`) |
| Final checks | the CI workflow that runs on pull requests |
| Forbidden actions | scripts that touch shared or production systems, and the existing `CLAUDE.md` rules |
| Build in public | a roadmap file, a folder of posts or drafts, its README |

Confirm each command: the script, the task, or the target must exist. Do not run slow commands.

The step is complete when each value of the template is **found** (with a source), **none** (with a source that shows that it does not apply), or **open**.

## 3. Ask the open values

Use the `design-interview` skill rules: one `AskUserQuestion` at a time, two or three plain sentences, one concrete example, and the recommended answer first.

1. Ask each open value.
2. Ask the decisions that the code cannot make:
   - The deployed hosts that agents must never call.
   - The forbidden actions.
   - Whether the project uses build in public.
3. Ask for the **unwritten rules**: the conventions, the gotchas, and the reasons behind choices that no file tells. Example: "Tests must never hit the production database, because the e2e proxy points to it." These go into `AGENTS.md`.

The step is complete when no value is open.

## 4. Show the draft

Show the user both files in full, before you write them. Under each value of `project.md` that you found, show its source in a short comment. In update mode, show only the changed lines as a diff.

Wait for the user to confirm or to correct the draft.

## 5. Write

1. Write `.claude/project.md`. Remove the source comments.
2. Write `AGENTS.md` (see [AGENTS-FORMAT.md](./AGENTS-FORMAT.md)). If it exists, merge into it: keep each line of the user that is still true.
3. Make sure that `CLAUDE.md` contains the line `@AGENTS.md`, so Claude Code reads the same file. If `CLAUDE.md` does not exist, create it with only that line. If `CLAUDE.md` has a rule that is now in `AGENTS.md`, ask the user before you remove it from `CLAUDE.md`.

## 6. Report

Give the user the paths of the changed files, the values that came from answers, and the values that came from the code. Then stop. Do not commit.
