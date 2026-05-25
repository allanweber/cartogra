import { createFileRoute } from '@tanstack/react-router'
import { ChevronDown, ChevronUp, Wrench } from 'lucide-react'
import { useState } from 'react'

import { AppLayout } from '#/components/AppLayout'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent } from '#/components/ui/card'
import { MOCK_RISKS } from '#/lib/mock-data'
import { cn } from '#/lib/utils'

import type { RiskSeverity } from '#/lib/mock-data'

export const Route = createFileRoute('/_authenticated/risks')({
  component: RisksPage,
})

type SeverityFilter = RiskSeverity | 'all'

function RisksPage() {
  const [filter, setFilter] = useState<SeverityFilter>('all')

  const criticalCount = MOCK_RISKS.filter((r) => r.severity === 'critical').length
  const warningCount = MOCK_RISKS.filter((r) => r.severity === 'warning').length
  const infoCount = MOCK_RISKS.filter((r) => r.severity === 'info').length

  const filtered =
    filter === 'all' ? MOCK_RISKS : MOCK_RISKS.filter((r) => r.severity === filter)

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

        {/* Risk cards */}
        <div className="space-y-2">
          {filtered.map((risk) => (
            <RiskCard key={risk.id} risk={typeof risk === 'object' ? risk : risk} />
          ))}
        </div>
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
    <button
      onClick={onClick}
      className={cn(
        'rounded-xl border p-4 text-left transition-all',
        active && variant === 'critical' && 'border-red-300 bg-red-50 dark:border-red-700 dark:bg-red-950/30',
        active && variant === 'warning' && 'border-amber-300 bg-amber-50 dark:border-amber-700 dark:bg-amber-950/30',
        active && variant === 'info' && 'border-blue-300 bg-blue-50 dark:border-blue-700 dark:bg-blue-950/30',
        !active && 'border-border bg-card hover:bg-muted/50',
      )}
    >
      <p
        className={cn(
          'text-2xl font-bold',
          variant === 'critical' && 'text-red-600 dark:text-red-400',
          variant === 'warning' && 'text-amber-600 dark:text-amber-400',
          variant === 'info' && 'text-blue-600 dark:text-blue-400',
        )}
      >
        {count}
      </p>
      <p className="mt-1 text-xs font-medium text-muted-foreground">{label}</p>
    </button>
  )
}

function RiskCard({ risk }: { risk: (typeof MOCK_RISKS)[number] }) {
  const [expanded, setExpanded] = useState(false)

  const severityConfig = {
    critical: {
      border: 'border-l-red-500',
      bg: 'bg-red-50 dark:bg-red-950/20',
      badge: 'border-red-200 bg-red-50 text-red-700 dark:border-red-800 dark:bg-red-950/50 dark:text-red-400',
    },
    warning: {
      border: 'border-l-amber-500',
      bg: 'bg-amber-50 dark:bg-amber-950/20',
      badge: 'border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-800 dark:bg-amber-950/50 dark:text-amber-400',
    },
    info: {
      border: 'border-l-blue-400',
      bg: 'bg-blue-50 dark:bg-blue-950/20',
      badge: 'border-blue-200 bg-blue-50 text-blue-700 dark:border-blue-800 dark:bg-blue-950/50 dark:text-blue-400',
    },
  }

  const config = severityConfig[risk.severity]

  return (
    <Card className={cn('overflow-hidden border-l-4', config.border)}>
      <button
        className="w-full text-left"
        onClick={() => setExpanded((e) => !e)}
      >
        <CardContent className={cn('flex items-start gap-3 p-4', config.bg)}>
          <div className="flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <p className="font-semibold">{risk.title}</p>
              <Badge variant="outline" className={cn('text-[10px] font-bold uppercase', config.badge)}>
                {risk.severity}
              </Badge>
            </div>
            <div className="mt-1.5 flex flex-wrap gap-1.5">
              {risk.services.map((s) => (
                <span
                  key={s}
                  className="rounded-md bg-background/60 px-1.5 py-0.5 text-[11px] font-medium text-muted-foreground"
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
      </button>

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
        </CardContent>
      )}
    </Card>
  )
}
