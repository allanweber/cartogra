---
name: task-gate
description: Close a roadmap phase gate after its last story shipped (gate checklist, impeccable, improve, architecture review, demo) with one PR.
disable-model-invocation: true
---

Closes the gate of ONE roadmap phase. Start it when `task-run` reports that the phase has no open stories. It ends on a PR that checks the gate boxes and closes the gate issue.

## Authorization

The user started this skill by hand. That authorizes the branch, the commits, the push, and the PR for this gate. The guardrails of `task-run` apply: no merge, only reads on the production cluster, and no `--no-verify` or `--force`. Each triage decision in step 4 belongs to the user.

## 1. Find the gate

1. Find the current phase: the first `## ⬜` phase in `docs/roadmap.md`.
2. Find its gate issue: `gh issue list --label phase-gate --search "[Phase <N>] in:title"`.
3. Make sure that every story of the phase is shipped. Use `DRY_RUN=1 sh .claude/skills/task-run/claim.sh`: it must exit 3 with no `taken` or `depends-on` lines. If a story is open, report it and stop.
4. Run `git worktree add .claude/worktrees/gate-<N> -b docs/phase-<N>-gate origin/main` and work there.

## 2. Gate checklist

For each `- [ ]` under **Gate** in the phase, find evidence: a test name, an IT, a `pnpm acme verify` check, a command output, or a `file:line`. Run the command if the evidence is a command. A box with evidence becomes `[x]`. A box with no evidence stays `[ ]` and goes to the report.

## 3. Standing checks

Run each check over the scope of the phase. The scope is the services and the screens that the phase's story commits changed (`git log origin/main --grep "(<N>\." --name-only`).

1. `/impeccable audit` and `/impeccable critique` over each changed screen. If the phase touched no UI, record that fact.
2. `/improve` over the changed services.
3. `/improve-codebase-architecture` over the same scope. Install it first with the pinned command in `CONTRIBUTING.md` ("Gate tooling").
4. Make sure that each story PR of the phase has `/code-review` and `/security-review` results (from its `task-eval` report or its PR body).

## 4. Triage (needs the user)

Give the user each finding from step 3 as one `AskUserQuestion` with these options: **fix now**, **next phase**, **won't do**. Put your recommendation first. Then do these actions:

- **Fix now**: fix it on the gate branch, with a test.
- **Next phase**: create an issue with the label of the next phase. Name it in the gate record.
- **Won't do**: write the reason in the gate record. For an architectural finding, write an ADR (`docs/adr/TEMPLATE.md`) that says why not.

## 5. Demo

Follow the phase's **Demo** line step by step against the local k3d stack (`sh .claude/skills/task-eval/k3d-run.sh` holds the lock), or against cartogra.dev for read-only steps. Record each step and its result.

## 6. Ship

1. Write the gate record in the gate issue body or in `docs/` (use the same place as the previous gate, for example #130 for Phase 4).
2. Change the phase heading from `⬜` to `✅`, and check the gate boxes that have evidence.
3. Commit, push, and open a PR with `Closes #<gate-issue>`. Watch `gh pr checks --watch` until it is green. Then notify the user with `PushNotification`.
