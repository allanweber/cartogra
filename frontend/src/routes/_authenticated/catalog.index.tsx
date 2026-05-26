import { createFileRoute, Link } from '@tanstack/react-router'
import { AlertTriangle, Check, ChevronDown, LayoutGrid, List, Search } from 'lucide-react'
import { useMemo, useState } from 'react'

import { AppLayout } from '#/components/AppLayout'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent } from '#/components/ui/card'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '#/components/ui/dropdown-menu'
import { Input } from '#/components/ui/input'
import { MOCK_SERVICES } from '#/lib/mock-data'
import { cn } from '#/lib/utils'

import type { ScmProvider, Service, ServiceHealth } from '#/lib/mock-data'

export const Route = createFileRoute('/_authenticated/catalog/')({
  component: CatalogPage,
})

const TEAMS = ['All Teams', 'Platform', 'Security', 'Payments', 'Core', 'Data']
const HEALTH_FILTERS: Array<{ label: string; value: ServiceHealth | 'all' }> = [
  { label: 'All', value: 'all' },
  { label: 'Healthy', value: 'healthy' },
  { label: 'Degraded', value: 'degraded' },
  { label: 'Down', value: 'down' },
]
const SCM_PROVIDERS: Array<{ label: string; value: ScmProvider | 'all' }> = [
  { label: 'All SCM', value: 'all' },
  { label: 'GitHub', value: 'github' },
  { label: 'GitLab', value: 'gitlab' },
  { label: 'Azure DevOps', value: 'azure-devops' },
  { label: 'Bitbucket', value: 'bitbucket' },
]

const ALL_TECH = Array.from(new Set(MOCK_SERVICES.flatMap((s) => s.tech))).sort()

function CatalogPage() {
  const [query, setQuery] = useState('')
  const [teamFilter, setTeamFilter] = useState('All Teams')
  const [healthFilter, setHealthFilter] = useState<ServiceHealth | 'all'>('all')
  const [scmFilter, setScmFilter] = useState<ScmProvider | 'all'>('all')
  const [techFilter, setTechFilter] = useState<string[]>([])
  const [view, setView] = useState<'grid' | 'list'>('grid')

  function toggleTech(tech: string) {
    setTechFilter((prev) =>
      prev.includes(tech) ? prev.filter((t) => t !== tech) : [...prev, tech],
    )
  }

  const filtered = useMemo(() => MOCK_SERVICES.filter((s) => {
    const matchesQuery =
      query === '' ||
      s.name.toLowerCase().includes(query.toLowerCase()) ||
      s.tech.some((t) => t.toLowerCase().includes(query.toLowerCase()))
    const matchesTeam = teamFilter === 'All Teams' || s.owner === teamFilter
    const matchesHealth = healthFilter === 'all' || s.health === healthFilter
    const matchesScm = scmFilter === 'all' || s.scmProvider === scmFilter
    const matchesTech =
      techFilter.length === 0 || techFilter.every((t) => s.tech.includes(t))
    return matchesQuery && matchesTeam && matchesHealth && matchesScm && matchesTech
  }), [query, teamFilter, healthFilter, scmFilter, techFilter])

  const healthCounts = useMemo(() => ({
    healthy: MOCK_SERVICES.filter((s) => s.health === 'healthy').length,
    degraded: MOCK_SERVICES.filter((s) => s.health === 'degraded').length,
    down: MOCK_SERVICES.filter((s) => s.health === 'down').length,
  }), [])

  return (
    <AppLayout title="Service Catalog" description="Search and inspect your services">
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
                  'flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1',
                  healthFilter === f.value
                    ? 'border-primary bg-primary text-primary-foreground'
                    : 'border-border bg-background text-muted-foreground hover:border-primary/50 hover:text-foreground',
                )}
              >
                {f.value !== 'all' && (
                  <span
                    role="img"
                    aria-label={`${f.label} health`}
                    className={cn(
                      'size-1.5 rounded-full',
                      f.value === 'healthy' && 'bg-[oklch(0.55_0.18_145)]',
                      f.value === 'degraded' && 'bg-[oklch(0.65_0.18_60)]',
                      f.value === 'down' && 'bg-[oklch(0.58_0.23_28)]',
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
          <div className="relative min-w-45 flex-1">
            <Search className="absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input
              placeholder="Search services or tech..."
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              className="pl-8 text-sm"
              aria-label="Search services"
            />
          </div>

          {/* Team filter */}
          <select
            value={teamFilter}
            onChange={(e) => setTeamFilter(e.target.value)}
            aria-label="Filter by team"
            className="rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground shadow-sm focus:outline-none focus:ring-1 focus:ring-ring"
          >
            {TEAMS.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>

          {/* SCM provider filter */}
          <select
            value={scmFilter}
            onChange={(e) => setScmFilter(e.target.value as ScmProvider | 'all')}
            aria-label="Filter by SCM provider"
            className="rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground shadow-sm focus:outline-none focus:ring-1 focus:ring-ring"
          >
            {SCM_PROVIDERS.map((p) => (
              <option key={p.value} value={p.value}>
                {p.label}
              </option>
            ))}
          </select>

          {/* Tech stack multi-select */}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                className={cn(
                  'flex items-center gap-1.5 rounded-md border px-3 py-2 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
                  techFilter.length > 0
                    ? 'border-primary bg-primary/5 text-foreground'
                    : 'border-input bg-background text-foreground',
                )}
                aria-label="Filter by tech stack"
              >
                {techFilter.length === 0
                  ? 'Tech Stack'
                  : `Tech (${techFilter.length})`}
                <ChevronDown className="size-3.5 text-muted-foreground" aria-hidden="true" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="max-h-64 overflow-y-auto">
              {ALL_TECH.map((tech) => (
                <DropdownMenuItem
                  key={tech}
                  onSelect={(e) => {
                    e.preventDefault()
                    toggleTech(tech)
                  }}
                  className="flex items-center gap-2"
                >
                  <span
                    className={cn(
                      'flex size-4 items-center justify-center rounded border',
                      techFilter.includes(tech)
                        ? 'border-primary bg-primary text-primary-foreground'
                        : 'border-border',
                    )}
                    aria-hidden="true"
                  >
                    {techFilter.includes(tech) && <Check className="size-2.5" />}
                  </span>
                  {tech}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>

          {/* Tech filter clear */}
          {techFilter.length > 0 && (
            <button
              onClick={() => setTechFilter([])}
              className="text-xs text-muted-foreground underline-offset-2 hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-sm"
            >
              Clear tech
            </button>
          )}

          {/* Grid/list toggle */}
          <div className="flex overflow-hidden rounded-md border border-border">
            <button
              onClick={() => setView('grid')}
              className={cn(
                'flex items-center px-2.5 py-2 transition-colors focus-visible:outline-none focus-visible:ring-inset focus-visible:ring-2 focus-visible:ring-ring',
                view === 'grid'
                  ? 'bg-primary text-primary-foreground'
                  : 'bg-background text-muted-foreground hover:bg-muted',
              )}
              aria-label="Grid view"
              aria-pressed={view === 'grid'}
            >
              <LayoutGrid className="size-4" aria-hidden="true" />
            </button>
            <button
              onClick={() => setView('list')}
              className={cn(
                'flex items-center px-2.5 py-2 transition-colors focus-visible:outline-none focus-visible:ring-inset focus-visible:ring-2 focus-visible:ring-ring',
                view === 'list'
                  ? 'bg-primary text-primary-foreground'
                  : 'bg-background text-muted-foreground hover:bg-muted',
              )}
              aria-label="List view"
              aria-pressed={view === 'list'}
            >
              <List className="size-4" aria-hidden="true" />
            </button>
          </div>
        </div>

        {/* Results count */}
        <p className="text-xs text-muted-foreground" aria-live="polite">
          {filtered.length} of {MOCK_SERVICES.length} services
        </p>

        {/* Service cards */}
        {filtered.length === 0 ? (
          <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-16 text-center">
            <Search className="mb-3 size-8 text-muted-foreground/50" aria-hidden="true" />
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
      role="img"
      aria-label={`${health} health`}
      className={cn(
        'inline-block size-2 rounded-full',
        health === 'healthy' && 'bg-[oklch(0.55_0.18_145)]',
        health === 'degraded' && 'bg-[oklch(0.65_0.18_60)]',
        health === 'down' && 'bg-[oklch(0.58_0.23_28)]',
      )}
    />
  )
}

function HealthBadge({ health }: { health: ServiceHealth }) {
  return (
    <Badge
      variant="outline"
      className={cn('gap-1.5 text-xs capitalize border-current', `bg-health-${health}`)}
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
          ? 'bg-[oklch(0.97_0.05_28)] text-[oklch(0.50_0.20_28)] dark:bg-[oklch(0.27_0.05_28)] dark:text-[oklch(0.72_0.18_28)]'
          : score >= 40
            ? 'bg-[oklch(0.97_0.06_80)] text-[oklch(0.50_0.15_60)] dark:bg-[oklch(0.27_0.06_80)] dark:text-[oklch(0.78_0.14_60)]'
            : 'bg-muted text-muted-foreground',
      )}
    >
      {score}
    </span>
  )
}

function ServiceCard({ service }: { service: Service }) {
  const isOrphan = service.warnings.includes('orphan')
  return (
    <Link to="/catalog/$serviceId" params={{ serviceId: service.id }}>
      <Card
        className={cn(
          'group h-full cursor-pointer transition-all hover:border-primary/50 hover:shadow-md',
          isOrphan && 'ring-2 ring-[oklch(0.65_0.18_60)] ring-offset-1',
        )}
      >
        <CardContent className="flex h-full flex-col gap-3 p-4">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <p className="font-semibold leading-tight group-hover:text-primary">
                {service.name}
              </p>
              <p className="mt-0.5 text-xs text-muted-foreground">
                {service.owner ?? (
                  <span className="text-[oklch(0.55_0.18_60)] dark:text-[oklch(0.78_0.14_60)]">No owner</span>
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
                  className="flex items-center gap-0.5 rounded-md border border-[oklch(0.72_0.14_60)] bg-[oklch(0.97_0.06_80)] px-1.5 py-0.5 text-[10px] font-medium text-[oklch(0.50_0.15_60)] dark:border-[oklch(0.60_0.15_60)] dark:bg-[oklch(0.27_0.06_80)] dark:text-[oklch(0.78_0.14_60)]"
                >
                  <AlertTriangle className="size-2.5" aria-hidden="true" />
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
  const isOrphan = service.warnings.includes('orphan')
  return (
    <Link to="/catalog/$serviceId" params={{ serviceId: service.id }}>
      <div
        className={cn(
          'group flex items-center gap-4 rounded-lg border border-border bg-card p-3 transition-all hover:border-primary/50 hover:shadow-sm',
          isOrphan && 'ring-2 ring-[oklch(0.65_0.18_60)] ring-offset-1',
        )}
      >
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
