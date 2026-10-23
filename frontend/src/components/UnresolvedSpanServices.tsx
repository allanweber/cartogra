import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { ConfirmDialog } from '#/components/ConfirmDialog'
import { RegisterServiceDrawer } from '#/components/RegisterServiceDrawer'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Skeleton } from '#/components/ui/skeleton'
import { UNRESOLVED_SPAN_SERVICES_KEY, useUnresolvedSpanServices } from '#/hooks/useUnresolvedSpanServices'
import { ApiError, apiMutate } from '#/lib/api'

import type { UnresolvedSpanService, UnresolvedSpanServicesPage } from '#/lib/topology-types'

export const UNRESOLVED_PAGE_SIZE = 20
const MAX_LIMIT = 200

function lastSeen(dateStr: string): string {
  const secs = Math.floor((Date.now() - new Date(dateStr).getTime()) / 1000)
  if (secs < 3600) return `${Math.max(1, Math.floor(secs / 60))}m ago`
  if (secs < 86400) return `${Math.floor(secs / 3600)}h ago`
  return `${Math.floor(secs / 86400)}d ago`
}

export function UnresolvedSpanServices() {
  const queryClient = useQueryClient()
  const [limit, setLimit] = useState(UNRESOLVED_PAGE_SIZE)
  const { data, isLoading, error } = useUnresolvedSpanServices(limit)
  const [pendingDismiss, setPendingDismiss] = useState<UnresolvedSpanService | null>(null)
  const [registering, setRegistering] = useState<UnresolvedSpanService | null>(null)

  function removeFromCache(id: string) {
    queryClient.setQueriesData<UnresolvedSpanServicesPage>({ queryKey: UNRESOLVED_SPAN_SERVICES_KEY }, (page) =>
      page && page.items.some((item) => item.id === id)
        ? { ...page, items: page.items.filter((item) => item.id !== id), total: Math.max(0, page.total - 1) }
        : page,
    )
  }

  const dismissMutation = useMutation({
    mutationFn: (item: UnresolvedSpanService) =>
      apiMutate<UnresolvedSpanService>(`/v1/topology/unresolved-span-services/${item.id}/dismiss`, {}),
    onSuccess: (_result, item) => {
      removeFromCache(item.id)
      queryClient.invalidateQueries({ queryKey: UNRESOLVED_SPAN_SERVICES_KEY })
      setPendingDismiss(null)
    },
  })

  if (isLoading) {
    return (
      <div className="space-y-2">
        {Array.from({ length: 2 }).map((_, i) => (
          <Skeleton key={i} className="h-14 w-full" />
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

  const items = data?.items ?? []
  const total = data?.total ?? 0

  return (
    <section aria-labelledby="unresolved-span-services-heading" className="space-y-2">
      <div>
        <h3 id="unresolved-span-services-heading" className="text-sm font-semibold">
          Unresolved services ({total})
        </h3>
        <p className="text-xs text-muted-foreground">Called in traces, but not in the catalog.</p>
      </div>

      {items.length === 0 ? (
        <p className="text-sm text-muted-foreground">Every traced call resolves to a catalog service.</p>
      ) : (
        <ul className="space-y-2" aria-label="Unresolved services">
          {items.map((item) => (
            <li key={item.id} className="rounded-lg border border-border p-3 text-sm">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <span className="font-medium break-all">{item.name}</span>
                <span className="text-xs text-muted-foreground">
                  {item.seenCount}× · {lastSeen(item.lastSeenAt)}
                </span>
              </div>
              <div className="mt-2 flex gap-2">
                <Button size="sm" variant="outline" onClick={() => setRegistering(item)}>
                  Create service
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setPendingDismiss(item)}>
                  Dismiss
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {items.length < total && limit < MAX_LIMIT && (
        <Button
          size="sm"
          variant="ghost"
          className="w-full"
          onClick={() => setLimit((l) => Math.min(l + UNRESOLVED_PAGE_SIZE, MAX_LIMIT))}
        >
          Show more ({items.length} of {total})
        </Button>
      )}

      <ConfirmDialog
        open={pendingDismiss !== null}
        onOpenChange={(open) => {
          if (!open) {
            setPendingDismiss(null)
            dismissMutation.reset()
          }
        }}
        title="Dismiss this service?"
        description={
          pendingDismiss
            ? `Hides ${pendingDismiss.name} from this list for everyone in your organization. It will not come back, even if traces keep calling it.`
            : ''
        }
        confirmLabel="Dismiss"
        isPending={dismissMutation.isPending}
        error={dismissMutation.error}
        onConfirm={() => pendingDismiss && dismissMutation.mutate(pendingDismiss)}
      />

      <RegisterServiceDrawer
        open={registering !== null}
        onOpenChange={(open) => {
          if (!open) setRegistering(null)
        }}
        defaultName={registering?.name}
        onRegistered={() => registering && removeFromCache(registering.id)}
      />
    </section>
  )
}
