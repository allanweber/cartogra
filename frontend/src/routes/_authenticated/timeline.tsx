import { createFileRoute } from '@tanstack/react-router'
import { useState } from 'react'

import { AppLayout } from '#/components/AppLayout'
import { Badge } from '#/components/ui/badge'
import { MOCK_TIMELINE } from '#/lib/mock-data'
import { cn } from '#/lib/utils'

import type { TimelineEventType } from '#/lib/mock-data'

export const Route = createFileRoute('/_authenticated/timeline')({
  component: TimelinePage,
})

type TypeFilter = TimelineEventType | 'all'

const TYPE_FILTERS: Array<{ label: string; value: TypeFilter }> = [
  { label: 'All', value: 'all' },
  { label: 'Deploy', value: 'deploy' },
  { label: 'Contract', value: 'contract' },
  { label: 'Risk', value: 'risk' },
  { label: 'Ownership', value: 'ownership' },
  { label: 'Dependency', value: 'dependency' },
]

const typeConfig: Record<
  TimelineEventType,
  { color: string; bgColor: string; label: string }
> = {
  deploy: { color: 'bg-[oklch(0.55_0.18_145)]', bgColor: 'bg-[oklch(0.96_0.05_145)] dark:bg-[oklch(0.25_0.06_145)]', label: 'Deploy' },
  contract: { color: 'bg-[oklch(0.55_0.18_260)]', bgColor: 'bg-[oklch(0.96_0.04_260)] dark:bg-[oklch(0.25_0.05_260)]', label: 'Contract' },
  risk: { color: 'bg-[oklch(0.58_0.23_28)]', bgColor: 'bg-[oklch(0.97_0.05_28)] dark:bg-[oklch(0.27_0.05_28)]', label: 'Risk' },
  ownership: { color: 'bg-[oklch(0.65_0.18_60)]', bgColor: 'bg-[oklch(0.97_0.06_80)] dark:bg-[oklch(0.27_0.06_80)]', label: 'Ownership' },
  dependency: { color: 'bg-[oklch(0.60_0.18_240)]', bgColor: 'bg-[oklch(0.96_0.04_240)] dark:bg-[oklch(0.26_0.04_240)]', label: 'Dependency' },
}

function TimelinePage() {
  const [filter, setFilter] = useState<TypeFilter>('all')

  const filtered =
    filter === 'all' ? MOCK_TIMELINE : MOCK_TIMELINE.filter((e) => e.type === filter)

  return (
    <AppLayout title="Timeline" description="Chronological activity log across all services">
      <div className="space-y-4">
        {/* Type filter buttons */}
        <div className="flex flex-wrap gap-2">
          {TYPE_FILTERS.map((f) => (
            <button
              key={f.value}
              onClick={() => setFilter(f.value)}
              aria-pressed={filter === f.value}
              className={cn(
                'rounded-full border px-3 py-1 text-xs font-medium transition-colors',
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
            </button>
          ))}
        </div>

        {/* Vertical timeline */}
        <div className="relative ml-3 space-y-0">
          {filtered.map((event, idx) => {
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
                    <span className="text-[9px] font-bold text-primary-foreground uppercase">
                      {event.type.slice(0, 2)}
                    </span>
                  </div>
                  {idx < filtered.length - 1 && (
                    <div className="w-px flex-1 bg-border" />
                  )}
                </div>

                {/* Card */}
                <div className={cn('mb-4 flex-1 rounded-xl border border-border p-4', config.bgColor)}>
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div className="flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge
                          variant="outline"
                          className="text-[10px] font-semibold uppercase"
                        >
                          {config.label}
                        </Badge>
                        <span className="text-sm font-medium">{event.service}</span>
                        {event.team && (
                          <span className="text-xs text-muted-foreground">· {event.team}</span>
                        )}
                      </div>
                      <p className="mt-1.5 text-sm">{event.msg}</p>
                    </div>
                    <p className="shrink-0 text-xs text-muted-foreground">{event.time}</p>
                  </div>
                  <p className="mt-1.5 text-xs text-muted-foreground">{event.actor}</p>
                </div>
              </div>
            )
          })}

          {filtered.length === 0 && (
            <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-16 text-center">
              <p className="text-sm text-muted-foreground">No events match this filter.</p>
            </div>
          )}
        </div>
      </div>
    </AppLayout>
  )
}
