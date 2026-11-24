# CONTEXT.md format

## Structure

```md
# {Context name}

{One or two sentences: what this context is and why it exists.}

## Language

**Order**:
{One or two sentences that define the term.}
_Avoid_: Purchase, transaction

**Invoice**:
A request for payment that is sent to a customer after delivery.
_Avoid_: Bill, payment request
```

## Rules

- **Select one term.** If many words have the same meaning, select the best word. Put the other words under `_Avoid_`.
- **Write short definitions.** Use one or two sentences. Define what the term is, not what it does.
- **Add only domain terms of this project.** Do not add general programming concepts (timeouts, error types, utility patterns).
- **Group the terms under subheadings** when natural groups occur. A flat list is correct for one small area.

## One context or many contexts

**One context:** one `CONTEXT.md` at the root of the repo.

**Many contexts:** a `CONTEXT-MAP.md` at the root lists the contexts, their location, and their relations:

```md
# Context map

## Contexts

- [Ordering](./services/ordering/CONTEXT.md): receives and tracks customer orders
- [Billing](./services/billing/CONTEXT.md): makes invoices and processes payments

## Relationships

- **Ordering → Billing**: Ordering sends `OrderPlaced` events. Billing consumes them and makes invoices.
```

Find the structure:

- If `CONTEXT-MAP.md` exists, read it to find the contexts.
- If only a root `CONTEXT.md` exists, the repo has one context.
- If neither exists, create a root `CONTEXT.md` when the first term is resolved.

If the repo has many contexts, find the context of the current topic. If it is not clear, ask the user.
