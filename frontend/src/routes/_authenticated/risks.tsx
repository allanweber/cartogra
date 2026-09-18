import { createFileRoute } from '@tanstack/react-router'
import { ChevronDown, ChevronUp, ShieldCheck, Wrench } from 'lucide-react'
import { useEffect, useState } from 'react'

import { AppLayout } from '#/components/AppLayout'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Card, CardContent } from '#/components/ui/card'
import { MOCK_RISKS } from '#/lib/mock-data'
import { cn } from '#/lib/utils'

import type { RiskSeverity } from '#/lib/mock-data'

export const Route = createFileRoute('/_authenticated/risks')({
  component: RisksPage,
})

type SeverityFilter = RiskSeverity | 'all'

const SEVERITY_ORDER: Record<RiskSeverity, number> = { critical: 0, warning: 1, info: 2 }
const DISMISSED_KEY = 'cartogra:risks:dismissed'

function RisksPage() {
  const [filter, setFilter] = useState<SeverityFilter>('all')
  const [dismissed, setDismissed] = useState<Set<string>>(new Set())
  const [showDismissed, setShowDismissed] = useState(false)

  useEffect(() => {
    try {
      const raw = localStorage.getItem(DISMISSED_KEY)
      if (raw) setDismissed(new Set(JSON.parse(raw) as string[]))
    } catch {
      // per-viewer convenience only — ignore read failures
    }
  }, [])

  function toggleDismissed(id: string) {
    setDismissed((prev) => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      try {
        localStorage.setItem(DISMISSED_KEY, JSON.stringify([...next]))
      } catch {
        // per-viewer convenience only — ignore write failures
      }
      return next
    })
  }

  const criticalCount = MOCK_RISKS.filter((r) => r.severity === 'critical').length
  const warningCount = MOCK_RISKS.filter((r) => r.severity === 'warning').length
  const infoCount = MOCK_RISKS.filter((r) => r.severity === 'info').length

  const filtered = MOCK_RISKS
    .filter((r) => filter === 'all' || r.severity === filter)
    .filter((r) => showDismissed || !dismissed.has(r.id))
    .sort((a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity])

  const dismissedCount = MOCK_RISKS.filter((r) => dismissed.has(r.id)).length

  return (
    <AppLayout title="Risks" description="Active dependency and operational risks">
      <div className="space-y-4">
        {/* Summary stat cards */}
        <div className="grid grid-cols-3 gap-3">
          <SummaryCard
            label="Critical"
            count={criticalCount}
            active={filter === 'critical'}
            onClick={() => setFilter((f) => (f === 'critical' ? 'all' : 'critical'))}
            variant="critical"
          />
          <SummaryCard
            label="Warning"
            count={warningCount}
            active={filter === 'warning'}
            onClick={() => setFilter((f) => (f === 'warning' ? 'all' : 'warning'))}
            variant="warning"
          />
          <SummaryCard
            label="Info"
            count={infoCount}
            active={filter === 'info'}
            onClick={() => setFilter((f) => (f === 'info' ? 'all' : 'info'))}
            variant="info"
          />
        </div>

        {dismissedCount > 0 && (
          <div className="flex justify-end">
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setShowDismissed((s) => !s)}
              className="h-auto gap-1.5 px-2 py-1 text-xs text-muted-foreground"
            >
              {showDismissed ? 'Hide' : 'Show'} {dismissedCount} dismissed
            </Button>
          </div>
        )}

        {/* Risk cards */}
        {filtered.length === 0 ? (
          <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-16 text-center">
            <ShieldCheck className="mb-3 size-8 text-muted-foreground/50" aria-hidden="true" />
            <p className="text-sm font-medium">No active risks</p>
            <p className="mt-1 text-xs text-muted-foreground">
              {dismissed.size > 0 && !showDismissed
                ? 'All risks in this filter have been dismissed.'
                : 'Nothing matches this filter right now.'}
            </p>
          </div>
        ) : (
          <div className="space-y-2">
            {filtered.map((risk) => (
              <RiskCard
                key={risk.id}
                risk={risk}
                dismissed={dismissed.has(risk.id)}
                onToggleDismissed={() => toggleDismissed(risk.id)}
              />
            ))}
          </div>
        )}
      </div>
    </AppLayout>
  )
}

function SummaryCard({
  label,
  count,
  active,
  onClick,
  variant,
}: {
  label: string
  count: number
  active: boolean
  onClick: () => void
  variant: 'critical' | 'warning' | 'info'
}) {
  return (
    <Button
      variant="ghost"
      onClick={onClick}
      className={cn(
        'h-auto rounded-xl border p-4 text-left transition-all',
        active && variant === 'critical' && 'border-critical bg-critical-subtle',
        active && variant === 'warning' && 'border-warning bg-warning-subtle',
        active && variant === 'info' && 'border-info bg-info-subtle',
        !active && 'border-border bg-card hover:bg-muted/50',
      )}
    >
      <p
        className={cn(
          'text-2xl font-bold',
          variant === 'critical' && 'text-critical',
          variant === 'warning' && 'text-warning',
          variant === 'info' && 'text-info',
        )}
      >
        {count}
      </p>
      <p className="mt-1 text-xs font-medium text-muted-foreground">{label}</p>
    </Button>
  )
}

function RiskCard({
  risk,
  dismissed,
  onToggleDismissed,
}: {
  risk: (typeof MOCK_RISKS)[number]
  dismissed: boolean
  onToggleDismissed: () => void
}) {
  const [expanded, setExpanded] = useState(false)

  return (
    <Card className={cn('overflow-hidden', dismissed && 'opacity-60')}>
      <Button
        variant="ghost"
        className="h-auto w-full justify-start rounded-none p-0 text-left hover:bg-transparent"
        onClick={() => setExpanded((e) => !e)}
        aria-expanded={expanded}
      >
        <CardContent className="flex items-start gap-3 p-4">
          <span
            aria-hidden
            className={cn('mt-1.5 size-2 shrink-0 rounded-full bg-current', `severity-${risk.severity}`)}
          />
          <div className="flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <p className="font-semibold">{risk.title}</p>
              <Badge
                variant="outline"
                className={cn('border-current text-xs font-semibold uppercase', `severity-${risk.severity}`)}
              >
                {risk.severity}
              </Badge>
              {dismissed && (
                <Badge variant="outline" className="text-xs text-muted-foreground">
                  Dismissed
                </Badge>
              )}
            </div>
            <div className="mt-1.5 flex flex-wrap gap-1.5">
              {risk.services.map((s) => (
                <span
                  key={s}
                  className="rounded-md bg-muted px-1.5 py-0.5 text-xs font-medium text-muted-foreground"
                >
                  {s}
                </span>
              ))}
            </div>
          </div>
          <div className="shrink-0 text-muted-foreground">
            {expanded ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
          </div>
        </CardContent>
      </Button>

      {expanded && (
        <CardContent className="space-y-3 border-t border-border p-4">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              Explanation
            </p>
            <p className="mt-1 text-sm">{risk.explanation}</p>
          </div>
          <div className="flex items-start gap-2 rounded-lg bg-muted/50 p-3">
            <Wrench className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
            <div>
              <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                Suggested fix
              </p>
              <p className="mt-1 text-sm">{risk.fix}</p>
            </div>
          </div>
          <div className="flex justify-end">
            <Button
              variant="outline"
              size="sm"
              onClick={(e) => { e.stopPropagation(); onToggleDismissed() }}
            >
              {dismissed ? 'Restore' : 'Dismiss'}
            </Button>
          </div>
        </CardContent>
      )}
    </Card>
  )
}
