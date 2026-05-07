---
mode: 'agent'
description: 'Create a new Architecture Decision Record (ADR) from the project template'
---

You are working in the Cartogra monorepo. The full project rules are in `.github/copilot-instructions.md` — apply them to everything you generate.

Create a new Architecture Decision Record (ADR) following the project template.

**Usage:** provide `<title>` (e.g., `Use Kafka for async service communication`)

## Steps

1. **Parse arguments**: title (free text, will be converted to sentence case)

2. **Find the next ADR number**:
   - Look in `docs/adr/` for existing files named `NNNN-*.md`
   - Increment the highest number by 1 (zero-padded to 4 digits)
   - If no ADRs exist yet, start at `0001`

3. **Create file** at `docs/adr/<NNNN>-<kebab-case-title>.md`

4. **ADR template**:
   ```markdown
   # <NNNN>. <Title in Sentence Case>

   Date: <YYYY-MM-DD>

   Status: Proposed

   ## Context

   What is the issue that we're seeing that is motivating this decision or change?

   ## Decision

   What is the change that we're proposing and/or doing?

   ## Consequences

   What becomes easier or more difficult to do because of this change?

   ### Positive
   - ...

   ### Negative
   - ...

   ### Neutral
   - ...
   ```

5. **Valid status values**: `Proposed` | `Accepted` | `Deprecated` | `Superseded by [NNNN](NNNN-title.md)`

6. **Verify rules checklist:**
   - [ ] File in `docs/adr/` directory
   - [ ] Sequential number (no gaps)
   - [ ] Date is today's date
   - [ ] Status starts as `Proposed`
   - [ ] Context / Decision / Consequences sections all present
