# Frontend Shell Context

**Module**: `frontend/` · Port `3000` · Phase 1 · **Partial**

---

## Purpose

The Frontend Shell is Cartogra's single-page application. It renders the service catalog, dependency graph, contract hub, intelligence panel, and team management screens. It is a pure consumer of the Gateway's API — it never bypasses the Gateway to call downstream services directly.

---

## Ubiquitous Language

| Term | Meaning in this context |
|---|---|
| **Route** | A TanStack Router file-based route under `src/routes/`; maps 1:1 to a page |
| **Authenticated layout** | The `_authenticated` layout wrapper; enforces session before rendering any route beneath it |
| **Phase placeholder** | A stub page for features not yet implemented (Phase 2+); shows a "coming soon" indicator |
| **Session** | The current user's auth state, hydrated from the httpOnly JWT cookie via `fetchSession()` |
| **apiFetch** | The canonical API call helper; unwraps the `{ data, traceId }` envelope; throws `ApiError` on errors |
| **ApiError** | Typed error with `code`, `message`, and `traceId` for support reference |
| **Tenant context** | The `currentTenantId` from `useTenantStore`; injected as `X-Tenant-Id` header on every API call |
| **Mock data** | Static fixtures in `src/lib/mock-data.ts`; used while real API wiring is pending (task 1.64) |
| **Command palette** | Keyboard-driven search overlay (cmdk); accessible via `Ctrl+K` / `Cmd+K` |
| **Inspector panel** | The right-side panel on the Graph page; tabs react to node selection (Details, Blast Radius) plus one tenant-wide tab (Drift) that needs no selection |
| **Edge mode** | The Graph page's unified edge-visibility control: All / Declared only / Observed only / Drift only — replaces having separate declared-vs-observed and drift toggles |
| **Standing badge** | A node/edge visual marker (SPOF badge, cycle dashed-edge) that is always rendered when the underlying condition holds — never toggle-gated, unlike Edge mode |

Confirmed Phase 2 design brief: [docs/design/phase-2-dependency-graph.md](../docs/design/phase-2-dependency-graph.md).

---

## Tech Stack

| Concern | Library |
|---|---|
| Framework | TanStack Start (SSR) |
| Routing | TanStack Router (file-based) |
| Server state | TanStack Query |
| Forms | TanStack Forms |
| Tables | TanStack Table |
| Global state | Zustand |
| UI components | shadcn/ui + Tailwind CSS 4 |
| Icons | lucide-react |
| Graphs | D3 |
| Charts | Recharts |
| Testing | Vitest + React Testing Library + happy-dom |
| Fonts | Geist Variable (body) · JetBrains Mono (mono) |

---

## Routes

All authenticated routes are children of `_authenticated` which calls `fetchSession()` in `beforeLoad` and redirects to `/login?redirect=...` if unauthenticated.

| Route | Component / File | Status |
|---|---|---|
| `/` | Redirects to `/dashboard` | Live |
| `/login` | `routes/login.tsx` | Live |
| `/register` | `routes/register.tsx` | Live |
| `/verify-email` | `routes/verify-email.tsx` | Live |
| `/forgot-password` | `routes/forgot-password.tsx` | Live |
| `/reset-password` | `routes/reset-password.tsx` | Live |
| `/oauth-handoff` | `routes/oauth-handoff.tsx` | Live |
| `/_authenticated/dashboard` | `routes/_authenticated/dashboard.tsx` | Live (mock data) |
| `/_authenticated/catalog` | `routes/_authenticated/catalog.index.tsx` | Live (mock data) |
| `/_authenticated/catalog/$serviceId` | `routes/_authenticated/catalog.$serviceId.tsx` | Live (mock data) |
| `/_authenticated/graph` | Phase placeholder | Planned (Phase 2) — design brief confirmed, see below |
| `/_authenticated/contracts` | Phase placeholder | Planned (Phase 3) |
| `/_authenticated/risks` | Live (mock data) | Design brief confirmed for real API wiring (Phase 2) |
| `/_authenticated/intelligence` | Phase placeholder | Planned (Phase 4) |
| `/_authenticated/teams` | Phase placeholder | Planned (Phase 1+) |
| `/_authenticated/timeline` | Phase placeholder | Planned (Phase 1+) |
| `/_authenticated/ops` | Phase placeholder | Planned |
| `/_authenticated/settings` | Phase placeholder | Planned |

---

## Global State (Zustand Stores)

| Store | State | Purpose |
|---|---|---|
| `useAuthStore` | `user`, `isAuthenticated`, `isHydrated` | Current user identity; set on login/SSR hydration |
| `useTenantStore` | `currentTenantId` | Active tenant for all API calls |
| `useThemeStore` | `theme` (light/dark) | UI theme; persisted to localStorage |

---

## API Client (`src/lib/api.ts`)

```ts
apiFetch<T>(path, init?)  → Promise<T>        // unwraps .data; throws ApiError
apiMutate<T>(path, body, method?) → Promise<T> // convenience POST/PUT/PATCH wrapper
class ApiError { code, message, traceId }
```

All calls: `credentials: 'include'`, `X-Tenant-Id` from `useTenantStore`, `X-Trace-Id` extracted from response headers and attached to `ApiError`.

---

## Key Components

| Component | File | Purpose |
|---|---|---|
| `AppLayout` | `components/AppLayout.tsx` | Shell wrapper: sidebar nav, tenant switcher, theme toggle, command palette trigger, notification bell, sign-out |
| `AuthFormShell` | `components/AuthFormShell.tsx` | Shared card frame for all auth pages |
| `CommandPalette` | `components/CommandPalette.tsx` | `Ctrl+K` cmdk overlay |
| `NotificationBell` | `components/NotificationBell.tsx` | Notification indicator in header |
| `PhasePlaceholder` | `components/PhasePlaceholder.tsx` | "Coming in Phase N" stub for unbuilt routes |

shadcn/ui primitives in use: Alert, Badge, Button, Card, DropdownMenu, Input, ScrollArea, Separator, Sheet, Skeleton, Tooltip.

---

## Context Relationships

| Neighbour | Relationship | Notes |
|---|---|---|
| Identity & Access (Gateway) | Conformist | All API calls go to Gateway; cookies managed by Gateway |
| Service Catalog (Registry) | Conformist (via Gateway proxy) | `/api/v1/services`, `/api/v1/teams`, etc. |
| Topology | Conformist (Phase 2) | Will call `/api/v1/topology/**` for graph page |
| Contract | Conformist (Phase 3) | Will call `/api/v1/contracts/**` for contracts page |
| Intelligence | Conformist (Phase 4) | Will call `/api/v1/intelligence/**` for AI panel |

---

## Key Files

| Path | Role |
|---|---|
| `src/lib/api.ts` | `apiFetch`, `apiMutate`, `ApiError` |
| `src/lib/session.ts` | `fetchSession()` — SSR-safe session check |
| `src/lib/mock-data.ts` | Static fixtures for unfinished catalog wiring |
| `src/stores/useAuthStore.ts` | Auth Zustand store |
| `src/stores/useTenantStore.ts` | Tenant context store |
| `src/router.tsx` | TanStack Router root configuration |
| `src/routes/__root.tsx` | Root route + TanStack Query provider |
| `src/routes/_authenticated.tsx` | Auth guard layout |
| `vite.config.ts` | Vite + TanStack Start config |
| `package.json` | pnpm 10, Node 22, TypeScript 6 |
