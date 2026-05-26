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
            <RiskCard key={risk.id} risk={risk} />
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
        active && variant === 'critical' && 'border-[oklch(0.70_0.15_28)] bg-[oklch(0.97_0.05_28)] dark:border-[oklch(0.55_0.18_28)] dark:bg-[oklch(0.27_0.05_28)]',
        active && variant === 'warning' && 'border-[oklch(0.72_0.14_60)] bg-[oklch(0.97_0.06_80)] dark:border-[oklch(0.60_0.15_60)] dark:bg-[oklch(0.27_0.06_80)]',
        active && variant === 'info' && 'border-[oklch(0.65_0.12_240)] bg-[oklch(0.96_0.04_240)] dark:border-[oklch(0.55_0.14_240)] dark:bg-[oklch(0.26_0.04_240)]',
        !active && 'border-border bg-card hover:bg-muted/50',
      )}
    >
      <p
        className={cn(
          'text-2xl font-bold',
          variant === 'critical' && 'text-[oklch(0.58_0.23_28)] dark:text-[oklch(0.72_0.18_28)]',
          variant === 'warning' && 'text-[oklch(0.65_0.18_60)] dark:text-[oklch(0.78_0.14_60)]',
          variant === 'info' && 'text-[oklch(0.60_0.18_240)] dark:text-[oklch(0.70_0.16_240)]',
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
      border: 'border-l-[oklch(0.58_0.23_28)]',
      bg: 'bg-[oklch(0.97_0.05_28)] dark:bg-[oklch(0.27_0.05_28)]',
      badge: 'bg-severity-critical border-current',
    },
    warning: {
      border: 'border-l-[oklch(0.65_0.18_60)]',
      bg: 'bg-[oklch(0.97_0.06_80)] dark:bg-[oklch(0.27_0.06_80)]',
      badge: 'bg-severity-warning border-current',
    },
    info: {
      border: 'border-l-[oklch(0.60_0.18_240)]',
      bg: 'bg-[oklch(0.96_0.04_240)] dark:bg-[oklch(0.26_0.04_240)]',
      badge: 'bg-severity-info border-current',
    },
  }

  const config = severityConfig[risk.severity]

  return (
    <Card className={cn('overflow-hidden border-l-4', config.border)}>
      <button
        className="w-full text-left"
        onClick={() => setExpanded((e) => !e)}
        aria-expanded={expanded}
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
