import { useForm } from '@tanstack/react-form'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { useEffect } from 'react'
import { toast } from 'sonner'

import { ApiError, apiFetch } from '#/lib/api'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Dialog, DialogContent, DialogTitle } from '#/components/ui/dialog'
import { Input } from '#/components/ui/input'

import type { PageResult, RegistryTeam } from '#/lib/registry-types'

interface InviteUserDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  lockedTeam?: { id: string; name: string }
}

const ROLES = ['VIEWER', 'MEMBER', 'TEAM_OWNER', 'ADMIN'] as const

export function InviteUserDialog({
  open,
  onOpenChange,
  lockedTeam,
}: InviteUserDialogProps) {
  const queryClient = useQueryClient()

  const { data: teamsPage } = useQuery({
    queryKey: ['teams'],
    queryFn: () =>
      apiFetch<PageResult<RegistryTeam>>('/v1/registry/teams?limit=200'),
    enabled: open && !lockedTeam,
  })
  const teams = teamsPage?.items ?? []

  const inviteMutation = useMutation({
    mutationFn: (input: {
      email: string
      role: string
      teamId: string | null
    }) =>
      apiFetch<{ userId: string; email: string }>('/auth/admin/invite', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tenant-usage'] })
      if (lockedTeam)
        queryClient.invalidateQueries({
          queryKey: ['team-members', lockedTeam.id],
        })
      toast.success('Invitation sent')
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
      email: '',
      role: 'MEMBER' as (typeof ROLES)[number],
      teamId: lockedTeam?.id ?? '',
    },
    onSubmit: ({ value }) => {
      inviteMutation.mutate({
        email: value.email.trim(),
        role: value.role,
        teamId: lockedTeam?.id ?? (value.teamId || null),
      })
    },
  })

  useEffect(() => {
    if (open) return
    inviteMutation.reset()
    form.reset()
  }, [open])

  function handleClose() {
    if (inviteMutation.isPending) return
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent
        className="gap-0 p-0 sm:max-w-100"
        showCloseButton={false}
        onInteractOutside={(e) => e.preventDefault()}
      >
        <div className="border-b px-6 py-5">
          <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">
            Users
          </p>
          <div className="mt-1 flex items-start justify-between gap-4">
            <DialogTitle className="text-2xl font-bold">
              Invite user
            </DialogTitle>
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
          onSubmit={(e) => {
            e.preventDefault()
            form.handleSubmit()
          }}
        >
          <div className="space-y-5 px-6 py-6">
            {inviteMutation.error && (
              <Alert variant="destructive">
                <AlertDescription>
                  {inviteMutation.error.message}
                  {inviteMutation.error instanceof ApiError &&
                    ` (trace: ${inviteMutation.error.traceId})`}
                </AlertDescription>
              </Alert>
            )}

            <form.Field name="email">
              {(field) => (
                <div className="space-y-1.5">
                  <label className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                    Email
                    <span className="text-destructive">*</span>
                  </label>
                  <Input
                    type="email"
                    value={field.state.value}
                    onChange={(e) => field.handleChange(e.target.value)}
                    onBlur={field.handleBlur}
                    placeholder="teammate@company.com"
                    required
                    maxLength={255}
                    autoFocus
                  />
                </div>
              )}
            </form.Field>

            <form.Field name="role">
              {(field) => (
                <div className="space-y-1.5">
                  <label className="text-sm font-medium text-foreground">
                    Role
                  </label>
                  <select
                    value={field.state.value}
                    onChange={(e) =>
                      field.handleChange(
                        e.target.value as (typeof ROLES)[number],
                      )
                    }
                    className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/50"
                  >
                    {ROLES.map((role) => (
                      <option key={role} value={role}>
                        {role}
                      </option>
                    ))}
                  </select>
                </div>
              )}
            </form.Field>

            {lockedTeam ? (
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-foreground">
                  Team
                </label>
                <p className="rounded-md border border-input bg-muted/50 px-3 py-2 text-sm text-muted-foreground">
                  {lockedTeam.name}
                </p>
              </div>
            ) : (
              <form.Field name="teamId">
                {(field) => (
                  <div className="space-y-1.5">
                    <label className="text-sm font-medium text-foreground">
                      Team (optional)
                    </label>
                    <select
                      value={field.state.value}
                      onChange={(e) => field.handleChange(e.target.value)}
                      className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/50"
                    >
                      <option value="">No team</option>
                      {teams.map((team) => (
                        <option key={team.id} value={team.id}>
                          {team.name}
                        </option>
                      ))}
                    </select>
                  </div>
                )}
              </form.Field>
            )}
          </div>

          <div className="flex items-center justify-end gap-3 border-t px-6 py-4">
            <Button
              type="button"
              variant="outline"
              onClick={handleClose}
              disabled={inviteMutation.isPending}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={inviteMutation.isPending}>
              {inviteMutation.isPending ? 'Sending…' : 'Send invite'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  )
}
