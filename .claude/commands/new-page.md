You are working in the Cartogra monorepo. Read AGENTS.md for all project rules before proceeding.

Create a new TanStack Start page (file-based route) with sidebar layout, TanStack Query data loading, and proper envelope parsing.

Arguments: $ARGUMENTS
(Expected: `<PageName> <route-path>` — e.g., `/new-page ServiceDetails /services/:id`)

## Steps

1. **Parse arguments**: PageName (PascalCase), route path

2. **Create page file** at `frontend/src/routes/<route-segment>.tsx`

3. **Route template with layout + TanStack Query**:
   ```tsx
   import { createFileRoute } from '@tanstack/react-router';
   import { useQuery } from '@tanstack/react-query';
   import { AppLayout } from '@/components/layout/AppLayout';
   import { Skeleton } from '@/components/ui/skeleton';
   import { Alert, AlertDescription } from '@/components/ui/alert';
   import { api } from '@/lib/api';

   export const Route = createFileRoute('/<route-path>')({
     component: <PageName>,
   });

   export function <PageName>() {
     const { id } = Route.useParams() as { id?: string };

     const { data, isLoading, error } = useQuery({
       queryKey: ['<resource>', id],
       queryFn: () => api.get<XyzResponse>(`/api/v1/<resource>/${id}`),
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

4. **Ensure the file path matches TanStack Router route conventions** under `frontend/src/routes/`

5. **Add sidebar nav link** if needed in `frontend/src/components/layout/Sidebar.tsx`

6. **Verify rules checklist:**
   - [ ] Uses `AppLayout` (or equivalent sidebar wrapper)
   - [ ] TanStack Query — NOT raw fetch
   - [ ] shadcn/ui + Tailwind — NOT MUI/Bootstrap
    - [ ] Route created using `createFileRoute`
   - [ ] Envelope parsed: `.data` on success, `.error` handled
   - [ ] Named export
