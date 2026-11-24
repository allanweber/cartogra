# AGENTS.md format

`AGENTS.md` is loaded in each session of each agent tool. Each line costs context on each turn, so it keeps only what the agent cannot find by looking.

## Put in

- One or two sentences: what the project is, and for whom.
- The unwritten rules from the interview: conventions, gotchas, and the reason behind each one.
- Pointers, each with the condition that tells when to read the target:
  - "For commands, modules, the dev server, and the forbidden actions, read `.claude/project.md`."
  - "Before you change backend code, read `.claude/rules/backend.md`." (one line for each rules file)
  - "For the domain terms, read `CONTEXT.md`" (or `CONTEXT-MAP.md` when the project has many contexts).
- The plan rule: "Each implementation plan is a folder `plans/NNN-<slug>/` with `plan.md` and `checklist.md`."

## Keep out

Facts that the environment already tells are a copy that goes stale. Point to them instead:

- Commands and scripts: they are in `.claude/project.md`, `package.json`, and the build files.
- The folder layout and the stack: the agent sees them in the files.
- Behaviour that the agent does by default, such as "write clean code".

## Shape

```md
# {Project name}

{What the project is, and for whom.}

## Rules
- {Rule}. {Reason.}

## Where to look
- {Condition}: read `{path}`.
```
