---
description: Produce a full implementation plan for one or more Cartogra execution-checklist items and write it to .claude/plans/. Use when the user provides task lines from docs/execution-checklist.md (e.g. "1.28 [CODE] …") or says "plan task", "plan these tasks", "write a plan for".
---

# plan-tasks

Produce an implementation plan for checklist items and write it to `.claude/plans/`.

**Arguments**: one or more task lines copied verbatim from `docs/execution-checklist.md`.  
Multiple items = one branch, one plan.

---

## Step 1 — Parse

- Extract task IDs, category (`[CODE]` `[INFRA]` `[DOCS]` `[TEST]` `[BIP]`), description.
- Identify services: gateway · registry · topology · contract · intelligence · ingestion · frontend · shared.
- Flag: new DB tables · new REST endpoints · new Kafka topics · new env vars · ADR decisions · BIP output.
- Read relevant source files before planning: existing migrations, `build.gradle.kts`, `application.yml`, Postman collections.

## Step 2 — Output path

Filename: `plan-tasks-{first-id}-{last-id}.md` → `.claude/plans/{filename}`. Report path before writing.

## Step 3 — Write plan

Template in [plan-template.md](reference/plan-template.md). Every section required; `N/A — {reason}` when not applicable.

- Postman rules → [postman-rules.md](reference/postman-rules.md)
- BIP formats → [bip-format.md](reference/bip-format.md)

## Step 4 — Verify before saving

- [ ] Every task has a per-task subsection
- [ ] Flyway version read from existing files — not guessed
- [ ] Postman: one request per AC, or "no HTTP surface" stated
- [ ] PlantUML stubs: `@startuml`/`@enduml`, `title`, at least one block
- [ ] BIP: all 5 channels for `[BIP]` tasks, or N/A
- [ ] Env vars complete — no hardcoded values
- [ ] Rollback plan present for any migration
- [ ] "Checklist Items to Mark Done" lists every task ID
