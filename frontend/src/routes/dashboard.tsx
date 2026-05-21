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

export const Route = createFileRoute('/dashboard')({
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
        {/* Top stat cards */}
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <StatCard
            label="Health Score"
            value={`${healthScore}%`}
            sub={`${healthyCount} healthy`}
            variant={healthScore >= 80 ? 'healthy' : healthScore >= 60 ? 'degraded' : 'down'}
          />
          <StatCard
            label="Services"
            value={totalServices}
            sub={`${criticalTier} critical tier`}
            icon={<Server className="size-4" />}
          />
          <StatCard
            label="Active Risks"
            value={criticalRisks + warningRisks}
            sub={`${criticalRisks} critical`}
            variant={criticalRisks > 0 ? 'down' : 'healthy'}
            icon={<AlertTriangle className="size-4" />}
          />
          <StatCard
            label="Teams"
            value={5}
            sub={`${orphanServices.length} orphaned services`}
            icon={<Users className="size-4" />}
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
            <div className="mb-3 flex h-2.5 overflow-hidden rounded-full bg-muted">
              <div
                className="bg-emerald-500 transition-all"
                style={{ width: `${(healthyCount / totalServices) * 100}%` }}
              />
              <div
                className="bg-amber-500 transition-all"
                style={{ width: `${(degradedCount / totalServices) * 100}%` }}
              />
              <div
                className="bg-red-500 transition-all"
                style={{ width: `${(downCount / totalServices) * 100}%` }}
              />
            </div>
            <div className="flex gap-4 text-xs">
              <LegendItem color="bg-emerald-500" label="Healthy" count={healthyCount} />
              <LegendItem color="bg-amber-500" label="Degraded" count={degradedCount} />
              <LegendItem color="bg-red-500" label="Down" count={downCount} />
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
                    risk.severity === 'critical'
                      ? 'border-l-red-500 bg-red-50 dark:bg-red-950/30'
                      : risk.severity === 'warning'
                        ? 'border-l-amber-500 bg-amber-50 dark:bg-amber-950/30'
                        : 'border-l-blue-400 bg-blue-50 dark:bg-blue-950/30',
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

function StatCard({
  label,
  value,
  sub,
  variant,
  icon,
}: {
  label: string
  value: string | number
  sub: string
  variant?: 'healthy' | 'degraded' | 'down'
  icon?: React.ReactNode
}) {
  return (
    <Card>
      <CardContent className="pt-5">
        <div className="flex items-center justify-between">
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">{label}</p>
          {icon && <span className="text-muted-foreground">{icon}</span>}
        </div>
        <p
          className={cn(
            'mt-2 text-3xl font-bold',
            variant === 'healthy' && 'text-emerald-600 dark:text-emerald-400',
            variant === 'degraded' && 'text-amber-600 dark:text-amber-400',
            variant === 'down' && 'text-red-600 dark:text-red-400',
          )}
        >
          {value}
        </p>
        <p className="mt-1 text-xs text-muted-foreground">{sub}</p>
      </CardContent>
    </Card>
  )
}

function LegendItem({ color, label, count }: { color: string; label: string; count: number }) {
  return (
    <div className="flex items-center gap-1.5">
      <div className={cn('size-2 rounded-full', color)} />
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
      <div className="flex items-center gap-1 text-xs text-amber-600 dark:text-amber-400">
        <Clock className="size-3" />
        {service.lastDeploy}
      </div>
    </div>
  )
}

function ActivityList({ events }: { events: TimelineEvent[] }) {
  const typeColors: Record<string, string> = {
    deploy: 'bg-emerald-500',
    contract: 'bg-violet-500',
    risk: 'bg-red-500',
    ownership: 'bg-amber-500',
    dependency: 'bg-blue-500',
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
        severity === 'critical' && 'border-red-300 text-red-600 dark:border-red-700 dark:text-red-400',
        severity === 'warning' && 'border-amber-300 text-amber-600 dark:border-amber-700 dark:text-amber-400',
        severity === 'info' && 'border-blue-300 text-blue-600 dark:border-blue-700 dark:text-blue-400',
      )}
    >
      {severity}
    </Badge>
  )
}
