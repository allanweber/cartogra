import { createFileRoute, Link } from '@tanstack/react-router'
import { Activity, AlertTriangle, ArrowRight, Clock, Server, Users } from 'lucide-react'

import { AppLayout } from '#/components/AppLayout'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'
import {
  MOCK_RISKS,
  MOCK_SERVICES,
  MOCK_TIMELINE,
} from '#/lib/mock-data'
import { cn } from '#/lib/utils'

import type { Service, TimelineEvent } from '#/lib/mock-data'

export const Route = createFileRoute('/_authenticated/dashboard')({
  component: DashboardPage,
})

function DashboardPage() {
  const totalServices = MOCK_SERVICES.length
  const criticalTier = MOCK_SERVICES.filter((s) => s.tier === 'critical').length
  const staleServices = MOCK_SERVICES.filter((s) => s.warnings.includes('stale'))
  const orphanServices = MOCK_SERVICES.filter((s) => s.owner === null)
  const criticalRisks = MOCK_RISKS.filter((r) => r.severity === 'critical').length
  const warningRisks = MOCK_RISKS.filter((r) => r.severity === 'warning').length

  const healthyCount = MOCK_SERVICES.filter((s) => s.health === 'healthy').length
  const degradedCount = MOCK_SERVICES.filter((s) => s.health === 'degraded').length
  const downCount = MOCK_SERVICES.filter((s) => s.health === 'down').length

  const healthScore = Math.round((healthyCount / totalServices) * 100)

  const healthScoreClass = cn(
    healthScore >= 80 ? 'health-healthy' : healthScore >= 60 ? 'health-degraded' : 'health-down',
  )

  return (
    <AppLayout title="Dashboard" description="Architecture health overview">
      <div className="space-y-4">
        {/* Health overview panel — health score primary, secondary metrics compact strip */}
        <div className="overflow-hidden rounded-xl border border-border bg-card">
          <div className="flex flex-col gap-4 p-5 sm:flex-row sm:items-center sm:gap-6">
            {/* Health score — primary signal */}
            <div className="shrink-0">
              <p className="text-xs font-medium uppercase tracking-widest text-muted-foreground">
                Health Score
              </p>
              <p className={cn('mt-1 text-4xl font-semibold tabular-nums leading-none', healthScoreClass)}>
                {healthScore}%
              </p>
              <p className="mt-1.5 text-xs text-muted-foreground">
                {healthyCount} of {totalServices} healthy
              </p>
            </div>

            <div className="hidden h-16 w-px shrink-0 bg-border sm:block" />

            {/* Health bar + legend */}
            <div className="flex flex-1 flex-col gap-2.5">
              <div
                className="flex h-2.5 overflow-hidden rounded-full bg-muted"
                role="img"
                aria-label={`Health: ${healthyCount} healthy, ${degradedCount} degraded, ${downCount} down`}
              >
                <div
                  className="bg-success transition-all"
                  style={{ width: `${(healthyCount / totalServices) * 100}%` }}
                />
                <div
                  className="bg-warning transition-all"
                  style={{ width: `${(degradedCount / totalServices) * 100}%` }}
                />
                <div
                  className="bg-critical transition-all"
                  style={{ width: `${(downCount / totalServices) * 100}%` }}
                />
              </div>
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs">
                <LegendItem colorClass="bg-success" label="Healthy" count={healthyCount} />
                <LegendItem colorClass="bg-warning" label="Degraded" count={degradedCount} />
                <LegendItem colorClass="bg-critical" label="Down" count={downCount} />
                <Link
                  to="/catalog"
                  className="ml-auto flex items-center gap-1 text-primary hover:underline"
                >
                  View all <ArrowRight className="size-3" />
                </Link>
              </div>
            </div>
          </div>

          {/* Secondary metrics strip */}
          <div className="grid grid-cols-3 divide-x divide-border border-t border-border">
            <StatStrip
              icon={<Server className="size-3.5" />}
              value={String(totalServices)}
              label="services"
              sub={`${criticalTier} critical tier`}
            />
            <StatStrip
              icon={<AlertTriangle className="size-3.5" />}
              value={String(criticalRisks + warningRisks)}
              label="risks"
              sub={`${criticalRisks} critical · ${warningRisks} warning`}
              valueClass={criticalRisks > 0 ? 'health-down' : 'health-healthy'}
            />
            <StatStrip
              icon={<Users className="size-3.5" />}
              value="5"
              label="teams"
              sub={`${orphanServices.length} unowned`}
            />
          </div>
        </div>

        <div className="grid gap-4 md:grid-cols-2">
          {/* Active risks */}
          <Card>
            <CardHeader className="pb-2">
              <div className="flex items-center justify-between">
                <CardTitle className="text-sm font-medium">Active Risks</CardTitle>
                <Link to="/risks" className="flex items-center gap-1 text-xs text-primary hover:underline">
                  View all <ArrowRight className="size-3" />
                </Link>
              </div>
            </CardHeader>
            <CardContent className="space-y-2">
              {MOCK_RISKS.slice(0, 4).map((risk) => (
                <div
                  key={risk.id}
                  className="flex items-start gap-3 rounded-lg bg-muted/40 p-3"
                >
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium leading-tight">{risk.title}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      {risk.services.join(', ')}
                    </p>
                  </div>
                  <SeverityBadge severity={risk.severity} />
                </div>
              ))}
            </CardContent>
          </Card>

          {/* Stale services */}
          <Card>
            <CardHeader className="pb-2">
              <div className="flex items-center justify-between">
                <CardTitle className="text-sm font-medium">Stale Services</CardTitle>
                <Link to="/catalog" className="flex items-center gap-1 text-xs text-primary hover:underline">
                  All services <ArrowRight className="size-3" />
                </Link>
              </div>
            </CardHeader>
            <CardContent className="space-y-2">
              {staleServices.length === 0 ? (
                <p className="text-sm text-muted-foreground">No stale services.</p>
              ) : (
                staleServices.map((svc) => <StaleServiceRow key={svc.id} service={svc} />)
              )}
            </CardContent>
          </Card>
        </div>

        {/* Activity timeline */}
        <Card>
          <CardHeader className="pb-2">
            <div className="flex items-center justify-between">
              <CardTitle className="text-sm font-medium flex items-center gap-2">
                <Activity className="size-4" />
                Recent Activity
              </CardTitle>
              <Link to="/timeline" className="flex items-center gap-1 text-xs text-primary hover:underline">
                View all <ArrowRight className="size-3" />
              </Link>
            </div>
          </CardHeader>
          <CardContent>
            <ActivityList events={MOCK_TIMELINE.slice(0, 5)} />
          </CardContent>
        </Card>
      </div>
    </AppLayout>
  )
}

function LegendItem({ colorClass, label, count }: { colorClass: string; label: string; count: number }) {
  return (
    <div className="flex items-center gap-1.5">
      <div className={cn('size-2 rounded-full', colorClass)} />
      <span className="text-muted-foreground">
        {label} <span className="font-medium text-foreground">{count}</span>
      </span>
    </div>
  )
}

function StatStrip({
  icon,
  value,
  label,
  sub,
  valueClass,
}: {
  icon: React.ReactNode
  value: string
  label: string
  sub: string
  valueClass?: string
}) {
  return (
    <div className="flex items-center gap-3 px-5 py-3">
      <span className="shrink-0 text-muted-foreground">{icon}</span>
      <div className="min-w-0">
        <div className="flex items-baseline gap-1">
          <span className={cn('text-base font-semibold tabular-nums', valueClass)}>{value}</span>
          <span className="text-xs text-muted-foreground">{label}</span>
        </div>
        <p className="truncate text-xs text-muted-foreground">{sub}</p>
      </div>
    </div>
  )
}

function StaleServiceRow({ service }: { service: Service }) {
  return (
    <div className="flex items-center gap-3 rounded-lg bg-muted/50 px-3 py-2">
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium">{service.name}</p>
        <p className="text-xs text-muted-foreground">{service.owner ?? 'No owner'}</p>
      </div>
      <div className="flex items-center gap-1 text-xs text-warning">
        <Clock className="size-3" />
        {service.lastDeploy}
      </div>
    </div>
  )
}

function ActivityList({ events }: { events: TimelineEvent[] }) {
  const typeColors: Record<string, string> = {
    deploy: 'bg-success',
    contract: 'bg-info',
    risk: 'bg-critical',
    ownership: 'bg-warning',
    dependency: 'bg-info',
  }

  return (
    <div className="space-y-0">
      {events.map((event, idx) => (
        <div key={event.id} className="flex gap-3">
          <div className="flex flex-col items-center">
            <div className={cn('size-2.5 rounded-full', typeColors[event.type] ?? 'bg-muted-foreground')} />
            {idx < events.length - 1 && <div className="w-px flex-1 bg-border" />}
          </div>
          <div className="min-w-0 pb-4">
            <p className="text-sm">{event.msg}</p>
            <p className="mt-0.5 text-xs text-muted-foreground">
              {event.service} · {event.time}
            </p>
          </div>
        </div>
      ))}
    </div>
  )
}

function SeverityBadge({ severity }: { severity: string }) {
  return (
    <Badge
      variant="outline"
      className={cn('shrink-0 border-current text-xs font-semibold uppercase', `severity-${severity}`)}
    >
      {severity}
    </Badge>
  )
}
