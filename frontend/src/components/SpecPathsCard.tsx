import { useForm } from '@tanstack/react-form'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { toast } from 'sonner'

import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '#/components/ui/card'
import { Input } from '#/components/ui/input'
import { Label } from '#/components/ui/label'
import { Skeleton } from '#/components/ui/skeleton'
import { ApiError, apiFetch, apiMutate } from '#/lib/api'
import { useAuthStore } from '#/stores/useAuthStore'

export interface SpecPaths {
  defaults: string[]
  custom: string[]
}

export const SPEC_PATHS_QUERY_KEY = ['ingestion', 'spec-paths'] as const
const MAX_PATHS = 10
const MAX_PATH_LENGTH = 256
const GLOB_CHARACTERS = /[*?[\]{}]/

/** The same rules as the backend; the backend stays the authority and gives 400 for a bad list. */
export function specPathError(path: string, paths: SpecPaths): string | undefined {
  if (path.length === 0) return 'Enter a path'
  if (path.length > MAX_PATH_LENGTH) return `Use ${MAX_PATH_LENGTH} characters or fewer`
  if (path.startsWith('/')) return 'Use a path relative to the repository root'
  if (path.includes('..')) return "Do not use '..'"
  if (GLOB_CHARACTERS.test(path)) return 'Glob patterns are not supported'
  if (paths.defaults.includes(path)) return 'This path is already a default'
  if (paths.custom.includes(path)) return 'This path is already in the list'
  if (paths.custom.length >= MAX_PATHS) return `You can add ${MAX_PATHS} paths or fewer`
  return undefined
}

export function SpecPathsCard() {
  const queryClient = useQueryClient()
  const isAdmin = useAuthStore((s) => s.user?.roles.includes('ADMIN') ?? false)

  const { data, isLoading, error } = useQuery({
    queryKey: SPEC_PATHS_QUERY_KEY,
    queryFn: () => apiFetch<SpecPaths>('/v1/ingestion/spec-paths'),
  })

  const saveMutation = useMutation({
    mutationFn: (custom: string[]) => apiMutate<SpecPaths>('/v1/ingestion/spec-paths', { custom }, 'PUT'),
    onSuccess: (saved) => {
      queryClient.setQueryData(SPEC_PATHS_QUERY_KEY, saved)
      toast.success('Spec paths saved')
    },
    onError: (err: Error) => {
      toast.error(err.message, { description: err instanceof ApiError ? `Trace: ${err.traceId}` : undefined })
    },
  })

  const form = useForm({
    defaultValues: { path: '' },
    onSubmit: ({ value, formApi }) => {
      if (!data) return
      saveMutation.mutate([...data.custom, value.path.trim()], { onSuccess: () => formApi.reset() })
    },
  })

  return (
    <Card className="gap-3 rounded-lg py-4">
      <CardHeader className="px-5">
        <CardTitle className="text-sm font-semibold">Spec discovery</CardTitle>
        <CardDescription className="text-xs">
          Each sync looks for an OpenAPI file at these paths in each repository. Custom paths are tried
          first, then the defaults. The first file found becomes the next contract version.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3 px-5">
        {isLoading && (
          <div className="space-y-2">
            <Skeleton className="h-6 w-full" />
            <Skeleton className="h-6 w-2/3" />
          </div>
        )}

        {error && (
          <Alert variant="destructive">
            <AlertDescription>
              {error.message}
              {error instanceof ApiError && ` (trace: ${error.traceId})`}
            </AlertDescription>
          </Alert>
        )}

        {data && (
          <>
            <div className="space-y-1.5">
              <p className="text-xs font-medium text-muted-foreground">Defaults</p>
              <ul aria-label="Default spec paths" className="flex flex-wrap gap-1.5">
                {data.defaults.map((path) => (
                  <li key={path}>
                    <Badge variant="secondary" className="font-mono text-xs font-normal">{path}</Badge>
                  </li>
                ))}
              </ul>
            </div>

            <div className="space-y-1.5">
              <p className="text-xs font-medium text-muted-foreground">Custom</p>
              {data.custom.length === 0 ? (
                <p className="text-xs text-muted-foreground">No custom paths.</p>
              ) : (
                <ul aria-label="Custom spec paths" className="flex flex-wrap gap-1.5">
                  {data.custom.map((path) => (
                    <li key={path} className="flex items-center gap-0.5">
                      <Badge variant="outline" className="font-mono text-xs font-normal">{path}</Badge>
                      {isAdmin && (
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon-sm"
                          className="size-6"
                          aria-label={`Remove ${path}`}
                          disabled={saveMutation.isPending}
                          onClick={() => saveMutation.mutate(data.custom.filter((p) => p !== path))}
                        >
                          <X className="size-3.5" aria-hidden="true" />
                        </Button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {isAdmin ? (
              <form
                className="flex items-start gap-2"
                onSubmit={(e) => {
                  e.preventDefault()
                  form.handleSubmit()
                }}
              >
                <form.Field
                  name="path"
                  validators={{ onSubmit: ({ value }) => specPathError(value.trim(), data) }}
                >
                  {(field) => (
                    <div className="flex-1 space-y-1">
                      <Label htmlFor="spec-path" className="sr-only">Custom spec path</Label>
                      <Input
                        id="spec-path"
                        value={field.state.value}
                        onChange={(e) => field.handleChange(e.target.value)}
                        onBlur={field.handleBlur}
                        placeholder="src/main/resources/openapi.yml"
                        maxLength={MAX_PATH_LENGTH}
                        className="h-8 font-mono text-xs"
                        aria-invalid={field.state.meta.errors.length > 0}
                        aria-describedby={field.state.meta.errors.length > 0 ? 'spec-path-error' : undefined}
                      />
                      {field.state.meta.errors.length > 0 && (
                        <p id="spec-path-error" role="alert" className="text-xs text-destructive">
                          {field.state.meta.errors[0]}
                        </p>
                      )}
                    </div>
                  )}
                </form.Field>
                <Button type="submit" size="sm" disabled={saveMutation.isPending}>
                  Add path
                </Button>
              </form>
            ) : (
              <p className="text-xs text-muted-foreground">Only an admin can change the custom paths.</p>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}
