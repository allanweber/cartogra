# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT-MAP.md`** at the repo root — it points at one `CONTEXT.md` per context. Read each one relevant to the topic.
- **`docs/adr/`** — read ADRs that touch the area you're about to work in. These are system-wide decisions.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The producer skill (`/grill-with-docs`) creates them lazily when terms or decisions actually get resolved.

## File structure

This is a multi-context monorepo. `CONTEXT-MAP.md` at the root points to per-service context files:

```
/
├── CONTEXT-MAP.md                         ← start here; maps topics to context files
├── docs/adr/                              ← system-wide architectural decisions
├── services/
│   ├── gateway/CONTEXT.md
│   ├── registry/CONTEXT.md
│   ├── topology/CONTEXT.md
│   ├── contract/CONTEXT.md
│   ├── intelligence/CONTEXT.md
│   └── ingestion/CONTEXT.md
├── frontend/CONTEXT.md
└── shared/
    └── common/CONTEXT.md
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in the relevant `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/grill-with-docs`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (local dev infrastructure) — but worth reopening because…_
