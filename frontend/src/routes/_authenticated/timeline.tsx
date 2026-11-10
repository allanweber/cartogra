import { createFileRoute } from '@tanstack/react-router'
import { useState } from 'react'

import { AppLayout } from '#/components/AppLayout'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Input } from '#/components/ui/input'
import { Skeleton } from '#/components/ui/skeleton'
import { useAuditEvents } from '#/hooks/useAuditEvents'
import { ApiError } from '#/lib/api'
import {
  AUDIT_CATEGORY_COLORS,
  AUDIT_CATEGORY_ENTITY_TYPES,
  auditActor,
  auditCategory,
  auditMessage,
  auditSubject,
  relativeTime,
} from '#/lib/audit-format'
import { cn } from '#/lib/utils'

import type { AuditCategory } from '#/lib/audit-format'

export const Route = createFileRoute('/_authenticated/timeline')({
  component: TimelinePage,
})

type CategoryFilter = AuditCategory | 'all'

const CATEGORY_FILTERS: Array<{ label: string; value: CategoryFilter }> = [
  { label: 'All', value: 'all' },
  { label: 'Service', value: 'service' },
  { label: 'Team', value: 'team' },
  { label: 'Dependency', value: 'dependency' },
  { label: 'Drift', value: 'drift' },
  { label: 'Integration', value: 'integration' },
  { label: 'Access', value: 'access' },
]

const categoryConfig: Record<AuditCategory, { label: string; abbr: string }> = {
  service: { label: 'Service', abbr: 'svc' },
  team: { label: 'Team', abbr: 'tm' },
  dependency: { label: 'Dependency', abbr: 'dep' },
  drift: { label: 'Drift', abbr: 'dft' },
  integration: { label: 'Integration', abbr: 'int' },
  access: { label: 'Access', abbr: 'acc' },
}

function TimelinePage() {
  const [filter, setFilter] = useState<CategoryFilter>('all')
  const [query, setQuery] = useState('')
  const { data, isLoading, error, hasNextPage, fetchNextPage, isFetchingNextPage } = useAuditEvents(
    filter === 'all' ? null : AUDIT_CATEGORY_ENTITY_TYPES[filter],
  )

  // Offset paging over a log that grows between pages can return an event twice.
  const events = [...new Map((data?.pages.flatMap((page) => page.items) ?? []).map((e) => [e.eventId, e])).values()]
  const total = data?.pages[0]?.total ?? 0
  const normalizedQuery = query.trim().toLowerCase()
  const visible = normalizedQuery
    ? events.filter(
        (e) =>
          auditSubject(e).toLowerCase().includes(normalizedQuery) ||
          auditActor(e).toLowerCase().includes(normalizedQuery) ||
          auditMessage(e).toLowerCase().includes(normalizedQuery),
      )
    : events

  return (
    <AppLayout title="Timeline" description="Chronological activity log across all services">
      <div className="space-y-4">
        <Input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search by name, actor, or message…"
          className="h-9 max-w-sm text-sm"
          aria-label="Search timeline events"
        />

        <div className="flex flex-wrap gap-2">
          {CATEGORY_FILTERS.map((f) => (
            <Button
              key={f.value}
              variant="ghost"
              onClick={() => setFilter(f.value)}
              aria-pressed={filter === f.value}
              className={cn(
                'h-auto rounded-full border px-3 py-1 text-xs font-medium transition-colors pointer-coarse:px-4 pointer-coarse:py-2.5',
                filter === f.value
                  ? 'border-primary bg-primary text-primary-foreground'
                  : 'border-border bg-background text-muted-foreground hover:border-primary/50 hover:text-foreground',
              )}
            >
              {f.value !== 'all' && (
                <span
                  className={cn('mr-1.5 inline-block size-1.5 rounded-full', AUDIT_CATEGORY_COLORS[f.value])}
                />
              )}
              {f.label}
            </Button>
          ))}
        </div>

        {isLoading && (
          <div className="space-y-3" aria-label="Loading timeline">
            {Array.from({ length: 4 }).map((_, i) => (
              <Skeleton key={i} className="h-20 w-full rounded-xl" />
            ))}
          </div>
        )}

        {error && (
          <Alert variant="destructive">
            <AlertDescription>
              {error.message}
              {error instanceof ApiError && ` (trace: ${error.traceId})`}
            </AlertDescription>
          </Alert>
        )}

        {!isLoading && (!error || events.length > 0) && (
          <div className="relative ml-3 space-y-0">
            {visible.map((event, idx) => {
              const category = auditCategory(event.entityType) ?? 'service'
              const config = categoryConfig[category]
              return (
                <div key={event.eventId} className="flex gap-4" data-testid="timeline-event">
                  <div className="flex flex-col items-center">
                    <div
                      className={cn(
                        'z-10 flex size-7 shrink-0 items-center justify-center rounded-full ring-2 ring-background',
                        AUDIT_CATEGORY_COLORS[category],
                      )}
                    >
                      <span className="text-xs font-bold text-primary-foreground uppercase">{config.abbr}</span>
                    </div>
                    {idx < visible.length - 1 && <div className="w-px flex-1 bg-border" />}
                  </div>

                  <div className="mb-4 flex-1 rounded-xl border border-border bg-card p-4">
                    <div className="flex flex-wrap items-start justify-between gap-2">
                      <div className="flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge variant="outline" className="border-current text-xs font-semibold uppercase">
                            {config.label}
                          </Badge>
                          <span className="text-sm font-medium">{auditSubject(event)}</span>
                        </div>
                        <p className="mt-1.5 text-sm">{auditMessage(event)}</p>
                      </div>
                      <time
                        className="shrink-0 text-xs text-muted-foreground"
                        dateTime={event.occurredAt}
                        title={new Date(event.occurredAt).toLocaleString()}
                      >
                        {relativeTime(event.occurredAt)}
                      </time>
                    </div>
                    <p className="mt-1.5 text-xs text-muted-foreground">{auditActor(event)}</p>
                  </div>
                </div>
              )
            })}

            {visible.length === 0 && (
              <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-16 text-center">
                <p className="text-sm text-muted-foreground">
                  {events.length === 0
                    ? 'No activity recorded yet.'
                    : hasNextPage
                      ? `No match in the ${events.length} loaded of ${total} events. Load more to search further.`
                      : 'No events match this search.'}
                </p>
              </div>
            )}
          </div>
        )}

        {hasNextPage && (
          <div className="flex justify-center">
            <Button variant="outline" onClick={() => void fetchNextPage()} disabled={isFetchingNextPage}>
              {isFetchingNextPage ? 'Loading…' : `Load more (${total - events.length} remaining)`}
            </Button>
          </div>
        )}
      </div>
    </AppLayout>
  )
}
