# Plan: Phase-wide audit — closing dead-end loops from task 1.38 through 5.Y

## Feature Summary

This is a **planning-only PR**. No service code, migrations, infrastructure, or runtime behavior changes. Its single goal is to make every open task from 1.38 through 5.Y land on a clear, dependency-ordered sequence with no dead ends — every Kafka producer has a named consumer task, every architectural ambiguity has an ADR scheduled before its dependent code task, and every discarded return value has an upstream consumer task.

A full cross-phase audit identified twelve places where the existing checklist either silently dropped output, left a downstream consumer unspecified, or specified an architectural shape that violates project constraints (e.g. a Spring filter living in `shared:common`, which is a zero-Spring module). For each, this PR adds either (a) a new checklist entry that fills the gap, or (b) an ADR draft that captures the decision needed to unblock the dependent task, or both.

The audit summary, with file-level evidence and the ordered remediation sequence, lives in the conversation that produced this plan; the durable artefacts shipped by this PR are the checklist edits and the ADR drafts.

---

## GitHub Workflow

### Issues

**Issue 1 — create new:**
- Title: `Audit — close phase 1–5 dead-end loops in execution-checklist`
- Acceptance criteria:
  - `docs/execution-checklist.md` contains 17 new task entries with stable IDs (1.38a, 1.39a, 1.39b, 1.39c, 1.39d, 1.39e, 1.39f, 1.39g, 1.66a, 1.67a, 2.19a, 2.33a, 2.35a, 3.24a, 3.29a, 5.3a, 5.W) inserted in dependency order, not appended.
  - 6 existing tasks are reworded to point at the new ADRs / decisions: 1.67, 2.20, 2.34, 2.35, 3.30, 5.Y.
  - Six new ADR drafts exist with status `Proposed`: ADR-0015 (CODEOWNERS persistence), ADR-0016 (OtelSpan ingestion), ADR-0017 (audit ownership), ADR-0018 (spec discovery transport), ADR-0019 (guest enforcement), ADR-0020 (`shared:web` module).
  - ADR-0014 has an appended `## Stale RUNNING jobs and the reaper` section.
  - `docs/adr/README.md` index includes all new ADRs.
- Milestone: `Phase 1 — Gateway MVP auth + Registry` (it gates Phase 1 close-out by clarifying 1.66, 1.67, 1.55, 1.39 follow-ups)

### Branch

`feat/audit-close-deadends`

Single branch for a single docs PR. No code changes, so no cross-service coordination risk.

### PR

- Title: `docs: close phase 1–5 dead-end loops (planning-only)`
- Body must include `Closes #<issue-1>`.
- Milestone: `Phase 1 — Gateway MVP auth + Registry`
- CI must be green (PlantUML render + markdown lint, if configured) before requesting review.

### CI / GitHub workflow changes

**None.** This PR is markdown-only — no service code, no Gradle changes, no Docker, no K8s. The existing `.github/workflows/ci.yml` already covers markdown changes (build runs unconditionally; Trivy and the JVM test gate are no-ops for a docs-only diff). No new workflow file is needed and no existing workflow file is modified.

### Commit and push approval

Per CLAUDE.md: never commit or push without Allan's explicit approval. After all artefacts land, show the diff summary + draft commit message and ask "OK to commit?" before the commit. After commit, confirm target branch + PR intent and ask "OK to push?" before the push.

---

## Dependencies & Sequencing

- **Requires**: nothing — the checklist itself is the only artefact being edited beyond the new ADR files.
- **Order within plan**: write plan file → checklist edits → ADR drafts → ADR README index → ADR-0014 addendum. All steps are independent at the file level; sequencing matters only for review clarity.
- **Unblocks**: every open code task downstream of an ambiguity. Specifically:
  - 1.39 (reaper) — ADR-0014 addendum tells the implementer what `ingestion.sync.stale-timeout` defaults to and what the failure-event payload looks like.
  - 1.67 (registry consumes `sync.completed`) — checklist now explicitly names the producer's topic `cartogra.ingestion.sync.completed` and adds `1.67a` for the prerequisite migration adding `last_synced_at`/`last_sync_status` columns to `scm_connections`.
  - 2.18 (topology graph builder) — checklist now flags the missing topology module as the actual gap, and 1.38b adds the integration test that catches `KafkaJsonSerializer` regressions before topology consumes.
  - 2.34/2.35 (audit events) — ADR-0017 picks registry as the owner and pins the port shape, so the implementer doesn't have to re-derive the architecture.
  - 2.20 (OtelSpanWorker) — ADR-0016 names the topic and the source.
  - 3.30 (spec discovery) — ADR-0018 names the topic and the payload shape.
  - 5.4 (guest enforcement) — ADR-0019 specifies the JWT-role-only enforcement path.
  - 5.Y (gateway service-token filter) — ADR-0020 introduces a `shared:web` Gradle module so the filter has a place to live without violating the `shared:common = zero Spring deps` rule.

---

## Per-Task Implementation

### Task A — Write this plan file

Already in flight (this document).

### Task B — Edit `docs/execution-checklist.md`

Two kinds of edits, both surgical:

1. **Insertions** — 17 new task entries placed by phase/sequence so the file stays chronological per the working rules at the top of the checklist. Each new entry follows the existing `- [ ] {id} [{TAG}] {description}` line format.
2. **Rewordings** — 6 existing entries (1.67, 2.20, 2.34, 2.35, 3.30, 5.Y) updated to reference the ADR or decision that resolves their ambiguity. No removals.

Order matters for review readability but not for correctness — the file is markdown and the IDs are stable.

### Task C — ADR drafts (six new, one addendum)

Each new ADR follows `docs/adr/TEMPLATE.md`. All ship with `Status: Proposed` so the user can review the recommendation before flipping to `Accepted`. The recommended decision in each ADR is the one identified in the audit's Section 3.

| ADR | Topic | Unblocks |
|-----|-------|----------|
| ADR-0015 | CODEOWNERS persistence shape (auto-assign vs. dedicated path-level table) | 1.39b–1.39f |
| ADR-0016 | OtelSpanWorker ingestion path (Kafka topic from OTel Collector) | 2.20 |
| ADR-0017 | Audit events owning service and `AuditEventPort` shape | 2.34, 2.35 |
| ADR-0018 | Spec discovery transport (Kafka topic from ingestion) | 3.30 |
| ADR-0019 | Guest enforcement mechanism (special JWT role, no anonymous path) | 5.4 |
| ADR-0020 | `shared:web` Gradle module for cross-service web filters | 5.Y |

ADR-0014 gets a `## Stale RUNNING jobs and the reaper` addendum so the failure mode is documented next to the idempotency guard it interacts with, rather than in a separate ADR.

### Task D — Update `docs/adr/README.md` index

Add six rows to the index table (one per new ADR), in numeric order.

---

## Verification

This PR ships docs only. The verification surface is:

1. `./gradlew build` runs unchanged (nothing depends on markdown). Build green = no regression.
2. Markdown render of the new ADRs and the checklist diff is reviewable on GitHub.
3. The new task IDs in the checklist do not collide with existing IDs — verified by grepping the file before commit.
4. The new ADR numbers (`ADR-0015` through `ADR-0020`) are not yet taken — confirmed against the current `docs/adr/README.md` index (last in use: ADR-0014).

No code paths exercised, no Testcontainers boot, no Kafka activity.

---

## PlantUML diagrams

This PR introduces no new flow, schema, or domain model — every diagrammable change lives in future code tasks (1.39d/e, 2.20, 2.35a, 3.30, 5.Y) where the diagram is part of that task's done-definition per `.claude/rules/workflow.md`. None are due in this PR.

---

## BIP

No BIP channel applicable. This is an internal planning PR.
