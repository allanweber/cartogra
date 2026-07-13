import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createFileRoute } from '@tanstack/react-router'
import { UserPlus } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { InviteUserDialog } from '#/components/InviteUserDialog'
import { SettingsTabsLayout } from '#/components/SettingsTabsLayout'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'
import { Skeleton } from '#/components/ui/skeleton'
import { ApiError, apiFetch, apiMutate } from '#/lib/api'
import type { TenantUser } from '#/lib/registry-types'
import { useAuthStore } from '#/stores/useAuthStore'

export const Route = createFileRoute('/_authenticated/settings/users')({
  component: UsersPage,
})

const ROLES = ['VIEWER', 'MEMBER', 'TEAM_OWNER', 'ADMIN'] as const

function UsersPage() {
  const currentUserId = useAuthStore((s) => s.user?.id)
  const queryClient = useQueryClient()
  const [inviteOpen, setInviteOpen] = useState(false)
  const [confirmRemoveId, setConfirmRemoveId] = useState<string | null>(null)

  const { data: users, isLoading, error } = useQuery({
    queryKey: ['admin-users'],
    queryFn: () => apiFetch<TenantUser[]>('/auth/admin/users'),
  })

  const roleMutation = useMutation({
    mutationFn: ({ id, role }: { id: string; role: string }) =>
      apiMutate<TenantUser>(`/auth/admin/users/${id}/role`, { role }, 'PATCH'),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin-users'] })
      toast.success('Role updated')
    },
    onError: (err) => {
      toast.error(err.message, {
        description: err instanceof ApiError ? `trace: ${err.traceId}` : undefined,
      })
    },
  })

  const removeMutation = useMutation({
    mutationFn: (id: string) => apiMutate<void>(`/auth/admin/users/${id}`, undefined, 'DELETE'),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin-users'] })
      queryClient.invalidateQueries({ queryKey: ['tenant-usage'] })
      queryClient.invalidateQueries({ queryKey: ['tenant-info'] })
      toast.success('User removed')
      setConfirmRemoveId(null)
    },
    onError: (err) => {
      toast.error(err.message, {
        description: err instanceof ApiError ? `trace: ${err.traceId}` : undefined,
      })
      setConfirmRemoveId(null)
    },
  })

  const disableMutation = useMutation({
    mutationFn: ({ id, disabled }: { id: string; disabled: boolean }) =>
      apiMutate<TenantUser>(`/auth/admin/users/${id}/${disabled ? 'disable' : 'enable'}`, undefined, 'POST'),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['admin-users'] })
      toast.success(variables.disabled ? 'User disabled' : 'User enabled')
    },
    onError: (err) => {
      toast.error(err.message, {
        description: err instanceof ApiError ? `trace: ${err.traceId}` : undefined,
      })
    },
  })

  return (
    <SettingsTabsLayout>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle className="text-sm">Users</CardTitle>
          <Button onClick={() => setInviteOpen(true)} size="sm" className="gap-1.5">
            <UserPlus className="size-3.5" aria-hidden="true" />
            Invite user
          </Button>
        </CardHeader>
        <CardContent className="space-y-3">
          {isLoading && <Skeleton className="h-40 w-full rounded-lg" />}

          {error && (
            <Alert variant="destructive">
              <AlertDescription>
                Failed to load users.
                {error instanceof ApiError && ` (trace: ${error.traceId})`}
              </AlertDescription>
            </Alert>
          )}

          {users?.map((user) => {
            const isSelf = user.id === currentUserId
            const isConfirming = confirmRemoveId === user.id
            return (
              <div
                key={user.id}
                className="flex items-center justify-between gap-4 rounded-lg bg-muted/50 px-4 py-3"
              >
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium">
                    {user.name ?? user.email}
                    {isSelf && (
                      <Badge variant="outline" className="ml-2">
                        You
                      </Badge>
                    )}
                    {user.disabled && (
                      <Badge variant="destructive" className="ml-2">
                        Disabled
                      </Badge>
                    )}
                  </p>
                  <p className="truncate text-xs text-muted-foreground">{user.email}</p>
                </div>

                <div className="flex shrink-0 items-center gap-2">
                  <select
                    value={user.roles[0] ?? 'MEMBER'}
                    disabled={isSelf || roleMutation.isPending}
                    onChange={(e) =>
                      roleMutation.mutate({ id: user.id, role: e.target.value })
                    }
                    className="flex h-8 rounded-md border border-input bg-transparent px-2 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {ROLES.map((role) => (
                      <option key={role} value={role}>
                        {role}
                      </option>
                    ))}
                  </select>

                  {isConfirming ? (
                    <div className="flex items-center gap-1.5">
                      <span className="text-xs text-muted-foreground">Remove?</span>
                      <Button
                        type="button"
                        variant="destructive"
                        size="sm"
                        onClick={() => removeMutation.mutate(user.id)}
                        disabled={removeMutation.isPending}
                      >
                        {removeMutation.isPending ? 'Removing…' : 'Confirm'}
                      </Button>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={() => setConfirmRemoveId(null)}
                        disabled={removeMutation.isPending}
                      >
                        Cancel
                      </Button>
                    </div>
                  ) : (
                    <>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={isSelf || disableMutation.isPending}
                        onClick={() =>
                          disableMutation.mutate({ id: user.id, disabled: !user.disabled })
                        }
                      >
                        {user.disabled ? 'Enable' : 'Disable'}
                      </Button>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={isSelf}
                        onClick={() => setConfirmRemoveId(user.id)}
                      >
                        Remove
                      </Button>
                    </>
                  )}
                </div>
              </div>
            )
          })}
        </CardContent>
      </Card>

      <InviteUserDialog open={inviteOpen} onOpenChange={setInviteOpen} />
    </SettingsTabsLayout>
  )
}
