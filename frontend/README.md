# Cartogra Frontend

TanStack Start (Router, Query, Form, Table), shadcn/ui and Tailwind. Routes, stores, the API client and the
conventions are in [`CONTEXT.md`](CONTEXT.md). Coding rules: [`.claude/rules/frontend.md`](../.claude/rules/frontend.md).

## Run

Start the backend first ([`docs/runbooks/local-development.md`](../docs/runbooks/local-development.md)). The dev
server sends `/api/*` to the gateway on `http://localhost:8080`.

```bash
cd frontend
pnpm install
pnpm dev            # http://localhost:3006
```

## Commands

| Command | Does |
|---|---|
| `pnpm test` | Unit tests (Vitest) |
| `pnpm test:e2e` | Playwright specs in `e2e/`. They write to the database of the stack that runs on :8080. |
| `pnpm lint` / `pnpm typecheck` / `pnpm check` | ESLint, `tsc`, Prettier check |
| `pnpm precommit` | Lint, types, tests and build. Run it before a push. |
| `pnpm build` | Production build. `server.mjs` serves it in the container. |

## UI design workflow (Impeccable)

`PRODUCT.md` (users, purpose, personality) and `DESIGN.md` (tokens: color, type, space) at the repo root are the
design context. The `impeccable` skill reads both.

1. Run `/impeccable shape <feature>` before code for a new screen or flow. Confirm the brief.
2. Run `/impeccable craft <feature>` to make the screen. Attach a screenshot if you have a reference.
3. Run `/impeccable audit <feature>` before the phase gate.

Each screen covers all states: loading (`Skeleton`), error (`Alert` with the `traceId`), empty and validation.
