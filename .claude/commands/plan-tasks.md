You are working in the Cartogra monorepo. Read AGENTS.md for all project rules before proceeding.

Produce an execution plan for one or more checklist items from `docs/execution-checklist.md` and write it to a file.

Arguments: $ARGUMENTS
(Expected: one or more task lines copied verbatim from `docs/execution-checklist.md`, e.g.:
  `1.28 [CODE] Define registry/v1/registry.proto in shared:contracts ...`
  `1.29 [CODE] Implement RegistryGrpcService in services:registry ...`
Multiple items are treated as a single grouped feature delivered in one branch and PR.)

---

## Step 1 — Parse and understand the tasks

- Extract each task ID (e.g. `1.28`, `1.29`), category (`[CODE]`, `[INFRA]`, `[DOCS]`, `[TEST]`, `[BIP]`), and description.
- Identify the primary service(s) involved (gateway, registry, ingestion, topology, contract, intelligence, shared, frontend).
- Identify whether any item involves: new DB tables, new REST endpoints, new Kafka topics, new env vars, new ADR-worthy decisions, or BIP output.
- Read the relevant existing source files to understand current state before planning anything (check existing migrations, build.gradle.kts deps, application.yml, Bruno collections, etc.).

---

## Step 2 — Determine the output file path

- Slugify the task IDs: `plan-tasks-{first-id}-{last-id}.md` (e.g. `plan-tasks-1.28-1.31.md`).
- Write to `.claude/plans/{filename}`.
- Report the path to the user before writing.

---

## Step 3 — Produce the plan

Write the plan file using the structure below. Every section is required; if a section does not apply, write "N/A — {reason}" rather than omitting it.

---

### Plan file structure

```markdown
# Plan: Tasks {IDs} — {short title that names what these tasks collectively deliver}

## Feature Summary

Two or three sentences: what this group of tasks delivers, which service(s) it touches, and why it matters to the overall system. Name the user-visible or operator-visible outcome.

---

## GitHub Workflow

### Issues

For each task (or tight group of tasks), list whether an existing GitHub issue covers it or a new one must be created. Include:
- Title: `US{id} — {story title}`
- Acceptance criteria bullet points (pull from plan.md or derive from the checklist description)
- Milestone: `Phase N — {name}`

### Branch

feat/{first-id}-{short-slug}

One branch covers all tasks in this plan. Justify grouping or splitting if tasks span more than one service with no shared code path.

### PR

- Title: `feat: {short description} ({task IDs})`
- Body must include `Closes #<issue>` for every issue above.
- Milestone: same as issues.
- CI must be green (build + tests + diagrams render + OpenAPI validates) before requesting review.

### Commit and push approval

Per CLAUDE.md: never commit or push without Allan's explicit approval. Show diff summary + draft message and ask "OK to commit?" before every commit.

---

## Dependencies & Sequencing

List what must be true BEFORE these tasks can start (existing code, merged PRs, env vars, tool versions). Then list what these tasks UNBLOCK for future checklist items. If tasks within this plan must be done in order, number them explicitly.

Example:
- **Requires**: `shared:common` compiles; gateway Spring Cloud Gateway routing is wired
- **Order within plan**: 1.28 → 1.29 → 1.30 (SPI interface before provider impls)
- **Unblocks**: 1.32 (sync workers)

---

## Per-Task Implementation

One subsection per checklist item. Tasks that are tightly coupled (e.g. a proto definition and its server implementation) may share a subsection; note the coupling explicitly.

### Task {id} — {short title}

**What to build**: One paragraph describing the specific deliverable.

**Files to create or modify**:
| File | Action | Notes |
|------|--------|-------|
| `path/to/File.java` | New / Modify | Brief note |

**Key signatures** (only when the signature is an architectural contract other tasks depend on, or encodes a non-obvious constraint):
```java
// Omit entirely for straightforward CRUD — include only when it matters.
```

**Critical constraints** (rules from AGENTS.md / CLAUDE.md that apply specifically here):

- e.g. "RestClient must propagate `traceparent` and `X-Tenant-Id` on every outbound call"
- e.g. "Kafka consumer must extract traceparent from message headers before processing"

---

## Schema Changes

Only if any task involves new or modified DB tables.

For each new migration:
- **File**: `services/{service}/src/main/resources/db/migration/V{NNN}__{desc}.sql`
  - Find the next version number by reading existing migration files — do not guess.
- **Tables**: list table names, key columns, indexes, RLS policy, soft delete column.
- **ER diagram stub**: `docs/diagrams/{service}/{feature}-er.puml`

Provide a PlantUML `entity` block stub for the ER diagram.

---

## Env Vars Delta

Only if any task introduces new configuration.

| Var | Service(s) | `.env.example` default | `application-dev.yml` value | Notes |
|-----|-----------|------------------------|------------------------------|-------|
| `REGISTRY_BASE_URL` | gateway | `http://localhost:8081` | `http://registry:8081` | Registry REST base URL |

Also note any `docker-compose.yml` or `build.gradle.kts` changes needed to wire the new vars.

---

## Acceptance Criteria

Testable, one per line, phrased as observable outcomes (not implementation steps). These become the names of Bruno test requests and integration test method names.

- AC-1: {observable outcome}
- AC-2: {observable outcome}

Group by task ID when multiple tasks are present.

---

## Test Strategy

**Unit tests** (no containers):
- What to unit-test and what to mock.

**Integration tests** (Testcontainers):
- Which containers are needed (Postgres, Redis, Kafka, WireMock, etc.).
- Key scenarios per IT class, referencing AC numbers above.

**What NOT to test here** (covered by a later checklist item or a different layer):
- e.g. "UI flows — covered by 1.46"

---

## New Error Codes

Only if any task introduces new failure modes that need stable codes in `shared/common/src/main/java/io/cartogra/common/api/ErrorCodes.java`.

| Constant | HTTP Status | When emitted |
|----------|-------------|--------------|
| `REGISTRY_UNAVAILABLE` | 503 | Registry REST call returns a non-2xx status |

If none, write "N/A — no new error codes required."

---

## Postman Collection

**Service**: {service name}
**Collection file**: `postman/{service}.postman_collection.json`
**Action**: Extend existing | Create new — state which and why.

If creating a new collection, follow the Postman v2.1.0 schema: `info` block with `schema` URL, top-level `item` array for folders, and a `variable` array containing only the service-specific base URL variable (e.g. `registry-url`). All other variables live in `postman/cartogra-local.postman_environment.json` — never duplicate them in the collection.

### New folders and requests

One folder per GitHub issue (or per task ID if no issue exists yet). Folder name matches the issue slug or `task-{id}`. Each request item covers exactly one acceptance criterion.

Provide full Postman request JSON for every request. Rules:
- `name`: `AC-{N} — {AC title}`
- Use `{{<service>-url}}` (e.g. `{{gateway-url}}`, `{{registry-url}}`) — never hardcode base URLs.
- Use `{{authToken}}`, `{{tenantId}}` from the environment — never hardcode values.
- Capture created IDs via `pm.environment.set(...)` in the `test` event script when the AC involves a write that subsequent ACs depend on.
- Every test script must assert: correct HTTP status, `res.body.data` + `res.body.traceId` present (enveloped endpoints), `traceId` matches `/^[0-9a-f]{32}$/`, `x-trace-id` header equals body `traceId`.
- Internal-only tasks with no external HTTP surface: write "No Postman requests — verified by integration tests."

---

## Documentation

### PlantUML Diagrams

| File | Type | Trigger |
|------|------|---------|
| `docs/diagrams/{service}/{feature}-er.puml` | ER | new tables |
| `docs/diagrams/{service}/{feature}-class.puml` | Class | new domain model |
| `docs/diagrams/{service}/{feature}-sequence.puml` | Sequence | new use case or multi-step flow |

Provide a full syntactically valid `.puml` stub for each (title directive, participants/entities, happy-path flow at minimum).

### ADR

Only if the tasks encode an architectural decision not already captured. Check `docs/adr/` for the next number.

- File: `docs/adr/ADR-{NNNN}-{kebab-slug}.md`
- Sections: Context, Decision, Consequences (positive / negative / neutral).

If no ADR is needed: "N/A — existing ADR {ref} covers this decision."

### OpenAPI

Only if any task adds or changes HTTP endpoints.

- File: `docs/api/{service}.openapi.yaml`
- List each new path, method, request schema, response schema, security scheme changes, and new response headers.

---

## Rollback Plan

For tasks with schema migrations: steps to undo if the migration must be reverted mid-branch (drop table statements, how to restore the previous V-number, how to recreate DB locally).

For code-only tasks: "Revert the branch — no persistent state change."

---

## Verification Script

Ordered steps a developer runs after implementing to confirm the feature works end-to-end, before asking for commit approval. Reference Bruno AC request files by name.

1. `docker compose up` — confirm {service} healthy at `/actuator/health/ready`
2. Run `bruno/{service}/{folder}/01-{ac-slug}.bru` — expect {status}
3. ...

---

## BIP

Only if any task is tagged `[BIP]`, or if the feature produces a public artifact worth writing about. Otherwise: "N/A."

Draft file: `docs/bip/{task-id}-{slug}.md`

For each BIP item, produce all five channel drafts:

### Blog post (600–1000 words)
Full draft. Technical depth. Include key signatures or config snippets from the implementation. End with a takeaway or call to action.

### Twitter/X thread (4–8 tweets)
Tweet 1 is the hook. Subsequent tweets tell the story. Last tweet links to the blog or repo.

### Instagram carousel (5–8 slides)
Slide 1: hook visual. Slides 2–N: one concept per slide. Last slide: CTA. Caption: 150–200 words.

### LinkedIn post (300–500 words)
Professional framing. Ends with an engagement question.

### Video outline (optional)
5–10 min screencast or talking-head. Section timestamps. Note if it adds value or can be skipped.

---

## Files Created / Modified

| File | Action | Task |
|------|--------|------|
| `path/to/File.java` | New | 1.28 |
| `path/to/Other.java` | Modify | 1.29 |
| `docs/diagrams/....puml` | New | 1.28 |
| `bruno/....bru` | New | AC-1 |
| `docs/execution-checklist.md` | Mark done | 1.28, 1.29 |

---

## Checklist Items to Mark Done

After all work is implemented and verified:

- Change `[ ]` to `[x]` in `docs/execution-checklist.md` for tasks: {list each task ID}
```

---

## Step 4 — Verify before writing the file

- [ ] Every task from the arguments has a per-task subsection.
- [ ] Flyway version number was read from existing files — not guessed.
- [ ] Postman section has a request item for every AC, or explicitly states "no HTTP surface."
- [ ] All PlantUML stubs have `@startuml` / `@enduml` and at least a title and one block.
- [ ] BIP covers all 5 channels for every `[BIP]`-tagged task, or is marked N/A.
- [ ] Env vars delta is complete — no values hardcoded in the plan body.
- [ ] Rollback plan is non-empty for any task that touches a migration.
- [ ] "Checklist Items to Mark Done" lists every task ID from the arguments.
