---
mode: 'agent'
description: 'Create a TanStack Start file-based route/page with AppLayout and TanStack Query'
---

You are working in the Cartogra monorepo. The full project rules are in `.github/copilot-instructions.md` — apply them to everything you generate.

Create a new TanStack Start page (file-based route) with sidebar layout, TanStack Query data loading, and proper envelope parsing.

**Usage:** provide `<PageName> <route-path>` (e.g., `ServiceDetails /services/$id`)

## Steps

1. **Create page file** at `frontend/src/routes/<route-segment>.tsx`
   - Route path `/services` → file `frontend/src/routes/services/index.tsx`
   - Route path `/services/$id` → file `frontend/src/routes/services/$id.tsx`

2. **Route template with AppLayout + TanStack Query**:
   ```tsx
   import { createFileRoute } from '@tanstack/react-router';
   import { useQuery } from '@tanstack/react-query';
   import { AppLayout } from '@/components/layout/AppLayout';
   import { Skeleton } from '@/components/ui/skeleton';
   import { Alert, AlertDescription } from '@/components/ui/alert';
   import { apiFetch } from '@/lib/api';

   export const Route = createFileRoute('/<route-path>')({
     component: <PageName>,
   });

   export function <PageName>() {
     const { id } = Route.useParams() as { id?: string };

     const { data, isLoading, error } = useQuery({
       queryKey: ['<resource>', id],
       queryFn: () => apiFetch<XyzResponse>(`/api/v1/<resource>/${id}`),
       enabled: !!id,
     });

     return (
       <AppLayout>
         <div className="flex flex-col gap-6 p-6">
           <div className="flex items-center justify-between">
             <h1 className="text-2xl font-semibold tracking-tight">
               {data?.name ?? <Skeleton className="h-8 w-48" />}
             </h1>
           </div>

           {isLoading && <Skeleton className="h-64 w-full" />}

           {error && (
             <Alert variant="destructive">
               <AlertDescription>{error.message}</AlertDescription>
             </Alert>
           )}

           {data && (
             <div>
               {/* page content */}
             </div>
           )}
         </div>
       </AppLayout>
     );
   }
   ```

3. **Add sidebar nav link** if this is a top-level feature in `frontend/src/components/layout/Sidebar.tsx`

4. **Verify rules checklist:**
   - [ ] Uses `createFileRoute` — NEVER `createBrowserRouter`
   - [ ] File under `frontend/src/routes/`
   - [ ] Wrapped with `AppLayout`
   - [ ] TanStack Query — NOT raw fetch
   - [ ] shadcn/ui + Tailwind — NOT MUI/Bootstrap
   - [ ] Envelope parsed: `.data` on success, `.error` handled
   - [ ] Named export (not default)
