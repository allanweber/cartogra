# Cartogra Frontend Setup (TanStack Stack)

## Stack

- TanStack Start: https://tanstack.com/start/latest
- TanStack Query: https://tanstack.com/query/latest
- TanStack Router: https://tanstack.com/router/latest
- TanStack Table: https://tanstack.com/table/latest
- TanStack Forms: https://tanstack.com/forms/latest
- shadcn/ui: https://ui.shadcn.com/

## Prerequisites

- Node.js 20+
- npm 10+ or pnpm 9+
- Cartogra backend services running locally through gateway (default `http://localhost:8080`)

## Install

From repository root:

```bash
cd frontend
npm install
```

If using pnpm:

```bash
cd frontend
pnpm install
```

## Required Frontend Dependencies

Install or verify these packages in `frontend/package.json`:

```bash
npm install @tanstack/react-query @tanstack/react-router @tanstack/react-table @tanstack/react-form zustand
```

For TanStack Start runtime/build integration, add the package and follow official setup docs:

```bash
npm install @tanstack/start
```

UI dependencies:

```bash
npx shadcn@latest init
```

## Environment

Create `frontend/.env.local`:

```env
VITE_API_BASE_URL=http://localhost:8080
```

## Run (Development)

```bash
npm run dev
```

Expected local frontend URL: `http://localhost:3006` (or the configured frontend port).

## Build

```bash
npm run build
```

## Preview Production Build

```bash
npm run preview
```

---

## UI Design Workflow (Impeccable)

All frontend features follow this design-first loop before writing production code.

### Context files

| File | Purpose | Edit when |
|------|---------|----------|
| `DESIGN.md` | Visual tokens — colors, typography, spacing, elevation | Design system changes |
| `PRODUCT.md` | Strategic register — users, purpose, personality, anti-references | Product positioning changes |

Both files live at the repository root. The `impeccable` skill reads them automatically on every run. Install with `npx impeccable install` (pinned version in [`CONTRIBUTING.md`](../CONTRIBUTING.md#gate-tooling)) — not yet installed in this repo; run it before using `/shape` or `/impeccable craft`.

### The loop: shape → craft

**Step 1 — Shape (discovery interview)**

Run `/shape <feature>` before writing any code for a new screen or significant UI flow. The shape interview asks structured questions about users, tasks, data, states, and constraints, then produces a design brief. Confirm the brief before moving to craft.

```
/shape authentication page
/shape catalog home
/shape dependency graph view
/shape contract matrix
/shape intelligence panel
```

**Step 2 — Provide a reference design (optional but recommended)**

Attach a screenshot or image in chat; the skill analyzes and implements it.

Images and `/shape` are complementary: use `/shape` to align on intent, then attach a screenshot to constrain the visual execution.

**Step 3 — Craft (implementation)**

After the brief is confirmed, run `/impeccable craft <feature>`. The skill:

1. Loads `DESIGN.md` and `PRODUCT.md` for token and strategy context
2. Inventories the visual reference (mockup, screenshot, or shape brief)
3. Emits a visual direction note (typography decisions, color application, spatial strategy)
4. Implements production-ready files using: TanStack Router file-based routes, TanStack Forms, TanStack Query, shadcn/ui components, Tailwind for all layout and spacing
5. Covers all required states: default, loading (shadcn `Skeleton`), error (`Alert` with `traceId`), field validation, submitting, empty, edge cases

```
/impeccable craft authentication page
/impeccable craft catalog home
/impeccable craft dependency graph view
```

**Step 4 — Polish and audit (as needed)**

```
/impeccable polish <feature>    # Final quality pass before shipping
/impeccable audit <feature>     # Accessibility, performance, anti-pattern check
/impeccable adapt <feature>     # Responsive / mobile pass
```

### File outputs

The craft step produces files in these locations:

```
frontend/src/routes/<feature>/      # TanStack Router file-based routes
frontend/src/components/<feature>/  # Named-export React components
```

Rule: **named exports only**, one component per file, file name matches component name in `PascalCase`. No default-exported components.

### Phase mapping

| Phase | Screens to craft before shipping |
|-------|-----------------------------------|
| **0** | App shell, root layout, error boundary, 404 |
| **1** | Login, Register, Forgot password, Verify email, Catalog home, SCM connections |
| **2** | Dependency graph (D3), blast radius panel, SPOF/cycle findings |
| **3** | Contract hub, compatibility matrix, CI check detail |
| **4** | Intelligence panel, NL query, anti-pattern feed, health score |
| **5** | Operations view, digest page, admin/settings |

Run `/shape` at the start of each phase's frontend workstream. Run `/impeccable craft` per screen. Run `/impeccable audit` before the phase gate.

## Test

```bash
npm run test
```

## Type Check

```bash
npm run typecheck
```

## Frontend Conventions

- Routing lives in `frontend/src/routes` (TanStack Router file-based routes).
- Server-state data access uses TanStack Query only.
- Tabular UIs use TanStack Table.
- Non-trivial forms use TanStack Forms.
- Shared UI primitives use shadcn/ui + Tailwind.
- API responses always parse Cartogra envelope `.data` and handle `.error` with `X-Trace-Id`.

## Typical Local Workflow

1. Start infrastructure and backend services.
2. Start frontend with `npm run dev` inside `frontend`.
3. Open the app and verify routes under the TanStack file-based route tree.
4. Build/test before pushing changes.
