import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { GitBranch, Network, Pencil, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'

import { ConfirmDialog } from '#/components/ConfirmDialog'
import { DependencyDialog } from '#/components/DependencyDialog'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Skeleton } from '#/components/ui/skeleton'
import { ApiError, apiFetch } from '#/lib/api'
import { normalizeHealth } from '#/lib/registry-types'
import { cn } from '#/lib/utils'

import type { ServiceHealth } from '#/lib/registry-types'
import type { DependencyDirectionEntry, ServiceDependencies } from '#/lib/topology-types'

type Direction = 'upstream' | 'downstream'

function healthDotClass(health: ServiceHealth): string {
  if (health === 'down') return 'bg-critical'
  if (health === 'degraded') return 'bg-warning'
  return 'bg-success'
}

function DependencyRow({
  entry,
  direction,
  canManage,
  onEdit,
  onRemove,
}: {
  entry: DependencyDirectionEntry
  direction: Direction
  canManage: boolean
  onEdit: (entry: DependencyDirectionEntry, direction: Direction) => void
  onRemove: (entry: DependencyDirectionEntry) => void
}) {
  const health = normalizeHealth(entry.healthStatus)
  return (
    <li className="flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2.5">
      <div className="flex min-w-0 items-center gap-2">
        <span className={cn('size-2 shrink-0 rounded-full', healthDotClass(health))} aria-hidden="true" />
        <Link
          to="/catalog/$serviceId"
          params={{ serviceId: entry.serviceId }}
          className="truncate text-sm font-medium hover:text-primary hover:underline"
        >
          {entry.name}
        </Link>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <span className="rounded-md border border-border bg-background px-2 py-0.5 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          {entry.protocol}
        </span>
        {canManage && (
          <>
            <Button variant="ghost" size="icon-sm" onClick={() => onEdit(entry, direction)}>
              <Pencil className="size-3.5" />
              <span className="sr-only">Edit dependency on {entry.name}</span>
            </Button>
            <Button variant="ghost" size="icon-sm" onClick={() => onRemove(entry)}>
              <Trash2 className="size-3.5" />
              <span className="sr-only">Remove dependency on {entry.name}</span>
            </Button>
          </>
        )}
      </div>
    </li>
  )
}

function DependencyGroup({
  title,
  entries,
  direction,
  canManage,
  emptyMessage,
  onEdit,
  onRemove,
}: {
  title: string
  entries: DependencyDirectionEntry[]
  direction: Direction
  canManage: boolean
  emptyMessage: string
  onEdit: (entry: DependencyDirectionEntry, direction: Direction) => void
  onRemove: (entry: DependencyDirectionEntry) => void
}) {
  return (
    <div className="space-y-2">
      <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground/60">{title}</p>
      {entries.length === 0 ? (
        <p className="rounded-lg border border-dashed border-border px-3 py-4 text-center text-sm text-muted-foreground">
          {emptyMessage}
        </p>
      ) : (
        <ul className="space-y-1.5">
          {entries.map((entry) => (
            <DependencyRow
              key={entry.id}
              entry={entry}
              direction={direction}
              canManage={canManage}
              onEdit={onEdit}
              onRemove={onRemove}
            />
          ))}
        </ul>
      )}
    </div>
  )
}

export function DependenciesList({ serviceId, canManage }: { serviceId: string; canManage: boolean }) {
  const queryClient = useQueryClient()
  const [dialogState, setDialogState] = useState<
    { mode: 'add' } | { mode: 'edit'; entry: DependencyDirectionEntry; direction: Direction }
  >({ mode: 'add' })
  const [dialogOpen, setDialogOpen] = useState(false)
  const [removing, setRemoving] = useState<DependencyDirectionEntry | null>(null)

  const { data, isLoading, error } = useQuery({
    queryKey: ['dependencies', serviceId],
    queryFn: () => apiFetch<ServiceDependencies>(`/v1/topology/services/${serviceId}/dependencies`),
  })

  const removeMutation = useMutation({
    mutationFn: (id: string) => apiFetch<void>(`/v1/topology/dependencies/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['dependencies', serviceId] })
      setRemoving(null)
    },
  })

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-end gap-2">
        <Button asChild variant="outline" size="sm" className="gap-1.5">
          <Link to="/graph" search={{ service: serviceId }}>
            <GitBranch className="size-3.5" />
            View in graph
          </Link>
        </Button>
        {canManage && (
          <Button
            size="sm"
            onClick={() => {
              setDialogState({ mode: 'add' })
              setDialogOpen(true)
            }}
            className="gap-1.5"
          >
            <Plus className="size-3.5" />
            Add dependency
          </Button>
        )}
      </div>

      {isLoading && (
        <div className="space-y-3">
          <Skeleton className="h-20 w-full rounded-lg" />
          <Skeleton className="h-20 w-full rounded-lg" />
        </div>
      )}

      {!isLoading && (error || !data) && (
        <Alert variant="destructive">
          <AlertDescription>
            {error?.message ?? 'Failed to load dependencies.'}
            {error instanceof ApiError && ` (trace: ${error.traceId})`}
          </AlertDescription>
        </Alert>
      )}

      {!isLoading && data && data.upstream.length === 0 && data.downstream.length === 0 && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-12 text-center">
          <Network className="mb-3 size-8 text-muted-foreground/50" />
          <p className="text-sm text-muted-foreground">No declared dependencies yet.</p>
        </div>
      )}

      {!isLoading && data && (data.upstream.length > 0 || data.downstream.length > 0) && (
        <>
          <DependencyGroup
            title="Downstream — this service depends on"
            entries={data.downstream}
            direction="downstream"
            canManage={canManage}
            emptyMessage="This service doesn't declare any downstream dependencies."
            onEdit={(entry, direction) => {
              setDialogState({ mode: 'edit', entry, direction })
              setDialogOpen(true)
            }}
            onRemove={setRemoving}
          />
          <DependencyGroup
            title="Upstream — depends on this service"
            entries={data.upstream}
            direction="upstream"
            canManage={canManage}
            emptyMessage="No other service declares a dependency on this one."
            onEdit={(entry, direction) => {
              setDialogState({ mode: 'edit', entry, direction })
              setDialogOpen(true)
            }}
            onRemove={setRemoving}
          />
        </>
      )}

      <DependencyDialog
        serviceId={serviceId}
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        edit={dialogState.mode === 'edit' ? { entry: dialogState.entry, direction: dialogState.direction } : undefined}
      />

      <ConfirmDialog
        open={!!removing}
        onOpenChange={(open) => {
          if (!open) setRemoving(null)
        }}
        title="Remove dependency?"
        description={removing ? `This will remove the dependency on ${removing.name}. This can't be undone.` : ''}
        confirmLabel="Remove"
        pendingLabel="Removing…"
        isPending={removeMutation.isPending}
        error={removeMutation.error}
        onConfirm={() => removing && removeMutation.mutate(removing.id)}
      />
    </div>
  )
}
