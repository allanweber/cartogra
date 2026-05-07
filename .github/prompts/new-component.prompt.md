---
mode: 'agent'
description: 'Create a React component with TanStack Query, shadcn/ui, and envelope parsing'
---

You are working in the Cartogra monorepo. The full project rules are in `.github/copilot-instructions.md` — apply them to everything you generate.

Create a new React component following Cartogra frontend conventions: TanStack Query for data, TanStack Table/TanStack Forms when needed, shadcn/ui + Tailwind for UI, and proper envelope parsing.

**Usage:** provide `<ComponentName> [api-endpoint]` (e.g., `ServiceCard /api/v1/services/{id}`)

## Steps

1. **Create the component file** at `frontend/src/components/<ComponentName>.tsx`

2. **Component with TanStack Query + shadcn + envelope parsing**:
   ```tsx
   import { useQuery } from '@tanstack/react-query';
   import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
   import { Skeleton } from '@/components/ui/skeleton';
   import { Alert, AlertDescription } from '@/components/ui/alert';
   import { apiFetch } from '@/lib/api';

   interface <ComponentName>Props {
     id: string;
   }

   export function <ComponentName>({ id }: <ComponentName>Props) {
     const { data, isLoading, error } = useQuery({
       queryKey: ['<resource>', id],
       queryFn: () => apiFetch<XyzResponse>(`/api/v1/<resource>/${id}`),
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

3. **Ensure `frontend/src/lib/api.ts` API client** parses the envelope:
   ```ts
   export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
     const res = await fetch(`/api${path}`, { credentials: 'include', ...init });
     const traceId = res.headers.get('X-Trace-Id') ?? 'unknown';
     const body = await res.json();
     if (!res.ok) throw new ApiError(body.error.code, body.error.message, traceId);
     return body.data as T; // always extract .data — NEVER assume flat response
   }
   ```

4. **For tabular data**, use TanStack Table:
   ```tsx
   import { useReactTable, getCoreRowModel, flexRender } from '@tanstack/react-table';
   ```

5. **For non-trivial forms**, use TanStack Forms:
   ```tsx
   import { useForm } from '@tanstack/react-form';
   ```

6. **Verify rules checklist:**
   - [ ] TanStack Query (`useQuery`) — NOT raw `fetch` outside TanStack
   - [ ] TanStack Table for tabular data views
   - [ ] TanStack Forms for non-trivial forms
   - [ ] shadcn/ui components — NOT MUI, Ant Design, Bootstrap
   - [ ] Tailwind CSS for styling — NEVER inline styles
   - [ ] Envelope parsed: `.data` on success, `.error` handled on failure
   - [ ] Loading state rendered (Skeleton)
   - [ ] Error state shown with message
   - [ ] Named export (not default)
