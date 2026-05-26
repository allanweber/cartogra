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

  return (
    <AppLayout title="Dashboard" description="Architecture health overview">
      <div className="space-y-6">
        {/* Metrics bar — single row, 4 inline metrics separated by dividers */}
        <div className="grid grid-cols-2 divide-x divide-y divide-border overflow-hidden rounded-xl border border-border bg-card sm:grid-cols-4 sm:divide-y-0">
          <MetricCell
            label="Health Score"
            value={`${healthScore}%`}
            sub={`${healthyCount} of ${totalServices} healthy`}
            valueClass={cn(
              healthScore >= 80 ? 'health-healthy' : healthScore >= 60 ? 'health-degraded' : 'health-down',
            )}
          />
          <MetricCell
            label="Services"
            value={String(totalServices)}
            sub={`${criticalTier} critical tier`}
            icon={<Server className="size-3.5" />}
          />
          <MetricCell
            label="Active Risks"
            value={String(criticalRisks + warningRisks)}
            sub={`${criticalRisks} critical · ${warningRisks} warning`}
            valueClass={criticalRisks > 0 ? 'health-down' : 'health-healthy'}
            icon={<AlertTriangle className="size-3.5" />}
          />
          <MetricCell
            label="Teams"
            value="5"
            sub={`${orphanServices.length} orphaned services`}
            icon={<Users className="size-3.5" />}
          />
        </div>

        {/* Health summary bar */}
        <Card>
          <CardHeader className="pb-2">
            <div className="flex items-center justify-between">
              <CardTitle className="text-sm font-medium text-muted-foreground">
                Service Health
              </CardTitle>
              <Link to="/catalog" className="flex items-center gap-1 text-xs text-primary hover:underline">
                View all <ArrowRight className="size-3" />
              </Link>
            </div>
          </CardHeader>
          <CardContent>
            <div className="mb-3 flex h-2.5 overflow-hidden rounded-full bg-muted" role="img" aria-label={`Health: ${healthyCount} healthy, ${degradedCount} degraded, ${downCount} down`}>
              <div
                className="bg-[oklch(0.55_0.18_145)] transition-all"
                style={{ width: `${(healthyCount / totalServices) * 100}%` }}
              />
              <div
                className="bg-[oklch(0.65_0.18_60)] transition-all"
                style={{ width: `${(degradedCount / totalServices) * 100}%` }}
              />
              <div
                className="bg-[oklch(0.58_0.23_28)] transition-all"
                style={{ width: `${(downCount / totalServices) * 100}%` }}
              />
            </div>
            <div className="flex gap-4 text-xs">
              <LegendItem colorClass="bg-[oklch(0.55_0.18_145)]" label="Healthy" count={healthyCount} />
              <LegendItem colorClass="bg-[oklch(0.65_0.18_60)]" label="Degraded" count={degradedCount} />
              <LegendItem colorClass="bg-[oklch(0.58_0.23_28)]" label="Down" count={downCount} />
            </div>
          </CardContent>
        </Card>

        <div className="grid gap-4 md:grid-cols-2">
          {/* Recent risks */}
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
                  className={cn(
                    'flex items-start gap-3 rounded-lg border-l-2 p-3',
                    `bg-severity-${risk.severity}`,
                  )}
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
                  View all <ArrowRight className="size-3" />
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

function MetricCell({
  label,
  value,
  sub,
  valueClass,
  icon,
}: {
  label: string
  value: string
  sub: string
  valueClass?: string
  icon?: React.ReactNode
}) {
  return (
    <div className="flex flex-col gap-1 px-5 py-4">
      <div className="flex items-center justify-between">
        <p className="text-[11px] font-medium uppercase tracking-widest text-muted-foreground">{label}</p>
        {icon && <span className="text-muted-foreground">{icon}</span>}
      </div>
      <p className={cn('text-2xl font-semibold tabular-nums', valueClass)}>{value}</p>
      <p className="text-xs text-muted-foreground">{sub}</p>
    </div>
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

function StaleServiceRow({ service }: { service: Service }) {
  return (
    <div className="flex items-center gap-3 rounded-lg bg-muted/50 px-3 py-2">
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium">{service.name}</p>
        <p className="text-xs text-muted-foreground">{service.owner ?? 'No owner'}</p>
      </div>
      <div className="flex items-center gap-1 text-xs text-[oklch(0.65_0.18_60)] dark:text-[oklch(0.78_0.14_60)]">
        <Clock className="size-3" />
        {service.lastDeploy}
      </div>
    </div>
  )
}

function ActivityList({ events }: { events: TimelineEvent[] }) {
  const typeColors: Record<string, string> = {
    deploy: 'bg-[oklch(0.55_0.18_145)]',
    contract: 'bg-primary',
    risk: 'bg-[oklch(0.58_0.23_28)]',
    ownership: 'bg-[oklch(0.65_0.18_60)]',
    dependency: 'bg-[oklch(0.60_0.18_240)]',
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
      className={cn(
        'shrink-0 text-[10px] font-semibold uppercase',
        severity === 'critical' && 'border-[oklch(0.58_0.23_28)] text-[oklch(0.58_0.23_28)] dark:border-[oklch(0.68_0.18_28)] dark:text-[oklch(0.68_0.18_28)]',
        severity === 'warning' && 'border-[oklch(0.65_0.18_60)] text-[oklch(0.65_0.18_60)] dark:border-[oklch(0.75_0.16_60)] dark:text-[oklch(0.75_0.16_60)]',
        severity === 'info' && 'border-[oklch(0.60_0.18_240)] text-[oklch(0.60_0.18_240)] dark:border-[oklch(0.70_0.16_240)] dark:text-[oklch(0.70_0.16_240)]',
      )}
    >
      {severity}
    </Badge>
  )
}
