---
name: domain-language
description: Build and sharpen the domain model of a project. Use when you discuss domain terms, write or edit a CONTEXT.md glossary, or record or edit an ADR.
---

# Domain language

Build and sharpen the domain model of the project while you design. Challenge the terms, make edge-case scenarios, and write the glossary and the decisions when they become clear. If you only read `CONTEXT.md` for vocabulary, you do not need this skill.

## File structure

A repo with one context:

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

If a `CONTEXT-MAP.md` is at the root, the repo has more than one context. The map tells where each context is:

```
/
├── CONTEXT-MAP.md
├── docs/adr/                    ← system-wide decisions
└── services/
    ├── ordering/
    │   ├── CONTEXT.md
    │   └── docs/adr/            ← decisions for one context
    └── billing/
        └── CONTEXT.md
```

Create files only when you have content for them. Create the root `CONTEXT.md` when the first term is resolved. Create `docs/adr/` when the first ADR is necessary.

## During the session

### Challenge against the glossary

If the user uses a term that conflicts with `CONTEXT.md`, tell the user immediately. Example: "Your glossary defines 'cancellation' as X, but you mean Y. Which is correct?"

### Sharpen fuzzy language

If the user uses a vague or overloaded term, propose one precise canonical term. Example: "You say 'account'. Do you mean the Customer or the User? They are different."

### Make concrete scenarios

When you discuss relations between concepts, test them with specific scenarios. Make scenarios that find the edge cases and the boundaries between concepts.

### Compare with the code

When the user tells how something works, examine the code. If the code does not agree, tell the user. Example: "Your code cancels a full Order, but you said that partial cancellation is possible. Which is correct?"

### Update CONTEXT.md immediately

When a term is resolved, update `CONTEXT.md` at that time. Use the format in [CONTEXT-FORMAT.md](./CONTEXT-FORMAT.md).

`CONTEXT.md` contains only the domain language. Put implementation details and decisions in the plan or in an ADR.

### Offer an ADR only when necessary

Offer an ADR only when all three conditions are true:

1. **Hard to reverse**: a later change costs much.
2. **Surprising without context**: a future reader will ask "why did they do it this way?"
3. **A real trade-off**: there were real alternatives, and you selected one for specific reasons.

Use the format in [ADR-FORMAT.md](./ADR-FORMAT.md).
