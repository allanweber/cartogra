import { createFileRoute } from '@tanstack/react-router'
import { useState } from 'react'
import { toast } from 'sonner'

import { AppLayout } from '#/components/AppLayout'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Input } from '#/components/ui/input'
import { MOCK_TIMELINE } from '#/lib/mock-data'
import { cn } from '#/lib/utils'

import type { TimelineEventType } from '#/lib/mock-data'

export const Route = createFileRoute('/_authenticated/timeline')({
  component: TimelinePage,
})

type TypeFilter = TimelineEventType | 'all'

const PAGE_SIZE = 5

const TYPE_FILTERS: Array<{ label: string; value: TypeFilter }> = [
  { label: 'All', value: 'all' },
  { label: 'Deploy', value: 'deploy' },
  { label: 'Contract', value: 'contract' },
  { label: 'Risk', value: 'risk' },
  { label: 'Ownership', value: 'ownership' },
  { label: 'Dependency', value: 'dependency' },
]

const TYPE_ABBR: Record<TimelineEventType, string> = {
  deploy: 'dpl',
  contract: 'con',
  risk: 'rsk',
  ownership: 'own',
  dependency: 'dep',
}

const typeConfig: Record<TimelineEventType, { color: string; label: string }> = {
  deploy: { color: 'bg-success', label: 'Deploy' },
  contract: { color: 'bg-info', label: 'Contract' },
  risk: { color: 'bg-critical', label: 'Risk' },
  ownership: { color: 'bg-warning', label: 'Ownership' },
  dependency: { color: 'bg-info', label: 'Dependency' },
}

function TimelinePage() {
  const [filter, setFilter] = useState<TypeFilter>('all')
  const [query, setQuery] = useState('')
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE)

  const normalizedQuery = query.trim().toLowerCase()
  const filtered = MOCK_TIMELINE.filter((e) => {
    if (filter !== 'all' && e.type !== filter) return false
    if (!normalizedQuery) return true
    return (
      e.service.toLowerCase().includes(normalizedQuery) ||
      e.actor.toLowerCase().includes(normalizedQuery) ||
      e.msg.toLowerCase().includes(normalizedQuery)
    )
  })
  const visible = filtered.slice(0, visibleCount)

  function handleFilterChange(next: TypeFilter) {
    setFilter(next)
    setVisibleCount(PAGE_SIZE)
  }

  function handleQueryChange(next: string) {
    setQuery(next)
    setVisibleCount(PAGE_SIZE)
  }

  function openEvent(event: (typeof MOCK_TIMELINE)[number]) {
    toast.info(`Drill-through for "${event.msg}" isn't available yet`, {
      description: 'Linking events to their source record ships in a later phase.',
    })
  }

  return (
    <AppLayout title="Timeline" description="Chronological activity log across all services">
      <div className="space-y-4">
        <Input
          value={query}
          onChange={(e) => handleQueryChange(e.target.value)}
          placeholder="Search by service, actor, or message…"
          className="h-9 max-w-sm text-sm"
          aria-label="Search timeline events"
        />

        {/* Type filter buttons */}
        <div className="flex flex-wrap gap-2">
          {TYPE_FILTERS.map((f) => (
            <Button
              key={f.value}
              variant="ghost"
              onClick={() => handleFilterChange(f.value)}
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
                  className={cn(
                    'mr-1.5 inline-block size-1.5 rounded-full',
                    typeConfig[f.value].color,
                  )}
                />
              )}
              {f.label}
            </Button>
          ))}
        </div>

        {/* Vertical timeline */}
        <div className="relative ml-3 space-y-0">
          {visible.map((event, idx) => {
            const config = typeConfig[event.type]
            return (
              <div key={event.id} className="flex gap-4">
                {/* Line + dot */}
                <div className="flex flex-col items-center">
                  <div
                    className={cn(
                      'z-10 flex size-7 shrink-0 items-center justify-center rounded-full ring-2 ring-background',
                      config.color,
                    )}
                  >
                    <span className="text-xs font-bold text-primary-foreground uppercase">
                      {TYPE_ABBR[event.type]}
                    </span>
                  </div>
                  {idx < visible.length - 1 && (
                    <div className="w-px flex-1 bg-border" />
                  )}
                </div>

                {/* Card */}
                <button
                  type="button"
                  onClick={() => openEvent(event)}
                  className="mb-4 flex-1 rounded-xl border border-border bg-card p-4 text-left transition-colors hover:bg-muted/30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
                >
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div className="flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge variant="outline" className="border-current text-xs font-semibold uppercase">
                          {config.label}
                        </Badge>
                        <span className="text-sm font-medium">{event.service}</span>
                        {event.team && (
                          <span className="text-xs text-muted-foreground">· {event.team}</span>
                        )}
                      </div>
                      <p className="mt-1.5 text-sm">{event.msg}</p>
                    </div>
                    <p className="shrink-0 text-xs text-muted-foreground" title={event.time}>
                      {event.time}
                    </p>
                  </div>
                  <p className="mt-1.5 text-xs text-muted-foreground">{event.actor}</p>
                </button>
              </div>
            )
          })}

          {filtered.length === 0 && (
            <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-16 text-center">
              <p className="text-sm text-muted-foreground">No events match this filter.</p>
            </div>
          )}
        </div>

        {visibleCount < filtered.length && (
          <div className="flex justify-center">
            <Button variant="outline" onClick={() => setVisibleCount((c) => c + PAGE_SIZE)}>
              Load more ({filtered.length - visibleCount} remaining)
            </Button>
          </div>
        )}
      </div>
    </AppLayout>
  )
}
