import { createFileRoute } from '@tanstack/react-router'
import { Plus } from 'lucide-react'
import { createColumnHelper, tableFeatures, useTable } from '@tanstack/react-table'
import { useMemo, useState } from 'react'
import { toast } from 'sonner'

import { ApiKeyCreateDialog } from '#/components/ApiKeyCreateDialog'
import { ApiKeyRevealDialog } from '#/components/ApiKeyRevealDialog'
import { ConfirmDialog } from '#/components/ConfirmDialog'
import { SettingsTabsLayout } from '#/components/SettingsTabsLayout'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'
import { Skeleton } from '#/components/ui/skeleton'
import { useApiKeys, useRevokeApiKey } from '#/hooks/useApiKeys'
import { ApiError } from '#/lib/api'
import { useCan } from '#/lib/permissions'
import type { ApiKey, ApiKeyStatus, CreatedApiKey } from '#/lib/registry-types'
import { relativeTime } from '#/lib/audit-format'

export const Route = createFileRoute('/_authenticated/settings/api-keys')({
  component: ApiKeysPage,
})

const STATUS_VARIANT: Record<ApiKeyStatus, 'default' | 'secondary' | 'destructive'> = {
  active: 'default',
  expired: 'secondary',
  revoked: 'destructive',
}

function formatDate(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString() : '—'
}

const features = tableFeatures({})
const helper = createColumnHelper<typeof features, ApiKey>()
const NO_KEYS: ApiKey[] = []

function ApiKeysPage() {
  const canManage = useCan()('apiKey.manage') === 'allow'
  const { data, isLoading, error } = useApiKeys()
  const revoke = useRevokeApiKey()
  const [createOpen, setCreateOpen] = useState(false)
  const [created, setCreated] = useState<CreatedApiKey | null>(null)
  const [revokeTarget, setRevokeTarget] = useState<ApiKey | null>(null)

  const keys = data?.items ?? NO_KEYS

  const columns = useMemo(() => {
    const base = [
      helper.accessor('name', {
        header: 'Name',
        cell: (ctx) => <span className="font-medium">{ctx.getValue()}</span>,
      }),
      helper.accessor('prefix', {
        header: 'Key',
        cell: (ctx) => <span className="font-mono text-xs">{ctx.getValue()}…</span>,
      }),
      helper.accessor((key) => key.scopes.join(', '), { id: 'scopes', header: 'Scopes' }),
      helper.accessor('status', {
        header: 'Status',
        cell: (ctx) => <Badge variant={STATUS_VARIANT[ctx.getValue()]}>{ctx.getValue()}</Badge>,
      }),
      helper.accessor((key) => formatDate(key.expiresAt), { id: 'expiresAt', header: 'Expires' }),
      helper.accessor((key) => (key.lastUsedAt ? relativeTime(key.lastUsedAt) : 'never'), {
        id: 'lastUsedAt',
        header: 'Last used',
      }),
      helper.accessor((key) => formatDate(key.createdAt), { id: 'createdAt', header: 'Created' }),
    ]
    if (!canManage) return helper.columns(base)
    return helper.columns([
      ...base,
      helper.display({
        id: 'actions',
        header: () => <span className="sr-only">Actions</span>,
        cell: (ctx) =>
          ctx.row.original.status !== 'revoked' && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setRevokeTarget(ctx.row.original)}
            >
              Revoke
            </Button>
          ),
      }),
    ])
  }, [canManage])

  const table = useTable({ features, columns, data: keys })

  function confirmRevoke() {
    if (!revokeTarget) return
    revoke.mutate(revokeTarget.id, {
      onSuccess: () => {
        toast.success('API key revoked')
        setRevokeTarget(null)
      },
    })
  }

  return (
    <SettingsTabsLayout>
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle className="text-sm">API Keys</CardTitle>
          {canManage && (
            <Button onClick={() => setCreateOpen(true)} size="sm" className="gap-1.5">
              <Plus className="size-3.5" aria-hidden="true" />
              Create key
            </Button>
          )}
        </CardHeader>
        <CardContent className="space-y-3">
          {isLoading && <Skeleton className="h-40 w-full rounded-lg" />}

          {error && (
            <Alert variant="destructive">
              <AlertDescription>
                Failed to load API keys.
                {error instanceof ApiError && ` (trace: ${error.traceId})`}
              </AlertDescription>
            </Alert>
          )}

          {data && keys.length === 0 && (
            <p className="text-sm text-muted-foreground">No API keys yet.</p>
          )}

          {keys.length > 0 && (
            <table className="w-full text-sm">
              <thead>
                {table.getHeaderGroups().map((group) => (
                  <tr key={group.id} className="text-left text-xs text-muted-foreground">
                    {group.headers.map((header) => (
                      <th key={header.id} className="py-2 pr-4 font-medium">
                        {header.isPlaceholder ? null : <table.FlexRender header={header} />}
                      </th>
                    ))}
                  </tr>
                ))}
              </thead>
              <tbody>
                {table.getRowModel().rows.map((row) => (
                  <tr key={row.id} className="border-t">
                    {row.getAllCells().map((cell) => (
                      <td
                        key={cell.id}
                        className={cell.column.id === 'actions' ? 'py-2 text-right' : 'py-2 pr-4'}
                      >
                        <table.FlexRender cell={cell} />
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </CardContent>
      </Card>

      {canManage && (
        <>
          <ApiKeyCreateDialog open={createOpen} onOpenChange={setCreateOpen} onCreated={setCreated} />
          <ApiKeyRevealDialog created={created} onClose={() => setCreated(null)} />
          <ConfirmDialog
            open={revokeTarget !== null}
            onOpenChange={(open) => {
              if (!open) {
                setRevokeTarget(null)
                revoke.reset()
              }
            }}
            title="Revoke API key"
            description={`Revoke "${revokeTarget?.name ?? ''}"? Each request with this key fails at once. This cannot be undone.`}
            confirmLabel="Revoke"
            pendingLabel="Revoking…"
            isPending={revoke.isPending}
            error={revoke.error}
            onConfirm={confirmRevoke}
          />
        </>
      )}
    </SettingsTabsLayout>
  )
}
