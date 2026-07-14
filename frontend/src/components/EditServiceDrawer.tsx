import { useForm } from '@tanstack/react-form'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { X } from 'lucide-react'
import { toast } from 'sonner'
import { ApiError, apiFetch } from '#/lib/api'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '#/components/ui/dialog'
import { Input } from '#/components/ui/input'
import { TagsInput } from '#/components/TagsInput'
import type { PageResult, RegistryService, RegistryTeam } from '#/lib/registry-types'

interface EditServiceDrawerProps {
  service: RegistryService
  open: boolean
  onOpenChange: (open: boolean) => void
}

interface UpdateServicePayload {
  name: string
  description: string | null
  tier: string | null
  techStack: string[]
  tags: string[]
  slaTarget: number | null
  documentationUrl: string | null
  runbookUrl: string | null
  healthStatus: string | null
  repositoryUrl: string | null
  healthEndpoint: string | null
}

function Label({
  children,
  required,
  optional,
}: {
  children: React.ReactNode
  required?: boolean
  optional?: boolean
}) {
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

function NativeSelect({
  value,
  onChange,
  children,
}: {
  value: string
  onChange: (v: string) => void
  children: React.ReactNode
}) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
    >
      {children}
    </select>
  )
}

export function EditServiceDrawer({ service, open, onOpenChange }: EditServiceDrawerProps) {
  const queryClient = useQueryClient()

  const { data: teamsPage } = useQuery({
    queryKey: ['teams'],
    queryFn: () => apiFetch<PageResult<RegistryTeam>>('/v1/registry/teams?limit=200'),
    enabled: open,
  })
  const teams = teamsPage?.items ?? ([] as RegistryTeam[])

  const updateMutation = useMutation({
    mutationFn: (payload: UpdateServicePayload) =>
      apiFetch<RegistryService>(`/v1/registry/services/${service.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      }),
    onMutate: async (payload) => {
      await queryClient.cancelQueries({ queryKey: ['service', service.id] })
      const previous = queryClient.getQueryData<RegistryService>(['service', service.id])
      queryClient.setQueryData<RegistryService>(['service', service.id], (old) => {
        if (!old) return old
        return {
          ...old,
          name: payload.name,
          description: payload.description,
          tier: payload.tier as RegistryService['tier'],
          techStack: payload.techStack,
          tags: payload.tags,
          slaTarget: payload.slaTarget,
          documentationUrl: payload.documentationUrl,
          runbookUrl: payload.runbookUrl,
          healthStatus: (payload.healthStatus ?? old.healthStatus) as RegistryService['healthStatus'],
          repositoryUrl: payload.repositoryUrl,
          healthEndpoint: payload.healthEndpoint,
        }
      })
      return { previous }
    },
    onError: (_err, _payload, context) => {
      if (context?.previous) {
        queryClient.setQueryData(['service', service.id], context.previous)
      }
    },
    onSuccess: (updated) => {
      queryClient.setQueryData(['service', service.id], updated)
      queryClient.invalidateQueries({ queryKey: ['services'] })
    },
  })

  const assignOwnerMutation = useMutation({
    mutationFn: (teamId: string) =>
      apiFetch<RegistryService>(`/v1/registry/services/${service.id}/owner`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ teamId }),
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(['service', service.id], updated)
      queryClient.invalidateQueries({ queryKey: ['services'] })
    },
  })

  const form = useForm({
    defaultValues: {
      name: service.name,
      description: service.description ?? '',
      teamId: service.teamId ?? '',
      tier: service.tier ?? '',
      healthStatus: `${service.healthStatus}`,
      repositoryUrl: service.repositoryUrl ?? '',
      healthEndpoint: service.healthEndpoint ?? '',
      techStack: service.techStack ?? [],
      tags: service.tags ?? [],
      slaTarget: service.slaTarget != null ? String(service.slaTarget) : '',
      documentationUrl: service.documentationUrl ?? '',
      runbookUrl: service.runbookUrl ?? '',
    },
    onSubmit: async ({ value }) => {
      try {
        await updateMutation.mutateAsync({
          name: value.name,
          description: value.description || null,
          tier: value.tier || null,
          techStack: value.techStack,
          tags: value.tags,
          slaTarget: value.slaTarget ? parseFloat(value.slaTarget) : null,
          documentationUrl: value.documentationUrl || null,
          runbookUrl: value.runbookUrl || null,
          healthStatus: value.healthStatus !== '' ? value.healthStatus : null,
          repositoryUrl: value.repositoryUrl || null,
          healthEndpoint: value.healthEndpoint || null,
        })

        if (value.teamId && value.teamId !== service.teamId) {
          await assignOwnerMutation.mutateAsync(value.teamId)
        }

        toast.success('Service updated')
        onOpenChange(false)
      } catch (err) {
        // error surfaced via updateMutation.error / assignOwnerMutation.error
        toast.error(err instanceof Error ? err.message : 'Failed to save service', {
          description: err instanceof ApiError ? `trace: ${err.traceId}` : undefined,
        })
      }
    },
  })

  const isPending = updateMutation.isPending || assignOwnerMutation.isPending
  const submitError = updateMutation.error ?? assignOwnerMutation.error

  useEffect(() => {
    if (open) return
    updateMutation.reset()
    assignOwnerMutation.reset()
    form.reset({
      name: service.name,
      description: service.description ?? '',
      teamId: service.teamId ?? '',
      tier: service.tier ?? '',
      healthStatus: `${service.healthStatus}`,
      repositoryUrl: service.repositoryUrl ?? '',
      healthEndpoint: service.healthEndpoint ?? '',
      techStack: service.techStack ?? [],
      tags: service.tags ?? [],
      slaTarget: service.slaTarget != null ? String(service.slaTarget) : '',
      documentationUrl: service.documentationUrl ?? '',
      runbookUrl: service.runbookUrl ?? '',
    })
  }, [open])

  function handleClose() {
    if (isPending) return
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent
        className="gap-0 p-0 sm:max-w-140"
        showCloseButton={false}
        onInteractOutside={(e) => e.preventDefault()}
      >
        {/* Header */}
        <div className="border-b px-6 py-5">
          <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">
            Edit Service
          </p>
          <DialogDescription className="sr-only">
            Edit the details of this service.
          </DialogDescription>
          <div className="mt-1 flex items-start justify-between gap-4">
            <DialogTitle className="text-2xl font-bold">{service.name}</DialogTitle>
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

        {/* Body */}
        <form
          className="flex flex-col"
          onSubmit={(e) => {
            e.preventDefault()
            form.handleSubmit()
          }}
        >
          <div className="space-y-5 overflow-y-auto px-6 py-6" style={{ maxHeight: 'calc(100dvh - 220px)' }}>
            {submitError && (
              <Alert variant="destructive">
                <AlertDescription>
                  {submitError.message}
                  {submitError instanceof ApiError && ` (trace: ${submitError.traceId})`}
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
                    <NativeSelect value={field.state.value} onChange={field.handleChange}>
                      <option value="">-- None --</option>
                      {teams.map((t) => (
                        <option key={t.id} value={t.id}>
                          {t.name}
                        </option>
                      ))}
                    </NativeSelect>
                  </Field>
                )}
              </form.Field>

              <form.Field name="tier">
                {(field) => (
                  <Field>
                    <Label optional>Tier</Label>
                    <NativeSelect value={field.state.value} onChange={field.handleChange}>
                      <option value="">-- None --</option>
                      <option value="CRITICAL">Critical</option>
                      <option value="STANDARD">Standard</option>
                      <option value="EXPERIMENTAL">Experimental</option>
                    </NativeSelect>
                  </Field>
                )}
              </form.Field>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <form.Field name="healthStatus">
                {(field) => (
                  <Field>
                    <Label optional>Health</Label>
                    <NativeSelect value={field.state.value} onChange={field.handleChange}>
                      <option value="">-- None --</option>
                      <option value="HEALTHY">Healthy</option>
                      <option value="DEGRADED">Degraded</option>
                      <option value="UNHEALTHY">Unhealthy</option>
                      <option value="UNKNOWN">Unknown</option>
                    </NativeSelect>
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
                      maxLength={2048}
                      placeholder="https://github.com/org/repo"
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
                    maxLength={2048}
                    placeholder="https://example.com/actuator/health"
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

            <form.Field name="tags">
              {(field) => (
                <Field>
                  <Label optional>Tags</Label>
                  <TagsInput
                    value={field.state.value}
                    onChange={field.handleChange}
                    placeholder="Add tag (press Enter)"
                    maxTags={20}
                  />
                </Field>
              )}
            </form.Field>

            <div className="grid grid-cols-2 gap-4">
              <form.Field name="slaTarget">
                {(field) => (
                  <Field>
                    <Label optional>SLA Target (%)</Label>
                    <Input
                      type="number"
                      min={0}
                      max={100}
                      step={0.01}
                      value={field.state.value}
                      onChange={(e) => field.handleChange(e.target.value)}
                      onBlur={field.handleBlur}
                      placeholder="e.g. 99.9"
                    />
                  </Field>
                )}
              </form.Field>
            </div>

            <form.Field name="documentationUrl">
              {(field) => (
                <Field>
                  <Label optional>Documentation URL</Label>
                  <Input
                    type="url"
                    value={field.state.value}
                    onChange={(e) => field.handleChange(e.target.value)}
                    onBlur={field.handleBlur}
                    maxLength={2048}
                    placeholder="https://..."
                  />
                </Field>
              )}
            </form.Field>

            <form.Field name="runbookUrl">
              {(field) => (
                <Field>
                  <Label optional>Runbook URL</Label>
                  <Input
                    type="url"
                    value={field.state.value}
                    onChange={(e) => field.handleChange(e.target.value)}
                    onBlur={field.handleBlur}
                    maxLength={2048}
                    placeholder="https://..."
                  />
                </Field>
              )}
            </form.Field>
          </div>

          {/* Footer */}
          <div className="flex justify-end gap-3 border-t px-6 py-4">
            <Button type="button" variant="outline" onClick={handleClose} disabled={isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={isPending}>
              {isPending ? 'Saving…' : 'Save changes'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  )
}
