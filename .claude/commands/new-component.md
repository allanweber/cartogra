You are working in the Cartogra monorepo. Read AGENTS.md for all project rules before proceeding.

Create a new React component following Cartogra frontend conventions: TanStack Query for data, TanStack Table/TanStack Forms when needed, shadcn/ui + Tailwind for UI, and proper envelope parsing.

Arguments: $ARGUMENTS
(Expected: `<ComponentName> [api-endpoint]` — e.g., `/new-component ServiceCard /api/v1/services/{id}`)

## Steps

1. **Parse arguments**: ComponentName (PascalCase), optional API endpoint

2. **Create the component file** at `frontend/src/components/<ComponentName>.tsx`

3. **Component with TanStack Query + shadcn + envelope parsing**:
   ```tsx
   import { useQuery } from '@tanstack/react-query';
   import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
   import { Skeleton } from '@/components/ui/skeleton';
   import { Alert, AlertDescription } from '@/components/ui/alert';
   import { api } from '@/lib/api';

   interface <ComponentName>Props {
     id: string;
   }

   export function <ComponentName>({ id }: <ComponentName>Props) {
     const { data, isLoading, error } = useQuery({
       queryKey: ['<resource>', id],
       queryFn: () => api.get<XyzResponse>(`/api/v1/<resource>/${id}`),
     });

     if (isLoading) return <Skeleton className="h-32 w-full" />;

     if (error) {
       return (
         <Alert variant="destructive">
           <AlertDescription>{error.message}</AlertDescription>
         </Alert>
       );
     }

     return (
       <Card>
         <CardHeader>
           <CardTitle>{data?.name}</CardTitle>
         </CardHeader>
         <CardContent>
           {/* render data */}
         </CardContent>
       </Card>
     );
   }
   ```

4. **Ensure `frontend/src/lib/api.ts` API client** parses the envelope:
   ```ts
   async function get<T>(path: string): Promise<T> {
     const res = await fetch(path, { credentials: 'include' });
     const json = await res.json();
     if (!res.ok) {
       const traceId = res.headers.get('X-Trace-Id') ?? '';
       throw new Error(`${json.error?.message ?? 'Unknown error'} (trace: ${traceId})`);
     }
     return json.data as T; // always extract .data — NEVER assume flat response
   }
   ```

5. **Verify rules checklist:**
   - [ ] TanStack Query (`useQuery`) — NOT raw `fetch`/`axios` without TanStack
  - [ ] TanStack Table (`@tanstack/react-table`) for tabular data views
  - [ ] TanStack Forms (`@tanstack/react-form`) for non-trivial forms
   - [ ] shadcn/ui components — NOT MUI, Ant Design, Bootstrap
   - [ ] Tailwind CSS for styling
   - [ ] Envelope parsed: `.data` on success, `.error` handled on failure
   - [ ] Loading state rendered (Skeleton or spinner)
   - [ ] Error state shown to the user with message
   - [ ] Named export (not default) for easier refactoring
