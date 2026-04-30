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

Expected local frontend URL: `http://localhost:3000` (or the configured frontend port).

## Build

```bash
npm run build
```

## Preview Production Build

```bash
npm run preview
```

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
