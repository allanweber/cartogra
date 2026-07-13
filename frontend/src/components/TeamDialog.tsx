import { useForm } from '@tanstack/react-form'
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { Search, UserMinus, UserPlus, X } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { toast } from 'sonner'

import { ApiError, apiFetch } from '#/lib/api'
import { normalizeHealth } from '#/lib/registry-types'
import { useAuthStore } from '#/stores/useAuthStore'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Dialog, DialogContent, DialogTitle } from '#/components/ui/dialog'
import { Input } from '#/components/ui/input'
import { InviteUserDialog } from '#/components/InviteUserDialog'
import { cn } from '#/lib/utils'

import type {
  PageResult,
  RegistryService,
  RegistryTeam,
  RegistryTeamMember,
  TenantUser,
} from '#/lib/registry-types'

interface TeamDialogProps {
  team?: RegistryTeam
  open: boolean
  onOpenChange: (open: boolean) => void
  onDeleted?: () => void
}

export function TeamDialog({
  team,
  open,
  onOpenChange,
  onDeleted,
}: TeamDialogProps) {
  const queryClient = useQueryClient()
  const isAdmin = useAuthStore((s) => s.user?.roles.includes('ADMIN') ?? false)
  const isTeamOwner = useAuthStore((s) => s.user?.roles.includes('TEAM_OWNER') ?? false)
  const canManageMembers = isAdmin || isTeamOwner
  const isEdit = team !== undefined
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [serviceSearch, setServiceSearch] = useState('')
  const [selectedServiceIds, setSelectedServiceIds] = useState<Set<string>>(
    new Set(),
  )
  const [inviteOpen, setInviteOpen] = useState(false)
  const [memberSearch, setMemberSearch] = useState('')
  const [selectedMemberIds, setSelectedMemberIds] = useState<Set<string>>(
    new Set(),
  )

  const { data: servicesPage } = useQuery({
    queryKey: ['services', 'dialog', team?.id],
    queryFn: () =>
      apiFetch<PageResult<RegistryService>>('/v1/registry/services?limit=1000'),
    enabled: isEdit && open,
  })

  const { data: members } = useQuery({
    queryKey: ['team-members', team?.id],
    queryFn: () =>
      apiFetch<RegistryTeamMember[]>(`/v1/registry/teams/${team!.id}/members`),
    enabled: isEdit && open,
  })

  const { data: tenantUsers } = useQuery({
    queryKey: ['tenant-users'],
    queryFn: () => apiFetch<TenantUser[]>('/auth/admin/users'),
    enabled: canManageMembers && isEdit && open,
  })

  const memberIdsKey = useMemo(
    () => [...selectedMemberIds].sort().join(','),
    [selectedMemberIds],
  )
  const { data: memberUsers } = useQuery({
    queryKey: ['member-users', memberIdsKey],
    queryFn: () => apiFetch<TenantUser[]>(`/auth/users?ids=${memberIdsKey}`),
    enabled: isEdit && open && selectedMemberIds.size > 0,
    placeholderData: keepPreviousData,
  })

  function resolveUser(userId: string) {
    return (
      tenantUsers?.find((u) => u.id === userId) ??
      memberUsers?.find((u) => u.id === userId)
    )
  }

  const addableUsers = useMemo(() => {
    const q = memberSearch.trim().toLowerCase()
    if (!q) return []
    return (tenantUsers ?? [])
      .filter((u) => !selectedMemberIds.has(u.id))
      .filter(
        (u) =>
          u.email.toLowerCase().includes(q) ||
          (u.name?.toLowerCase().includes(q) ?? false),
      )
      .slice(0, 8)
  }, [tenantUsers, selectedMemberIds, memberSearch])

  const allServices = servicesPage?.items ?? []
  const ownedServices = useMemo(
    () => allServices.filter((s) => s.teamId === team?.id),
    [allServices, team?.id],
  )
  const orphanServices = useMemo(
    () => allServices.filter((s) => s.teamId === null),
    [allServices],
  )
  const visibleServices = useMemo(() => {
    const q = serviceSearch.trim().toLowerCase()
    const pool = [...ownedServices, ...orphanServices]
    return q ? pool.filter((s) => s.name.toLowerCase().includes(q)) : pool
  }, [ownedServices, orphanServices, serviceSearch])

  useEffect(() => {
    if (open && isEdit && servicesPage) {
      setSelectedServiceIds(new Set(ownedServices.map((s) => s.id)))
    }
  }, [open, servicesPage])

  useEffect(() => {
    if (open && isEdit && members) {
      setSelectedMemberIds(new Set(members.map((m) => m.userId)))
    }
  }, [open, members])

  const initialMemberIds = useMemo(
    () => new Set((members ?? []).map((m) => m.userId)),
    [members],
  )

  const saveMutation = useMutation({
    mutationFn: async (name: string) => {
      const initialOwned = new Set(ownedServices.map((s) => s.id))
      const toAdd = [...selectedServiceIds].filter(
        (id) => !initialOwned.has(id),
      )
      const toRemove = [...initialOwned].filter(
        (id) => !selectedServiceIds.has(id),
      )

      const memberIdsToAdd = [...selectedMemberIds].filter(
        (id) => !initialMemberIds.has(id),
      )
      const memberIdsToRemove = [...initialMemberIds].filter(
        (id) => !selectedMemberIds.has(id),
      )

      await Promise.all([
        apiFetch<RegistryTeam>(`/v1/registry/teams/${team!.id}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name }),
        }),
        ...toAdd.map((id) =>
          apiFetch(`/v1/registry/services/${id}/owner`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ teamId: team!.id }),
          }),
        ),
        ...toRemove.map((id) =>
          apiFetch(`/v1/registry/services/${id}/owner`, { method: 'DELETE' }),
        ),
        ...memberIdsToAdd.map((id) =>
          apiFetch(`/v1/registry/teams/${team!.id}/members`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: id }),
          }),
        ),
        ...memberIdsToRemove.map((id) =>
          apiFetch(`/v1/registry/teams/${team!.id}/members/${id}`, {
            method: 'DELETE',
          }),
        ),
      ])
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['teams'] })
      queryClient.invalidateQueries({ queryKey: ['services', 'teams-page'] })
      queryClient.invalidateQueries({
        queryKey: ['services', 'dialog', team?.id],
      })
      queryClient.invalidateQueries({ queryKey: ['team-members', team?.id] })
      toast.success('Team updated')
      onOpenChange(false)
    },
    onError: (err) => {
      toast.error(err.message, {
        description: err instanceof ApiError ? `trace: ${err.traceId}` : undefined,
      })
    },
  })

  const createMutation = useMutation({
    mutationFn: (name: string) =>
      apiFetch<RegistryTeam>('/v1/registry/teams', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['teams'] })
      toast.success('Team created')
      onOpenChange(false)
    },
    onError: (err) => {
      toast.error(err.message, {
        description: err instanceof ApiError ? `trace: ${err.traceId}` : undefined,
      })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: () =>
      apiFetch<void>(`/v1/registry/teams/${team!.id}`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['teams'] })
      queryClient.invalidateQueries({ queryKey: ['services', 'teams-page'] })
      toast.success('Team deleted')
      onDeleted?.()
    },
    onError: (err) => {
      toast.error(err.message, {
        description: err instanceof ApiError ? `trace: ${err.traceId}` : undefined,
      })
    },
  })

  const form = useForm({
    defaultValues: { name: team?.name ?? '' },
    onSubmit: ({ value }) => {
      const name = value.name.trim()
      if (isEdit) {
        saveMutation.mutate(name)
      } else {
        createMutation.mutate(name)
      }
    },
  })

  useEffect(() => {
    if (open) return
    createMutation.reset()
    saveMutation.reset()
    deleteMutation.reset()
    setConfirmDelete(false)
    setServiceSearch('')
    setSelectedServiceIds(new Set())
    setMemberSearch('')
    setSelectedMemberIds(new Set())
    form.reset({ name: team?.name ?? '' })
  }, [open])

  useEffect(() => {
    if (open && team) {
      form.reset({ name: team.name })
    }
  }, [team?.name])

  function handleClose() {
    if (isPending) return
    onOpenChange(false)
  }

  function toggleService(id: string) {
    setSelectedServiceIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function addMemberLocal(userId: string) {
    setSelectedMemberIds((prev) => new Set(prev).add(userId))
    setMemberSearch('')
  }

  function removeMemberLocal(userId: string) {
    setSelectedMemberIds((prev) => {
      const next = new Set(prev)
      next.delete(userId)
      return next
    })
  }

  const activeMutation = isEdit ? saveMutation : createMutation
  const isPending = activeMutation.isPending || deleteMutation.isPending
  const submitError = activeMutation.error ?? deleteMutation.error

  return (
    <>
      <Dialog open={open} onOpenChange={handleClose}>
        <DialogContent
          className="gap-0 p-0 sm:max-w-120"
          showCloseButton={false}
          onInteractOutside={(e) => e.preventDefault()}
        >
          <div className="border-b px-6 py-5">
            <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">
              {isEdit ? 'Manage team' : 'Teams'}
            </p>
            <div className="mt-1 flex items-start justify-between gap-4">
              <DialogTitle className="text-2xl font-bold">
                {isEdit ? team.name : 'Add team'}
              </DialogTitle>
              <button
                onClick={handleClose}
                className="mt-1 rounded-sm text-muted-foreground opacity-70 hover:opacity-100 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              >
                <X className="size-4" />
                <span className="sr-only">Close</span>
              </button>
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
              {submitError && (
                <Alert variant="destructive">
                  <AlertDescription>
                    {submitError.message}
                    {submitError instanceof ApiError &&
                      ` (trace: ${submitError.traceId})`}
                  </AlertDescription>
                </Alert>
              )}

              <form.Field name="name">
                {(field) => (
                  <div className="space-y-1.5">
                    <label className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                      Team name
                      <span className="text-destructive">*</span>
                    </label>
                    <Input
                      value={field.state.value}
                      onChange={(e) => field.handleChange(e.target.value)}
                      onBlur={field.handleBlur}
                      placeholder="e.g. Platform"
                      required
                      maxLength={255}
                      autoFocus={!isEdit}
                    />
                  </div>
                )}
              </form.Field>

              {isEdit && (
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <p className="text-sm font-medium text-foreground">
                      Members
                    </p>
                    {isAdmin && (
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        className="h-7 gap-1.5 px-2 text-xs"
                        onClick={() => setInviteOpen(true)}
                      >
                        <UserPlus className="size-3.5" aria-hidden="true" />
                        Invite user
                      </Button>
                    )}
                  </div>
                  {canManageMembers && (
                    <div className="relative">
                      <Search className="absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
                      <Input
                        value={memberSearch}
                        onChange={(e) => setMemberSearch(e.target.value)}
                        placeholder="Add existing user by email…"
                        className="pl-8 text-sm"
                        aria-label="Search existing users to add"
                      />
                      {memberSearch.trim() && (
                        <ul
                          role="list"
                          className="absolute z-10 mt-1 max-h-40 w-full overflow-y-auto rounded-md border bg-popover shadow-md"
                        >
                          {addableUsers.length === 0 ? (
                            <li className="px-3 py-2.5 text-xs text-muted-foreground">
                              No matching users
                            </li>
                          ) : (
                            addableUsers.map((u) => (
                              <li key={u.id}>
                                <button
                                  type="button"
                                  onClick={() => addMemberLocal(u.id)}
                                  className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-xs hover:bg-muted"
                                >
                                  <span className="truncate">
                                    {u.name ? `${u.name} — ${u.email}` : u.email}
                                  </span>
                                  <UserPlus className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                                </button>
                              </li>
                            ))
                          )}
                        </ul>
                      )}
                    </div>
                  )}
                  <div className="max-h-40 overflow-y-auto rounded-md border">
                    {selectedMemberIds.size === 0 ? (
                      <p className="px-3 py-4 text-center text-xs text-muted-foreground">
                        No members yet — invite a user to this team
                      </p>
                    ) : (
                      <ul role="list" className="divide-y">
                        {[...selectedMemberIds].map((userId) => {
                          const user = resolveUser(userId)
                          const isPendingAdd = !initialMemberIds.has(userId)
                          return (
                            <li
                              key={userId}
                              className="flex items-center justify-between gap-3 px-3 py-2.5"
                            >
                              <span className="flex items-center gap-2 truncate text-xs text-muted-foreground">
                                {user
                                  ? user.name
                                    ? `${user.name} — ${user.email}`
                                    : user.email
                                  : userId}
                                {isPendingAdd && (
                                  <span className="shrink-0 text-[10px] text-muted-foreground">
                                    will add
                                  </span>
                                )}
                              </span>
                              {canManageMembers && (
                                <button
                                  type="button"
                                  onClick={() => removeMemberLocal(userId)}
                                  aria-label="Remove member"
                                  className="shrink-0 rounded-md p-1 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                                >
                                  <UserMinus className="size-3.5" />
                                </button>
                              )}
                            </li>
                          )
                        })}
                      </ul>
                    )}
                  </div>
                </div>
              )}

              {isEdit && (
                <div className="space-y-2">
                  <p className="text-sm font-medium text-foreground">
                    Services
                  </p>
                  <div className="relative">
                    <Search className="absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
                    <Input
                      placeholder="Filter services…"
                      value={serviceSearch}
                      onChange={(e) => setServiceSearch(e.target.value)}
                      className="pl-8 text-sm"
                      aria-label="Filter services"
                    />
                  </div>
                  <div className="max-h-48 overflow-y-auto rounded-md border">
                    {visibleServices.length === 0 ? (
                      <p className="px-3 py-4 text-center text-xs text-muted-foreground">
                        {serviceSearch
                          ? 'No services match'
                          : 'No services available'}
                      </p>
                    ) : (
                      <ul role="list" className="divide-y">
                        {visibleServices.map((svc) => {
                          const checked = selectedServiceIds.has(svc.id)
                          const health = normalizeHealth(svc.healthStatus)
                          return (
                            <li key={svc.id}>
                              <label className="flex cursor-pointer items-center gap-3 px-3 py-2.5 hover:bg-muted/50">
                                <input
                                  type="checkbox"
                                  checked={checked}
                                  onChange={() => toggleService(svc.id)}
                                  className="size-4 rounded accent-primary"
                                  aria-label={svc.name}
                                />
                                <span
                                  className={cn(
                                    'size-2 shrink-0 rounded-full',
                                    health === 'healthy' &&
                                      'bg-[oklch(0.55_0.18_145)]',
                                    health === 'degraded' &&
                                      'bg-[oklch(0.65_0.18_60)]',
                                    health === 'down' &&
                                      'bg-[oklch(0.58_0.23_28)]',
                                  )}
                                  aria-hidden="true"
                                />
                                <span className="flex-1 truncate text-sm">
                                  {svc.name}
                                </span>
                                {checked && svc.teamId === null && (
                                  <span className="text-[10px] text-muted-foreground">
                                    will assign
                                  </span>
                                )}
                                {!checked && svc.teamId === team.id && (
                                  <span className="text-[10px] text-muted-foreground">
                                    will orphan
                                  </span>
                                )}
                              </label>
                            </li>
                          )
                        })}
                      </ul>
                    )}
                  </div>
                </div>
              )}
            </div>

            <div
              className={`flex items-center border-t px-6 py-4 ${isEdit ? 'justify-between' : 'justify-end gap-3'}`}
            >
              {isEdit &&
                (confirmDelete ? (
                  <div className="flex items-center gap-2">
                    <span className="text-sm text-muted-foreground">
                      Delete this team?
                    </span>
                    <Button
                      type="button"
                      variant="destructive"
                      size="sm"
                      disabled={isPending}
                      onClick={() => deleteMutation.mutate()}
                    >
                      {deleteMutation.isPending
                        ? 'Deleting…'
                        : 'Confirm delete'}
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => setConfirmDelete(false)}
                    >
                      Never mind
                    </Button>
                  </div>
                ) : (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                    onClick={() => setConfirmDelete(true)}
                    disabled={isPending}
                  >
                    Delete
                  </Button>
                ))}

              <div className="flex gap-3">
                <Button
                  type="button"
                  variant="outline"
                  onClick={handleClose}
                  disabled={isPending}
                >
                  Cancel
                </Button>
                <Button type="submit" disabled={isPending}>
                  {isEdit
                    ? saveMutation.isPending
                      ? 'Saving…'
                      : 'Save changes'
                    : createMutation.isPending
                      ? 'Creating…'
                      : 'Create team'}
                </Button>
              </div>
            </div>
          </form>
        </DialogContent>
      </Dialog>

      {isEdit && (
        <InviteUserDialog
          open={inviteOpen}
          onOpenChange={setInviteOpen}
          lockedTeam={{ id: team.id, name: team.name }}
        />
      )}
    </>
  )
}
