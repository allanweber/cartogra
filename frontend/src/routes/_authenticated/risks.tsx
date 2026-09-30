import { useQuery } from '@tanstack/react-query'
import { createFileRoute, Link } from '@tanstack/react-router'
import { ChevronDown, ChevronUp, ShieldCheck, Wrench } from 'lucide-react'
import { useEffect, useState } from 'react'

import { AppLayout } from '#/components/AppLayout'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Card, CardContent } from '#/components/ui/card'
import { Skeleton } from '#/components/ui/skeleton'
import { ToggleGroup, ToggleGroupItem } from '#/components/ui/toggle-group'
import { ApiError, apiFetch } from '#/lib/api'
import { cn } from '#/lib/utils'

import type { PageResult, RegistryService } from '#/lib/registry-types'
import type { Risk, RiskSeverity, RiskType } from '#/lib/topology-types'

export const Route = createFileRoute('/_authenticated/risks')({
  component: RisksPage,
})

type SeverityFilter = RiskSeverity | 'all'
type TypeFilter = RiskType | 'all'

const SEVERITY_ORDER: Record<RiskSeverity, number> = { critical: 0, warning: 1, info: 2 }
const DISMISSED_KEY = 'cartogra:risks:dismissed'
// Generous enough to cover any realistic tenant's combined risk count in one request (each
// source is independently capped at 200 server-side) — severity/type filtering needs to see
// the whole list, not one server page, so this is a single bounded fetch rather than
// page-flipping pagination, matching how graph.tsx/teams.tsx fetch other bounded resources.
const LIMIT = 200
const TYPE_LABELS: Record<RiskType, string> = { spof: 'SPOF', cycle: 'Cycle', orphan: 'Orphan', drift: 'Drift' }

function RisksPage() {
  const [severityFilter, setSeverityFilter] = useState<SeverityFilter>('all')
  const [typeFilter, setTypeFilter] = useState<TypeFilter>('all')
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

  const {
    data: risksPage,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['risks'],
    queryFn: () => apiFetch<PageResult<Risk>>(`/v1/topology/risks?limit=${LIMIT}&offset=0`),
    staleTime: 0,
  })

  // Best-effort: a failed services fetch degrades to showing raw IDs on the chip row rather
  // than blocking the page — risks themselves are this page's primary content.
  const { data: servicesPage } = useQuery({
    queryKey: ['services'],
    queryFn: () => apiFetch<PageResult<RegistryService>>('/v1/registry/services?limit=1000'),
  })
  const servicesById = new Map((servicesPage?.items ?? []).map((s) => [s.id, s]))

  const risks = risksPage?.items ?? []

  const criticalCount = risks.filter((r) => r.severity === 'critical').length
  const warningCount = risks.filter((r) => r.severity === 'warning').length
  const infoCount = risks.filter((r) => r.severity === 'info').length
  const typeCounts: Record<RiskType, number> = {
    spof: risks.filter((r) => r.type === 'spof').length,
    cycle: risks.filter((r) => r.type === 'cycle').length,
    orphan: risks.filter((r) => r.type === 'orphan').length,
    drift: risks.filter((r) => r.type === 'drift').length,
  }

  const filtered = risks
    .filter((r) => severityFilter === 'all' || r.severity === severityFilter)
    .filter((r) => typeFilter === 'all' || r.type === typeFilter)
    .filter((r) => showDismissed || !dismissed.has(r.id))
    .sort((a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity])

  const dismissedCount = risks.filter((r) => dismissed.has(r.id)).length

  return (
    <AppLayout title="Risks" description="Active dependency and operational risks">
      <div className="space-y-4">
        {isLoading && (
          <div className="space-y-3">
            <div className="grid grid-cols-3 gap-3">
              {Array.from({ length: 3 }).map((_, i) => (
                <Skeleton key={i} className="h-20 w-full rounded-xl" />
              ))}
            </div>
            <Skeleton className="h-16 w-full rounded-lg" />
            <Skeleton className="h-16 w-full rounded-lg" />
          </div>
        )}

        {!isLoading && (error || !risksPage) && (
          <Alert variant="destructive">
            <AlertDescription>
              {error?.message ?? 'Failed to load risks.'}
              {error instanceof ApiError && ` (trace: ${error.traceId})`}
            </AlertDescription>
          </Alert>
        )}

        {!isLoading && risksPage && (
          <>
            {/* Summary stat cards */}
            <div className="grid grid-cols-3 gap-3">
              <SummaryCard
                label="Critical"
                count={criticalCount}
                active={severityFilter === 'critical'}
                onClick={() => setSeverityFilter((f) => (f === 'critical' ? 'all' : 'critical'))}
                variant="critical"
              />
              <SummaryCard
                label="Warning"
                count={warningCount}
                active={severityFilter === 'warning'}
                onClick={() => setSeverityFilter((f) => (f === 'warning' ? 'all' : 'warning'))}
                variant="warning"
              />
              <SummaryCard
                label="Info"
                count={infoCount}
                active={severityFilter === 'info'}
                onClick={() => setSeverityFilter((f) => (f === 'info' ? 'all' : 'info'))}
                variant="info"
              />
            </div>

            {/* Risk-type filter */}
            <ToggleGroup
              type="single"
              value={typeFilter}
              onValueChange={(value) => setTypeFilter((value || 'all') as TypeFilter)}
              variant="outline"
              size="sm"
              className="flex-wrap"
            >
              <ToggleGroupItem value="all">All types</ToggleGroupItem>
              {(Object.keys(TYPE_LABELS) as RiskType[]).map((type) => (
                <ToggleGroupItem key={type} value={type}>
                  {TYPE_LABELS[type]} ({typeCounts[type]})
                </ToggleGroupItem>
              ))}
            </ToggleGroup>

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
                    servicesById={servicesById}
                    dismissed={dismissed.has(risk.id)}
                    onToggleDismissed={() => toggleDismissed(risk.id)}
                  />
                ))}
              </div>
            )}
          </>
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
  servicesById,
  dismissed,
  onToggleDismissed,
}: {
  risk: Risk
  servicesById: Map<string, RegistryService>
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
              <Badge variant="outline" className="text-xs uppercase text-muted-foreground">
                {TYPE_LABELS[risk.type]}
              </Badge>
              {dismissed && (
                <Badge variant="outline" className="text-xs text-muted-foreground">
                  Dismissed
                </Badge>
              )}
            </div>
            <div className="mt-1.5 flex flex-wrap gap-1.5" onClick={(e) => e.stopPropagation()}>
              {risk.affectedServices.map((serviceId) => (
                <Link
                  key={serviceId}
                  to="/graph"
                  search={{ service: serviceId }}
                  className="rounded-md bg-muted px-1.5 py-0.5 text-xs font-medium text-muted-foreground hover:bg-muted/70 hover:text-foreground"
                >
                  {servicesById.get(serviceId)?.name ?? serviceId}
                </Link>
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
