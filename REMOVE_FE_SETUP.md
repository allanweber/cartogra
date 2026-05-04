# Context

Phase 0 of the Cartogra implementation plan includes checklist item 15 (scaffold TanStack Start frontend shell) and item 16 (add frontend CI job). The CI job exists in `.github/workflows/ci.yml` and references `npm ci` and `frontend/package-lock.json` — but `frontend/` contains only a `.gitkeep` placeholder. The preferred package manager is **pnpm**, so the CI job must also be updated to use pnpm. This plan scaffolds the complete Phase 0 frontend shell using the official TanStack CLI with pnpm, then layers in shadcn/ui, the 5 nav route placeholders, the envelope-aware API client, and CI-compatible lint/test scripts.

## Goal

Use `pnpm dlx @tanstack/cli@latest create` to scaffold the base project, then configure ESLint + Prettier + Tailwind + shadcn/ui + vitest, wire up the 5 TanStack Router file-based route placeholders (Catalog, Graph, Contracts, Intelligence, Operations) with a persistent sidebar layout, and update `.github/workflows/ci.yml` to use pnpm.

## Step-by-Step Implementation

### Step 1 — Scaffold with the official TanStack CLI

Remove the placeholder, then run from the **repo root**:

```bash
rm frontend/.gitkeep
pnpm dlx @tanstack/cli@latest create frontend \
  --framework react \
  --add-ons tanstack-query \
  --package-manager pnpm
```

- `--package-manager pnpm` generates `pnpm-lock.yaml` (used by CI cache key).
- `--add-ons tanstack-query` wires TanStack Query out of the box.
- Resulting build scripts use **Vite**: `vite dev`, `vite build`, `vite preview`.

### Step 2 — Add missing scripts to package.json

The CLI scaffold does not include `lint` or `test` scripts. Add them to `frontend/package.json`:

```json
{
  "scripts": {
    "dev": "vite dev",
    "build": "vite build",
    "start": "node .output/server/index.mjs",
    "preview": "vite preview",
    "lint": "eslint src --ext .ts,.tsx --max-warnings 0",
    "test": "vitest run --passWithNoTests",
    "typecheck": "tsc --noEmit"
  }
}
```

### Step 3 — Add dev dependencies not included by the CLI

```bash
cd frontend
pnpm add -D \
  eslint \
  @typescript-eslint/parser \
  @typescript-eslint/eslint-plugin \
  eslint-plugin-react-hooks \
  prettier \
  vitest \
  @testing-library/react \
  @testing-library/jest-dom \
  jsdom
```

### Step 4 — ESLint + Prettier config files

Create `frontend/.eslintrc.cjs`:

```js
module.exports = {
  parser: '@typescript-eslint/parser',
  plugins: ['@typescript-eslint', 'react-hooks'],
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:react-hooks/recommended',
  ],
  ignorePatterns: ['routeTree.gen.ts', 'dist/', '.output/'],
}
```

Create `frontend/.prettierrc`:

```json
{ "semi": false, "singleQuote": true, "printWidth": 100 }
```

### Step 5 — Tailwind CSS

The TanStack CLI scaffold may or may not include Tailwind. If not present, add it:

```bash
pnpm add -D tailwindcss @tailwindcss/vite autoprefixer postcss
pnpm dlx tailwindcss init -p
```

Set `tailwind.config.ts` content paths:

```ts
content: ['./src/**/*.{ts,tsx}', './index.html']
```

Add to `src/styles/globals.css` (or equivalent entry CSS):

```css
@tailwind base;
@tailwind components;
@tailwind utilities;
```

### Step 6 — shadcn/ui

Run init from inside `frontend/`:

```bash
pnpm dlx shadcn@latest init
```

When prompted: style = default, base color = neutral (or match `DESIGN.md` tokens), CSS variables = yes.

Add initial shadcn components used in the shell:

```bash
pnpm dlx shadcn@latest add button badge separator
```

### Step 7 — Vitest config

In `vite.config.ts` (inside the `defineConfig` export), add:

```ts
test: {
  environment: 'jsdom',
  setupFiles: ['./src/test/setup.ts'],
  passWithNoTests: true,
}
```

Create `frontend/src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest'
```

### Step 8 — AppLayout with sidebar navigation

Create `frontend/src/components/AppLayout.tsx` (named export, no default export):

```tsx
import { Link } from '@tanstack/react-router'
import { LayoutDashboard, GitBranch, FileCheck2, Brain, Activity } from 'lucide-react'

const nav = [
  { label: 'Catalog',      to: '/catalog',      icon: LayoutDashboard },
  { label: 'Graph',        to: '/graph',         icon: GitBranch },
  { label: 'Contracts',    to: '/contracts',     icon: FileCheck2 },
  { label: 'Intelligence', to: '/intelligence',  icon: Brain },
  { label: 'Operations',   to: '/operations',    icon: Activity },
]

export function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex h-screen">
      <aside className="w-56 border-r flex flex-col gap-1 p-3">
        <span className="px-2 py-3 text-sm font-semibold tracking-tight">Cartogra</span>
        {nav.map(({ label, to, icon: Icon }) => (
          <Link
            key={to}
            to={to}
            className="flex items-center gap-2 rounded px-2 py-1.5 text-sm hover:bg-muted [&.active]:bg-muted [&.active]:font-medium"
          >
            <Icon size={15} />
            {label}
          </Link>
        ))}
      </aside>
      <main className="flex-1 overflow-auto p-6">{children}</main>
    </div>
  )
}
```

### Step 9 — Five route placeholders

Create each file under `frontend/src/routes/`: `catalog/index.tsx`, `graph/index.tsx`, `contracts/index.tsx`, `intelligence/index.tsx`, `operations/index.tsx`.

Pattern per file (example for catalog):

```tsx
import { createFileRoute } from '@tanstack/react-router'
import { AppLayout } from '@/components/AppLayout'

export const Route = createFileRoute('/catalog/')({
  component: CatalogPage,
})

function CatalogPage() {
  return (
    <AppLayout>
      <h1 className="text-2xl font-semibold">Catalog</h1>
      <p className="text-muted-foreground mt-2">Coming in Phase 1.</p>
    </AppLayout>
  )
}
```

Root `frontend/src/routes/index.tsx` redirects to `/catalog` via `beforeLoad: ({ navigate }) => navigate({ to: '/catalog' })`.

After adding routes, regenerate `routeTree.gen.ts`:

```bash
pnpm dlx @tanstack/router-cli generate
```

### Step 10 — Envelope-aware API client

Create `frontend/src/lib/api.ts`:

```ts
export class ApiError extends Error {
  constructor(public code: string, message: string, public traceId: string) {
    super(message)
    this.name = 'ApiError'
  }
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const base = import.meta.env.VITE_API_BASE_URL ?? ''
  const res = await fetch(`${base}/api${path}`, { credentials: 'include', ...init })
  const traceId = res.headers.get('X-Trace-Id') ?? 'unknown'
  const body = await res.json()
  if (!res.ok) {
    throw new ApiError(
      body.error?.code ?? 'UNKNOWN',
      body.error?.message ?? 'Request failed',
      traceId,
    )
  }
  return body.data as T
}
```

### Step 11 — Environment file

Create `frontend/.env.example`:

```dotenv
VITE_API_BASE_URL=http://localhost:8080
```

### Step 12 — Update the CI frontend job

Edit `.github/workflows/ci.yml` — replace the existing `frontend` job with:

```yaml
frontend:
  name: Frontend — Lint & Test
  runs-on: ubuntu-latest
  defaults:
    run:
      working-directory: frontend
  steps:
    - uses: actions/checkout@v4

    - uses: pnpm/action-setup@v4
      with:
        version: 10

    - uses: actions/setup-node@v4
      with:
        node-version: '22'
        cache: 'pnpm'
        cache-dependency-path: frontend/pnpm-lock.yaml

    - name: Install dependencies
      run: pnpm install --frozen-lockfile

    - name: Lint
      run: pnpm run lint

    - name: Test
      run: pnpm test
```

## Critical Files

| File | Action |
|------|--------|
| `frontend/package.json` | Generated by CLI, edited to add lint/test/typecheck scripts |
| `frontend/pnpm-lock.yaml` | Generated by `pnpm install` — required by CI cache |
| `frontend/.eslintrc.cjs` | Created manually after scaffold |
| `frontend/.prettierrc` | Created manually after scaffold |
| `frontend/vite.config.ts` | Generated by CLI, extended with vitest `test` block |
| `frontend/tailwind.config.ts` | Created if not included by CLI |
| `frontend/components.json` | Generated by `pnpm dlx shadcn@latest init` |
| `frontend/src/styles/globals.css` | Tailwind directives + shadcn CSS vars |
| `frontend/src/test/setup.ts` | vitest + testing-library setup |
| `frontend/src/components/AppLayout.tsx` | Sidebar shell |
| `frontend/src/lib/api.ts` | Envelope-aware `apiFetch` + `ApiError` |
| `frontend/src/routes/__root.tsx` | Root layout (CLI generated, may need adjustment) |
| `frontend/src/routes/index.tsx` | Redirect to `/catalog` |
| `frontend/src/routes/catalog/index.tsx` | Placeholder |
| `frontend/src/routes/graph/index.tsx` | Placeholder |
| `frontend/src/routes/contracts/index.tsx` | Placeholder |
| `frontend/src/routes/intelligence/index.tsx` | Placeholder |
| `frontend/src/routes/operations/index.tsx` | Placeholder |
| `frontend/.env.example` | API base URL env var |
| `.github/workflows/ci.yml` | Update frontend job: npm → pnpm |

## Verification

1. `pnpm dlx @tanstack/cli@latest create frontend --framework react --add-ons tanstack-query --package-manager pnpm` — scaffold succeeds
2. Add scripts, configs, and source files per steps 2–11 above
3. `pnpm run typecheck` — exits 0
4. `pnpm run lint` — exits 0
5. `pnpm test` — exits 0 (`passWithNoTests`)
6. `pnpm dev` — app serves on `http://localhost:3000`; sidebar shows all 5 links, each renders a placeholder page
7. Push to CI — frontend job passes: `pnpm install --frozen-lockfile` → `pnpm run lint` → `pnpm test`
