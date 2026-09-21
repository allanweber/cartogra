# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT-MAP.md`** at the repo root: it points at one `CONTEXT.md` per context (each service, plus frontend). Read each one relevant to the topic.
- **`docs/adr/`**: read ADRs that touch the area you're about to work in. This repo keeps all ADRs at the root — there are no per-service `docs/adr/` directories, so system-wide and service-specific decisions live together, numbered sequentially (ADR-0028+, after ADR-0001–0021 were restored and 0022–0027 added since).

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill creates them lazily when terms or decisions actually get resolved.

## File structure

```
/
├── CONTEXT-MAP.md
├── docs/adr/                  ← all decisions, system-wide and per-service, root only
├── services/
│   ├── gateway/CONTEXT.md
│   ├── registry/CONTEXT.md
│   ├── ingestion/CONTEXT.md
│   ├── topology/CONTEXT.md
│   ├── contract/CONTEXT.md
│   └── intelligence/CONTEXT.md
└── frontend/
    └── CONTEXT.md
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in the relevant service's `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (...), but worth reopening because…_
