import { useForm } from '@tanstack/react-form'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { X } from 'lucide-react'
import { toast } from 'sonner'
import { ApiError, apiFetch } from '#/lib/api'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Dialog, DialogContent, DialogTitle } from '#/components/ui/dialog'
import { Input } from '#/components/ui/input'
import { TagsInput } from '#/components/TagsInput'
import type { PageResult, RegistryService, RegistryTeam } from '#/lib/registry-types'

interface RegisterServiceDrawerProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

interface CreateServicePayload {
  name: string
  description: string | null
  teamId: string | null
  repositoryUrl: string | null
  techStack: string[]
  healthEndpoint: string | null
}

function Label({ children, required, optional }: { children: React.ReactNode; required?: boolean; optional?: boolean }) {
  return (
    <label className="flex items-center gap-1.5 text-sm font-medium text-foreground">
      {children}
      {required && <span className="text-destructive">*</span>}
      {optional && <span className="text-sm font-normal text-muted-foreground">optional</span>}
    </label>
  )
}

function Field({ children }: { children: React.ReactNode }) {
  return <div className="space-y-1.5">{children}</div>
}

export function RegisterServiceDrawer({ open, onOpenChange }: RegisterServiceDrawerProps) {
  const queryClient = useQueryClient()

  const { data: teamsPage } = useQuery({
    queryKey: ['teams'],
    queryFn: () => apiFetch<PageResult<RegistryTeam>>('/v1/registry/teams?limit=200'),
    enabled: open,
  })
  const teams = teamsPage?.items ?? []

  const mutation = useMutation({
    mutationFn: (payload: CreateServicePayload) =>
      apiFetch<RegistryService>('/v1/registry/services', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['services'] })
      queryClient.invalidateQueries({ queryKey: ['tech-stacks'] })
      toast.success('Service registered')
      onOpenChange(false)
    },
    onError: (err) => {
      toast.error(err.message, {
        description: err instanceof ApiError ? `trace: ${err.traceId}` : undefined,
      })
    },
  })

  const form = useForm({
    defaultValues: {
      name: '',
      description: '',
      teamId: '',
      repositoryUrl: '',
      healthEndpoint: '',
      techStack: [] as string[],
    },
    onSubmit: ({ value }) => {
      mutation.mutate({
        name: value.name,
        description: value.description || null,
        teamId: value.teamId || null,
        repositoryUrl: value.repositoryUrl || null,
        healthEndpoint: value.healthEndpoint || null,
        techStack: value.techStack,
      })
    },
  })

  useEffect(() => {
    if (open) return
    mutation.reset()
    form.reset()
  }, [open])

  function handleClose() {
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent
        className="gap-0 p-0 sm:max-w-[560px]"
        showCloseButton={false}
        onInteractOutside={(e) => e.preventDefault()}
      >
        <div className="border-b px-6 py-5">
          <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">Service Catalog</p>
          <div className="mt-1 flex items-start justify-between gap-4">
            <DialogTitle className="text-2xl font-bold">Register service</DialogTitle>
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={handleClose}
              className="mt-1 text-muted-foreground opacity-70 hover:opacity-100"
            >
              <X className="size-4" />
              <span className="sr-only">Close</span>
            </Button>
          </div>
        </div>

        <form
          className="flex flex-col"
          onSubmit={(e) => { e.preventDefault(); form.handleSubmit() }}
        >
          <div className="space-y-5 overflow-y-auto px-6 py-6" style={{ maxHeight: 'calc(100dvh - 220px)' }}>
            {mutation.error && (
              <Alert variant="destructive">
                <AlertDescription>
                  {mutation.error.message}
                  {mutation.error instanceof ApiError && ` (trace: ${mutation.error.traceId})`}
                </AlertDescription>
              </Alert>
            )}

            <form.Field name="name">
              {(field) => (
                <Field>
                  <Label required>Service name</Label>
                  <Input
                    value={field.state.value}
                    onChange={(e) => field.handleChange(e.target.value)}
                    onBlur={field.handleBlur}
                    placeholder="e.g. payments-api"
                    required
                    maxLength={255}
                  />
                </Field>
              )}
            </form.Field>

            <form.Field name="description">
              {(field) => (
                <Field>
                  <Label optional>Description</Label>
                  <textarea
                    value={field.state.value}
                    onChange={(e) => field.handleChange(e.target.value)}
                    onBlur={field.handleBlur}
                    placeholder="What does this service do?"
                    maxLength={1000}
                    rows={3}
                    className="flex w-full resize-none rounded-md border border-input bg-transparent px-3 py-2 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                  />
                </Field>
              )}
            </form.Field>

            <div className="grid grid-cols-2 gap-4">
              <form.Field name="teamId">
                {(field) => (
                  <Field>
                    <Label optional>Owner team</Label>
                    <select
                      value={field.state.value}
                      onChange={(e) => field.handleChange(e.target.value)}
                      className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                    >
                      <option value="">-- None --</option>
                      {teams.map((t) => (
                        <option key={t.id} value={t.id}>{t.name}</option>
                      ))}
                    </select>
                  </Field>
                )}
              </form.Field>

              <form.Field name="repositoryUrl">
                {(field) => (
                  <Field>
                    <Label optional>Repository URL</Label>
                    <Input
                      value={field.state.value}
                      onChange={(e) => field.handleChange(e.target.value)}
                      onBlur={field.handleBlur}
                      placeholder="https://github.com/org/repo"
                      type="url"
                      maxLength={2048}
                    />
                  </Field>
                )}
              </form.Field>
            </div>

            <form.Field name="healthEndpoint">
              {(field) => (
                <Field>
                  <Label optional>Health endpoint</Label>
                  <Input
                    value={field.state.value}
                    onChange={(e) => field.handleChange(e.target.value)}
                    onBlur={field.handleBlur}
                    placeholder="https://example.com/actuator/health"
                    type="url"
                    maxLength={2048}
                  />
                </Field>
              )}
            </form.Field>

            <form.Field name="techStack">
              {(field) => (
                <Field>
                  <Label optional>Tech stack</Label>
                  <TagsInput
                    value={field.state.value}
                    onChange={field.handleChange}
                    placeholder="Add technology (press Enter)"
                    maxTags={30}
                  />
                </Field>
              )}
            </form.Field>
          </div>

          <div className="flex justify-end gap-3 border-t px-6 py-4">
            <Button type="button" variant="outline" onClick={handleClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? 'Registering…' : 'Register service'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  )
}
