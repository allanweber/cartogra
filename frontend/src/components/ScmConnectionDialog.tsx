import { useMutation, useQueryClient } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { useEffect, useState } from 'react'

import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Dialog, DialogContent, DialogTitle } from '#/components/ui/dialog'
import { Input } from '#/components/ui/input'
import { ToggleGroup, ToggleGroupItem } from '#/components/ui/toggle-group'
import { ApiError, apiFetch, apiMutate } from '#/lib/api'

export interface ScmConnection {
  id: string
  tenantId: string
  provider: string
  config: Record<string, unknown>
  syncScheduler: boolean
  pollIntervalMinutes: number
  nextSyncAt: string | null
  lastSyncAt: string | null
  lastSyncStatus: string | null
  webhookEnabled: boolean
  createdAt: string
  updatedAt: string
}

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  provider: 'github' | 'azuredevops'
  connection?: ScmConnection
  onSuccess?: () => void
}

function buildConfig(
  provider: 'github' | 'azuredevops',
  fields: { org: string; token: string } | { org: string; pat: string },
  existing?: ScmConnection,
): string {
  const base: Record<string, unknown> = { ...(existing?.config ?? {}) }
  if (provider === 'github') {
    const f = fields as { org: string; token: string }
    base.org = f.org
    if (f.token) base.token = f.token
  } else {
    const f = fields as { org: string; pat: string }
    base.organization = f.org
    if (f.pat) base.pat = f.pat
  }
  return JSON.stringify(base)
}

export function ScmConnectionDialog({ open, onOpenChange, provider, connection, onSuccess }: Props) {
  const isEdit = !!connection
  const queryClient = useQueryClient()

  const [ghOrg, setGhOrg] = useState('')
  const [ghToken, setGhToken] = useState('')
  const [azUrl, setAzUrl] = useState('')
  const [azPat, setAzPat] = useState('')
  const [pollInterval, setPollInterval] = useState<number>(15)
  const [disconnectConfirm, setDisconnectConfirm] = useState(false)

  useEffect(() => {
    if (open) {
      setGhOrg((connection?.config.org as string | undefined) ?? '')
      setAzUrl((connection?.config.organization as string | undefined) ?? '')
      setGhToken('')
      setAzPat('')
      setPollInterval(connection?.pollIntervalMinutes ?? 15)
      setDisconnectConfirm(false)
    }
  }, [open, connection])

  const providerLabel = provider === 'github' ? 'GitHub' : 'Azure DevOps'

  const saveMutation = useMutation({
    mutationFn: () => {
      const config =
        provider === 'github'
          ? buildConfig(provider, { org: ghOrg, token: ghToken }, connection)
          : buildConfig(provider, { org: azUrl, pat: azPat }, connection)
      if (isEdit) {
        return apiMutate<ScmConnection>(`/v1/ingestion/scm-connections/${connection.id}`, { config, pollIntervalMinutes: pollInterval }, 'PUT')
      }
      return apiMutate<ScmConnection>('/v1/ingestion/scm-connections', { provider, config, syncScheduler: true, pollIntervalMinutes: pollInterval }, 'POST')
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['scm-connections'] })
      onOpenChange(false)
      onSuccess?.()
    },
  })

  const disconnectMutation = useMutation({
    mutationFn: () => {
      if (!connection) throw new Error('No connection to disconnect')
      return apiFetch<void>(`/v1/ingestion/scm-connections/${connection.id}`, { method: 'DELETE' })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['scm-connections'] })
      onOpenChange(false)
    },
    onError: () => setDisconnectConfirm(false),
  })

  function handleClose() {
    if (saveMutation.isPending || disconnectMutation.isPending) return
    onOpenChange(false)
  }

  const submitError = saveMutation.error ?? disconnectMutation.error
  const isPending = saveMutation.isPending || disconnectMutation.isPending

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent
        className="gap-0 p-0 sm:max-w-[480px]"
        showCloseButton={false}
        onInteractOutside={(e) => e.preventDefault()}
      >
        <div className="border-b px-6 py-5">
          <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">
            {isEdit ? 'Manage' : 'Connect'} · Source Control
          </p>
          <div className="mt-1 flex items-start justify-between gap-4">
            <DialogTitle className="text-2xl font-bold">{providerLabel}</DialogTitle>
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
          onSubmit={(e) => { e.preventDefault(); saveMutation.mutate() }}
        >
          <div className="space-y-5 px-6 py-6">
            {isEdit && connection.lastSyncStatus && (
              <div className="flex items-center gap-2 rounded-lg bg-muted/60 px-4 py-2.5 text-sm">
                <span className="size-2 shrink-0 rounded-full bg-green-500" />
                {connection.lastSyncStatus}
              </div>
            )}

            {submitError && (
              <Alert variant="destructive">
                <AlertDescription>
                  {submitError.message}
                  {submitError instanceof ApiError && ` (trace: ${submitError.traceId})`}
                </AlertDescription>
              </Alert>
            )}

            {provider === 'github' ? (
              <>
                <div className="space-y-1.5">
                  <label className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                    Organization
                    <span className="text-destructive">*</span>
                  </label>
                  <Input
                    placeholder="Enter organization"
                    value={ghOrg}
                    onChange={(e) => setGhOrg(e.target.value)}
                    required
                  />
                </div>
                <div className="space-y-1.5">
                  <label className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                    Access token
                    {!isEdit && <span className="text-destructive">*</span>}
                  </label>
                  <Input
                    type="password"
                    placeholder={isEdit ? '••••••• (leave blank to keep)' : 'Enter access token'}
                    value={ghToken}
                    onChange={(e) => setGhToken(e.target.value)}
                    required={!isEdit}
                    autoComplete="new-password"
                  />
                </div>
              </>
            ) : (
              <>
                <div className="space-y-1.5">
                  <label className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                    Organization
                    <span className="text-destructive">*</span>
                  </label>
                  <Input
                    placeholder="Enter organization name"
                    value={azUrl}
                    onChange={(e) => setAzUrl(e.target.value)}
                    required
                  />
                </div>
                <div className="space-y-1.5">
                  <label className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                    Personal access token
                    {!isEdit && <span className="text-destructive">*</span>}
                  </label>
                  <Input
                    type="password"
                    placeholder={isEdit ? '••••••• (leave blank to keep)' : 'Enter personal access token'}
                    value={azPat}
                    onChange={(e) => setAzPat(e.target.value)}
                    required={!isEdit}
                    autoComplete="new-password"
                  />
                </div>
              </>
            )}

            <div className="space-y-1.5">
              <label className="text-sm font-medium text-foreground">Sync interval</label>
              <ToggleGroup
                type="single"
                value={String(pollInterval)}
                onValueChange={(v) => { if (v) setPollInterval(Number(v)) }}
                className="justify-start"
              >
                {[5, 10, 15, 30, 60].map((m) => (
                  <ToggleGroupItem key={m} value={String(m)} className="px-3 text-xs">
                    {m}m
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
            </div>

            <p className="text-xs text-muted-foreground">
              Credentials are encrypted at rest and never shown after saving.
            </p>
          </div>

          <div className={`flex items-center border-t px-6 py-4 ${isEdit ? 'justify-between' : 'justify-end gap-3'}`}>
            {isEdit && (
              disconnectConfirm ? (
                <div className="flex items-center gap-2">
                  <span className="text-sm text-muted-foreground">Disconnect?</span>
                  <Button
                    type="button"
                    variant="destructive"
                    size="sm"
                    disabled={isPending}
                    onClick={() => disconnectMutation.mutate()}
                  >
                    {disconnectMutation.isPending ? 'Disconnecting…' : 'Confirm'}
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => setDisconnectConfirm(false)}
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
                  onClick={() => setDisconnectConfirm(true)}
                  disabled={isPending}
                >
                  Disconnect
                </Button>
              )
            )}

            <div className="flex gap-3">
              <Button type="button" variant="outline" onClick={handleClose} disabled={isPending}>
                Cancel
              </Button>
              <Button type="submit" disabled={isPending}>
                {saveMutation.isPending ? 'Saving…' : 'Save changes'}
              </Button>
            </div>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  )
}
