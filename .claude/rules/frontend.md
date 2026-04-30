# Frontend Rules

## Tech Choices (Non-Negotiable)

| Concern | Use | NEVER use |
|---------|-----|-----------|
| Framework | TanStack Start | Next.js, Remix, custom SSR |
| Routing | TanStack Router (file-based) | React Router, reach/router, `createBrowserRouter` |
| Global state | Zustand | Redux, React Context for global state |
| Server state / data fetching | TanStack Query | SWR, Apollo, raw fetch |
| Tables | TanStack Table | AG Grid, MUI Data Grid, react-data-grid |
| Forms | TanStack Forms | Formik, Redux Form |
| UI components | shadcn/ui + Tailwind | MUI, Ant Design, Bootstrap |
| Dependency graph | D3 | Cytoscape, vis.js |
| Dashboards / charts | Recharts | Chart.js |

## File-Based Routing

- All routes live under `frontend/src/routes/` using `createFileRoute`
- NEVER use `createBrowserRouter` or any imperative router setup
- Wrap every page with `AppLayout`; register nav links in the sidebar

```tsx
export const Route = createFileRoute('/services')({
  component: ServicesPage,
})
```

## API Client & Envelope Parsing

The backend wraps ALL responses in `{"data": T, "traceId": "..."}` (success) or `{"error": {...}, "traceId": "..."}` (error).

Rules:

- ALWAYS extract `.data` from success responses — NEVER assume flat response shape
- ALWAYS handle `.error` for error responses
- Surface `X-Trace-Id` from response headers in user-visible error messages
- The API client wraps ALL calls; raw `fetch` NEVER appears in components

```ts
// api client pattern
async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, init)
  const traceId = res.headers.get('X-Trace-Id') ?? 'unknown'
  const body = await res.json()
  if (!res.ok) {
    throw new ApiError(body.error.code, body.error.message, traceId)
  }
  return body.data as T
}
```

## TanStack Query Conventions

- Use `useQuery` for reads, `useMutation` for writes
- Always provide `queryKey` arrays that include tenant-scoping identifiers
- Loading state: render shadcn `Skeleton` components
- Error state: render shadcn `Alert` with the `traceId` for support reference

```tsx
const { data, isLoading, error } = useQuery({
  queryKey: ['services'],
  queryFn: () => apiFetch<Service[]>('/registry/services'),
})

if (isLoading) return <ServicesSkeleton />
if (error) return <Alert variant="destructive">{error.message} (trace: {error.traceId})</Alert>
```

## Component Conventions

- Named exports only — NEVER default export a component
- One component per file; file name matches component name in `PascalCase`
- Co-locate query hooks with the component that owns the data
- Use shadcn/ui primitives (Card, Table, Button, Input, etc.) — NEVER raw HTML elements where a shadcn component exists
- Tailwind for all layout and spacing — NEVER inline styles
