import { createFileRoute, Link } from '@tanstack/react-router'
import { AlertTriangle, LayoutGrid, List, Search } from 'lucide-react'
import { useState } from 'react'

import { AppLayout } from '#/components/AppLayout'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent } from '#/components/ui/card'
import { Input } from '#/components/ui/input'
import { MOCK_SERVICES } from '#/lib/mock-data'
import { cn } from '#/lib/utils'

import type { Service, ServiceHealth } from '#/lib/mock-data'

export const Route = createFileRoute('/catalog')({
  component: CatalogPage,
})

const TEAMS = ['All Teams', 'Platform', 'Security', 'Payments', 'Core', 'Data']
const HEALTH_FILTERS: Array<{ label: string; value: ServiceHealth | 'all' }> = [
  { label: 'All', value: 'all' },
  { label: 'Healthy', value: 'healthy' },
  { label: 'Degraded', value: 'degraded' },
  { label: 'Down', value: 'down' },
]

function CatalogPage() {
  const [query, setQuery] = useState('')
  const [teamFilter, setTeamFilter] = useState('All Teams')
  const [healthFilter, setHealthFilter] = useState<ServiceHealth | 'all'>('all')
  const [view, setView] = useState<'grid' | 'list'>('grid')

  const filtered = MOCK_SERVICES.filter((s) => {
    const matchesQuery =
      query === '' ||
      s.name.toLowerCase().includes(query.toLowerCase()) ||
      s.tech.some((t) => t.toLowerCase().includes(query.toLowerCase()))
    const matchesTeam =
      teamFilter === 'All Teams' ||
      s.owner === teamFilter
    const matchesHealth =
      healthFilter === 'all' || s.health === healthFilter
    return matchesQuery && matchesTeam && matchesHealth
  })

  const healthCounts = {
    healthy: MOCK_SERVICES.filter((s) => s.health === 'healthy').length,
    degraded: MOCK_SERVICES.filter((s) => s.health === 'degraded').length,
    down: MOCK_SERVICES.filter((s) => s.health === 'down').length,
  }

  return (
    <AppLayout title="Service Catalog" description="Browse, filter, and inspect your services">
      <div className="space-y-4">
        {/* Health summary chips */}
        <div className="flex flex-wrap gap-2">
          {HEALTH_FILTERS.map((f) => {
            const count =
              f.value === 'all'
                ? MOCK_SERVICES.length
                : healthCounts[f.value]
            return (
              <button
                key={f.value}
                onClick={() => setHealthFilter(f.value)}
                className={cn(
                  'flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium transition-colors',
                  healthFilter === f.value
                    ? 'border-primary bg-primary text-primary-foreground'
                    : 'border-border bg-background text-muted-foreground hover:border-primary/50 hover:text-foreground',
                )}
              >
                {f.value !== 'all' && (
                  <span
                    className={cn(
                      'size-1.5 rounded-full',
                      f.value === 'healthy' && 'bg-emerald-500',
                      f.value === 'degraded' && 'bg-amber-500',
                      f.value === 'down' && 'bg-red-500',
                    )}
                  />
                )}
                {f.label}
                <span
                  className={cn(
                    'rounded-full px-1.5 py-0.5 text-[10px]',
                    healthFilter === f.value
                      ? 'bg-primary-foreground/20 text-primary-foreground'
                      : 'bg-muted text-muted-foreground',
                  )}
                >
                  {count}
                </span>
              </button>
            )
          })}
        </div>

        {/* Search + filters bar */}
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative flex-1" style={{ minWidth: '180px' }}>
            <Search className="absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="Search services or tech..."
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              className="pl-8 text-sm"
            />
          </div>
          <select
            value={teamFilter}
            onChange={(e) => setTeamFilter(e.target.value)}
            className="rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground shadow-sm focus:outline-none focus:ring-1 focus:ring-ring"
          >
            {TEAMS.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
          <div className="flex overflow-hidden rounded-md border border-border">
            <button
              onClick={() => setView('grid')}
              className={cn(
                'flex items-center px-2.5 py-2 transition-colors',
                view === 'grid'
                  ? 'bg-primary text-primary-foreground'
                  : 'bg-background text-muted-foreground hover:bg-muted',
              )}
              aria-label="Grid view"
            >
              <LayoutGrid className="size-4" />
            </button>
            <button
              onClick={() => setView('list')}
              className={cn(
                'flex items-center px-2.5 py-2 transition-colors',
                view === 'list'
                  ? 'bg-primary text-primary-foreground'
                  : 'bg-background text-muted-foreground hover:bg-muted',
              )}
              aria-label="List view"
            >
              <List className="size-4" />
            </button>
          </div>
        </div>

        {/* Results count */}
        <p className="text-xs text-muted-foreground">
          {filtered.length} of {MOCK_SERVICES.length} services
        </p>

        {/* Service cards */}
        {filtered.length === 0 ? (
          <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-16 text-center">
            <Search className="mb-3 size-8 text-muted-foreground/50" />
            <p className="text-sm font-medium">No services match your filters</p>
            <p className="mt-1 text-xs text-muted-foreground">Try adjusting the search or filters</p>
          </div>
        ) : view === 'grid' ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {filtered.map((svc) => (
              <ServiceCard key={svc.id} service={svc} />
            ))}
          </div>
        ) : (
          <div className="space-y-2">
            {filtered.map((svc) => (
              <ServiceListRow key={svc.id} service={svc} />
            ))}
          </div>
        )}
      </div>
    </AppLayout>
  )
}

function HealthDot({ health }: { health: ServiceHealth }) {
  return (
    <span
      className={cn(
        'inline-block size-2 rounded-full',
        health === 'healthy' && 'bg-emerald-500',
        health === 'degraded' && 'bg-amber-500',
        health === 'down' && 'bg-red-500',
      )}
    />
  )
}

function HealthBadge({ health }: { health: ServiceHealth }) {
  return (
    <Badge
      variant="outline"
      className={cn(
        'gap-1.5 text-xs capitalize',
        health === 'healthy' &&
          'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-800 dark:bg-emerald-950/50 dark:text-emerald-400',
        health === 'degraded' &&
          'border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-800 dark:bg-amber-950/50 dark:text-amber-400',
        health === 'down' &&
          'border-red-200 bg-red-50 text-red-700 dark:border-red-800 dark:bg-red-950/50 dark:text-red-400',
      )}
    >
      <HealthDot health={health} />
      {health}
    </Badge>
  )
}

function RiskScore({ score }: { score: number }) {
  return (
    <span
      className={cn(
        'rounded-md px-1.5 py-0.5 text-xs font-semibold',
        score >= 70
          ? 'bg-red-100 text-red-700 dark:bg-red-950/50 dark:text-red-400'
          : score >= 40
            ? 'bg-amber-100 text-amber-700 dark:bg-amber-950/50 dark:text-amber-400'
            : 'bg-muted text-muted-foreground',
      )}
    >
      {score}
    </span>
  )
}

function ServiceCard({ service }: { service: Service }) {
  return (
    <Link to="/catalog/$serviceId" params={{ serviceId: service.id }}>
      <Card className="group h-full cursor-pointer transition-all hover:border-primary/50 hover:shadow-md">
        <CardContent className="flex h-full flex-col gap-3 p-4">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <p className="font-semibold leading-tight group-hover:text-primary">
                {service.name}
              </p>
              <p className="mt-0.5 text-xs text-muted-foreground">
                {service.owner ?? (
                  <span className="text-amber-600 dark:text-amber-400">No owner</span>
                )}
              </p>
            </div>
            <HealthBadge health={service.health} />
          </div>

          <p className="line-clamp-2 flex-1 text-xs text-muted-foreground">
            {service.description}
          </p>

          <div className="flex flex-wrap gap-1">
            {service.tech.map((t) => (
              <span
                key={t}
                className="rounded-md bg-muted px-1.5 py-0.5 text-[11px] font-medium text-muted-foreground"
              >
                {t}
              </span>
            ))}
          </div>

          <div className="flex items-center justify-between border-t border-border pt-2">
            <div className="flex items-center gap-3 text-xs text-muted-foreground">
              <span>{service.deps} deps</span>
              <span>{service.lastDeploy}</span>
            </div>
            <div className="flex items-center gap-1.5">
              {service.warnings.map((w) => (
                <span
                  key={w}
                  className="flex items-center gap-0.5 rounded-md border border-amber-200 bg-amber-50 px-1.5 py-0.5 text-[10px] font-medium text-amber-700 dark:border-amber-800 dark:bg-amber-950/50 dark:text-amber-400"
                >
                  <AlertTriangle className="size-2.5" />
                  {w}
                </span>
              ))}
              <RiskScore score={service.riskScore} />
            </div>
          </div>
        </CardContent>
      </Card>
    </Link>
  )
}

function ServiceListRow({ service }: { service: Service }) {
  return (
    <Link to="/catalog/$serviceId" params={{ serviceId: service.id }}>
      <div className="group flex items-center gap-4 rounded-lg border border-border bg-card p-3 transition-all hover:border-primary/50 hover:shadow-sm">
        <HealthDot health={service.health} />
        <div className="min-w-0 flex-1">
          <p className="font-medium group-hover:text-primary">{service.name}</p>
          <p className="text-xs text-muted-foreground">{service.owner ?? 'No owner'}</p>
        </div>
        <div className="hidden flex-wrap gap-1 sm:flex">
          {service.tech.slice(0, 2).map((t) => (
            <span
              key={t}
              className="rounded-md bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground"
            >
              {t}
            </span>
          ))}
        </div>
        <div className="flex items-center gap-2">
          <span className="hidden text-xs text-muted-foreground sm:block">{service.lastDeploy}</span>
          <RiskScore score={service.riskScore} />
        </div>
      </div>
    </Link>
  )
}
