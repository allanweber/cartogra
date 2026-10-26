import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { ConfirmDialog } from '#/components/ConfirmDialog'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Skeleton } from '#/components/ui/skeleton'
import { useDrifts } from '#/hooks/useDrifts'
import { ApiError, apiMutate } from '#/lib/api'
import { cn } from '#/lib/utils'

import type { Drift, DriftAction, DriftType } from '#/lib/topology-types'

const TYPE_LABEL: Record<DriftType, string> = { UNDECLARED: 'Undeclared', MISSING: 'Missing' }

const TYPE_HINT: Record<DriftType, string> = {
  UNDECLARED: 'Observed in traces, never declared.',
  MISSING: 'Declared, but no recent calls observed.',
}

interface ActionSpec {
  action: DriftAction
  label: string
  title: string
  tone: 'default' | 'destructive'
  description: (from: string, to: string) => string
}

const PRIMARY_ACTION: Record<DriftType, ActionSpec> = {
  UNDECLARED: {
    action: 'ACCEPT_OBSERVED',
    label: 'Accept',
    title: 'Declare this dependency?',
    tone: 'default',
    description: (from, to) => `Adds a declared dependency from ${from} to ${to}, matching what traces show.`,
  },
  MISSING: {
    action: 'REMOVE_DECLARED',
    label: 'Remove declared',
    title: 'Remove the declared dependency?',
    tone: 'destructive',
    description: (from, to) => `Deletes the declared dependency from ${from} to ${to}. It no longer shows traffic.`,
  },
}

const DISMISS_ACTION: ActionSpec = {
  action: 'DISMISS',
  label: 'Dismiss',
  title: 'Dismiss this drift?',
  tone: 'destructive',
  description: (from, to) => `Hides the ${from} → ${to} drift and keeps it hidden. The dependency itself is not changed.`,
}

export function driftKey(drift: Pick<Drift, 'sourceServiceId' | 'targetServiceId'>): string {
  return `${drift.sourceServiceId}>${drift.targetServiceId}`
}

export function DriftTab({
  nodesById,
  selectedDriftId,
  onSelectDrift,
  serviceId,
}: {
  nodesById: Map<string, { name: string }>
  serviceId?: string
  selectedDriftId: string | null
  onSelectDrift: (drift: Drift | null) => void
}) {
  const queryClient = useQueryClient()
  const { data, isLoading, error } = useDrifts()
  const [pending, setPending] = useState<{ drift: Drift; spec: ActionSpec } | null>(null)

  const resolveMutation = useMutation({
    mutationFn: ({ drift, spec }: { drift: Drift; spec: ActionSpec }) =>
      apiMutate<Drift>(`/v1/topology/drifts/${drift.id}/resolve`, { action: spec.action }),
    onSuccess: (_result, { drift }) => {
      if (drift.id === selectedDriftId) onSelectDrift(null)
      queryClient.invalidateQueries({ queryKey: ['drifts'] })
      queryClient.invalidateQueries({ queryKey: ['graph'] })
      queryClient.invalidateQueries({ queryKey: ['risks'] })
      queryClient.invalidateQueries({ queryKey: ['dependencies'] })
      setPending(null)
    },
    onError: (err) => {
      if (err instanceof ApiError && err.code === 'DRIFT_NO_LONGER_OBSERVED') {
        queryClient.invalidateQueries({ queryKey: ['drifts'] })
      }
    },
  })

  const nameOf = (id: string) => nodesById.get(id)?.name ?? id

  if (isLoading) {
    return (
      <div className="space-y-2">
        {Array.from({ length: 3 }).map((_, i) => (
          <Skeleton key={i} className="h-16 w-full" />
        ))}
      </div>
    )
  }

  if (error) {
    return (
      <Alert variant="destructive">
        <AlertDescription>
          {error.message}
          {error instanceof ApiError && ` (trace: ${error.traceId})`}
        </AlertDescription>
      </Alert>
    )
  }

  const allDrifts = data?.items ?? []
  const drifts = serviceId
    ? allDrifts.filter((d) => d.sourceServiceId === serviceId || d.targetServiceId === serviceId)
    : allDrifts
  const capped = (data?.total ?? 0) > allDrifts.length

  const cappedNote = capped && (
    <p className="mt-2 text-xs text-muted-foreground">
      {serviceId
        ? `Checked the newest ${allDrifts.length} of ${data?.total} drifts; older ones may involve this service.`
        : `Showing the first ${allDrifts.length} of ${data?.total} drifts.`}
    </p>
  )

  if (drifts.length === 0) {
    return (
      <>
        <p className="text-sm text-muted-foreground">
          {serviceId
            ? `No drift on ${nameOf(serviceId)}. Its declared and observed dependencies agree.`
            : 'No drift. Declared and observed dependencies agree.'}
        </p>
        {cappedNote}
      </>
    )
  }

  return (
    <>
      <ul className="space-y-2" aria-label="Drift records">
        {drifts.map((drift) => {
          const selected = drift.id === selectedDriftId
          const primary = PRIMARY_ACTION[drift.type]
          const pair = `${nameOf(drift.sourceServiceId)} → ${nameOf(drift.targetServiceId)}`
          return (
            <li
              key={drift.id}
              className={cn('rounded-lg border border-border p-3 text-sm', selected && 'border-ring bg-muted/50')}
            >
              <button
                type="button"
                className="w-full text-left"
                aria-pressed={selected}
                onClick={() => onSelectDrift(selected ? null : drift)}
              >
                <span className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">
                    {pair}
                  </span>
                  <Badge variant={drift.type === 'UNDECLARED' ? 'secondary' : 'outline'}>{TYPE_LABEL[drift.type]}</Badge>
                </span>
                <span className="mt-1 block text-xs text-muted-foreground">{TYPE_HINT[drift.type]}</span>
              </button>
              <div className="mt-2 flex gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  aria-label={`${primary.label}: ${pair}`}
                  onClick={() => setPending({ drift, spec: primary })}
                >
                  {primary.label}
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  aria-label={`${DISMISS_ACTION.label}: ${pair}`}
                  onClick={() => setPending({ drift, spec: DISMISS_ACTION })}
                >
                  {DISMISS_ACTION.label}
                </Button>
              </div>
            </li>
          )
        })}
      </ul>
      {cappedNote}

      <ConfirmDialog
        open={pending !== null}
        onOpenChange={(open) => {
          if (!open) {
            setPending(null)
            resolveMutation.reset()
          }
        }}
        title={pending?.spec.title ?? ''}
        description={
          pending
            ? pending.spec.description(nameOf(pending.drift.sourceServiceId), nameOf(pending.drift.targetServiceId))
            : ''
        }
        confirmLabel={pending?.spec.label}
        tone={pending?.spec.tone}
        isPending={resolveMutation.isPending}
        error={resolveMutation.error}
        onConfirm={() => pending && resolveMutation.mutate(pending)}
      />
    </>
  )
}
