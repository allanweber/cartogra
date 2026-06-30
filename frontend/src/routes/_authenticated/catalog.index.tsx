import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, Check, ChevronDown, ChevronLeft, ChevronRight, LayoutGrid, List, Search } from 'lucide-react'
import { useState } from 'react'

import { useDebounce } from '#/hooks/useDebounce'
import { AppLayout } from '#/components/AppLayout'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent } from '#/components/ui/card'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '#/components/ui/dropdown-menu'
import { Input } from '#/components/ui/input'
import { Skeleton } from '#/components/ui/skeleton'
import { ApiError, apiFetch } from '#/lib/api'
import { SCM_LABEL, normalizeHealth } from '#/lib/registry-types'
import { cn } from '#/lib/utils'

import type { PageResult, RegistryService, RegistryTeam, ServiceHealth } from '#/lib/registry-types'

export const Route = createFileRoute('/_authenticated/catalog/')({
  component: CatalogPage,
})

const LIMIT = 100

const HEALTH_FILTERS: Array<{ label: string; value: ServiceHealth | 'all'; dbValue?: string }> = [
  { label: 'All', value: 'all' },
  { label: 'Healthy', value: 'healthy', dbValue: 'healthy' },
  { label: 'Degraded', value: 'degraded', dbValue: 'degraded' },
  { label: 'Down', value: 'down', dbValue: 'unhealthy' },
]

const SCM_SOURCES: Array<{ label: string; value: string }> = [
  { label: 'All SCM', value: '' },
  { label: 'GitHub', value: 'github' },
  { label: 'GitLab', value: 'gitlab' },
  { label: 'Azure DevOps', value: 'azuredevops' },
  { label: 'Bitbucket', value: 'bitbucket' },
]

function CatalogPage() {
  const [query, setQuery] = useState('')
  const [teamFilter, setTeamFilter] = useState('')
  const [healthFilter, setHealthFilter] = useState<ServiceHealth | 'all'>('all')
  const [scmFilter, setScmFilter] = useState('')
  const [techFilter, setTechFilter] = useState<Set<string>>(new Set())
  const [view, setView] = useState<'grid' | 'list'>('grid')
  const [page, setPage] = useState(0)

  const dQuery      = useDebounce(query,       500)
  const dTeam       = useDebounce(teamFilter,  500)
  const dHealth     = useDebounce(healthFilter, 500)
  const dTech       = useDebounce(techFilter,   500)
  const dScm        = useDebounce(scmFilter,    500)
  const dPage       = useDebounce(page,         500)

  const healthDbValue = HEALTH_FILTERS.find((f) => f.value === dHealth)?.dbValue

  const { data: pageResult, isLoading, error } = useQuery({
    queryKey: ['services', dTeam, dHealth, [...dTech].sort(), dQuery, dScm, dPage],
    queryFn: () => {
      const params = new URLSearchParams()
      if (dTeam) params.set('teamId', dTeam)
      if (healthDbValue) params.set('health', healthDbValue)
      dTech.forEach((t) => params.append('techStack', t))
      if (dQuery.trim()) params.set('search', dQuery.trim())
      if (dScm) params.set('source', dScm)
      params.set('limit', String(LIMIT))
      params.set('offset', String(dPage * LIMIT))
      return apiFetch<PageResult<RegistryService>>(`/v1/services?${params}`)
    },
  })

  const { data: teamsPage } = useQuery({
    queryKey: ['teams'],
    queryFn: () => apiFetch<PageResult<RegistryTeam>>('/v1/teams?limit=200'),
  })

  const { data: techStacks } = useQuery({
    queryKey: ['tech-stacks'],
    queryFn: () => apiFetch<string[]>('/v1/services/tech-stacks'),
  })

  const teams = teamsPage?.items ?? []
  const teamMap = new Map(teams.map((t) => [t.id, t.name]))
  const services = pageResult?.items ?? []
  const total = pageResult?.total ?? 0
  const totalPages = Math.ceil(total / LIMIT)

  function resetPage() {
    setPage(0)
  }

  const hasActiveFilters = query !== '' || teamFilter !== '' || healthFilter !== 'all' || scmFilter !== '' || techFilter.size > 0

  function clearAllFilters() {
    setQuery('')
    setTeamFilter('')
    setHealthFilter('all')
    setScmFilter('')
    setTechFilter(new Set())
    setPage(0)
  }

  return (
    <AppLayout title="Service Catalog" description="Search and inspect your services">
      <div className="space-y-4">
        {/* Health filter chips */}
        <div className="flex flex-wrap gap-2">
          {HEALTH_FILTERS.map((f) => (
            <button
              key={f.value}
              onClick={() => { setHealthFilter(f.value); resetPage() }}
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
              {healthFilter === f.value && dHealth === f.value && !isLoading && (
                <span className="rounded-full bg-primary-foreground/20 px-1.5 py-0.5 text-[10px] text-primary-foreground">
                  {total}
                </span>
              )}
            </button>
          ))}
        </div>

        {/* Search + filters bar */}
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative min-w-45 flex-1">
            <Search className="absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input
              placeholder="Search services..."
              value={query}
              onChange={(e) => { setQuery(e.target.value); resetPage() }}
              className="pl-8 text-sm"
              aria-label="Search services"
            />
          </div>

          {/* Team filter */}
          <select
            value={teamFilter}
            onChange={(e) => { setTeamFilter(e.target.value); resetPage() }}
            aria-label="Filter by team"
            className="rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground shadow-sm focus:outline-none focus:ring-1 focus:ring-ring"
          >
            <option value="">All Teams</option>
            {teams.map((t) => (
              <option key={t.id} value={t.id}>{t.name}</option>
            ))}
          </select>

          {/* SCM filter */}
          <select
            value={scmFilter}
            onChange={(e) => { setScmFilter(e.target.value); resetPage() }}
            aria-label="Filter by SCM provider"
            className="rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground shadow-sm focus:outline-none focus:ring-1 focus:ring-ring"
          >
            {SCM_SOURCES.map((s) => (
              <option key={s.value} value={s.value}>{s.label}</option>
            ))}
          </select>

          {/* Tech filter */}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                className={cn(
                  'flex items-center gap-1.5 rounded-md border px-3 py-2 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
                  techFilter.size > 0
                    ? 'border-primary bg-primary/5 text-foreground'
                    : 'border-input bg-background text-foreground',
                )}
                aria-label="Filter by tech stack"
              >
                {techFilter.size > 0 ? `Tech Stack (${techFilter.size})` : 'Tech Stack'}
                <ChevronDown className="size-3.5 text-muted-foreground" aria-hidden="true" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="max-h-64 overflow-y-auto">
              {techFilter.size > 0 && (
                <DropdownMenuItem
                  onSelect={() => { setTechFilter(new Set()); resetPage() }}
                  className="text-muted-foreground"
                >
                  Clear filter
                </DropdownMenuItem>
              )}
              {(techStacks ?? []).map((tech) => (
                <DropdownMenuItem
                  key={tech}
                  onSelect={(e) => {
                    e.preventDefault()
                    setTechFilter((prev) => {
                      const next = new Set(prev)
                      next.has(tech) ? next.delete(tech) : next.add(tech)
                      return next
                    })
                    resetPage()
                  }}
                  className="flex items-center gap-2"
                >
                  <span
                    className={cn(
                      'flex size-4 items-center justify-center rounded border',
                      techFilter.has(tech) ? 'border-primary bg-primary text-primary-foreground' : 'border-border',
                    )}
                    aria-hidden="true"
                  >
                    {techFilter.has(tech) && <Check className="size-2.5" />}
                  </span>
                  {tech}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>

          {/* View toggle */}
          <div className="flex overflow-hidden rounded-md border border-border">
            <button
              onClick={() => setView('grid')}
              className={cn(
                'flex items-center px-2.5 py-2 transition-colors focus-visible:outline-none focus-visible:ring-inset focus-visible:ring-2 focus-visible:ring-ring',
                view === 'grid' ? 'bg-primary text-primary-foreground' : 'bg-background text-muted-foreground hover:bg-muted',
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
                view === 'list' ? 'bg-primary text-primary-foreground' : 'bg-background text-muted-foreground hover:bg-muted',
              )}
              aria-label="List view"
              aria-pressed={view === 'list'}
            >
              <List className="size-4" aria-hidden="true" />
            </button>
          </div>

          {hasActiveFilters && (
            <button
              onClick={clearAllFilters}
              className="rounded-md border border-border px-3 py-2 text-sm text-muted-foreground transition-colors hover:border-destructive hover:text-destructive"
            >
              Clear filters
            </button>
          )}
        </div>

        {/* Results count */}
        <p className="text-xs text-muted-foreground" aria-live="polite">
          {isLoading ? 'Loading...' : `${total} service${total !== 1 ? 's' : ''}`}
        </p>

        {/* Content */}
        {isLoading ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} className="h-40 w-full rounded-xl" />
            ))}
          </div>
        ) : error ? (
          <Alert variant="destructive">
            <AlertDescription>
              {error.message}
              {error instanceof ApiError && ` (trace: ${error.traceId})`}
            </AlertDescription>
          </Alert>
        ) : services.length === 0 ? (
          <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-16 text-center">
            <Search className="mb-3 size-8 text-muted-foreground/50" aria-hidden="true" />
            <p className="text-sm font-medium">No services match your filters</p>
            <p className="mt-1 text-xs text-muted-foreground">Try adjusting the search or filters</p>
          </div>
        ) : view === 'grid' ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {services.map((svc) => (
              <ServiceCard key={svc.id} service={svc} teamName={teamMap.get(svc.teamId ?? '') ?? null} />
            ))}
          </div>
        ) : (
          <div className="space-y-4">
            {services.map((svc) => (
              <ServiceListRow key={svc.id} service={svc} teamName={teamMap.get(svc.teamId ?? '') ?? null} />
            ))}
          </div>
        )}

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between pt-2">
            <p className="text-xs text-muted-foreground">
              Page {page + 1} of {totalPages}
            </p>
            <div className="flex gap-2">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className="flex items-center gap-1 rounded-md border border-border px-3 py-1.5 text-xs font-medium transition-colors hover:bg-muted disabled:cursor-not-allowed disabled:opacity-40"
              >
                <ChevronLeft className="size-3.5" aria-hidden="true" />
                Previous
              </button>
              <button
                onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1}
                className="flex items-center gap-1 rounded-md border border-border px-3 py-1.5 text-xs font-medium transition-colors hover:bg-muted disabled:cursor-not-allowed disabled:opacity-40"
              >
                Next
                <ChevronRight className="size-3.5" aria-hidden="true" />
              </button>
            </div>
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

function ServiceCard({ service, teamName }: { service: RegistryService; teamName: string | null }) {
  const health = normalizeHealth(service.healthStatus)
  const isOrphan = service.teamId === null
  const tech = service.techStack ?? []

  return (
    <Link to="/catalog/$serviceId" params={{ serviceId: service.id }}>
      <Card
        className={cn(
          'group h-full cursor-pointer transition-all hover:shadow-md',
          health === 'healthy' && 'ring-[oklch(0.55_0.18_145)]',
          health === 'degraded' && 'ring-[oklch(0.65_0.18_60)]',
          health === 'down' && 'ring-[oklch(0.58_0.23_28)]',
          isOrphan && health === 'healthy' && 'ring-[oklch(0.65_0.18_60)]',
        )}
      >
        <CardContent className="flex h-full flex-col gap-3 p-4">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <p className="font-semibold leading-tight group-hover:text-primary">{service.name}</p>
              <p className="mt-0.5 text-xs text-muted-foreground">
                {teamName ?? (
                  <span className="text-[oklch(0.55_0.18_60)] dark:text-[oklch(0.78_0.14_60)]">No owner</span>
                )}
              </p>
            </div>
            <HealthBadge health={health} />
          </div>

          {service.description && (
            <p className="line-clamp-2 flex-1 text-xs text-muted-foreground">{service.description}</p>
          )}

          {tech.length > 0 && (
            <div className="flex flex-wrap gap-1">
              {tech.map((t) => (
                <span key={t} className="rounded-md bg-muted px-1.5 py-0.5 text-[11px] font-medium text-muted-foreground">
                  {t}
                </span>
              ))}
            </div>
          )}

          <div className="flex items-center justify-between border-t border-border pt-2">
            <div className="flex items-center gap-3 text-xs text-muted-foreground">
              {service.source && (
                <span>{SCM_LABEL[service.source] ?? service.source}</span>
              )}
              {service.lastDeployedAt && (
                <span>{new Date(service.lastDeployedAt).toLocaleDateString()}</span>
              )}
            </div>
            {isOrphan && (
              <span className="flex items-center gap-0.5 rounded-md border border-[oklch(0.72_0.14_60)] bg-[oklch(0.97_0.06_80)] px-1.5 py-0.5 text-[10px] font-medium text-[oklch(0.50_0.15_60)] dark:border-[oklch(0.60_0.15_60)] dark:bg-[oklch(0.27_0.06_80)] dark:text-[oklch(0.78_0.14_60)]">
                <AlertTriangle className="size-2.5" aria-hidden="true" />
                orphan
              </span>
            )}
          </div>
        </CardContent>
      </Card>
    </Link>
  )
}

function ServiceListRow({ service, teamName }: { service: RegistryService; teamName: string | null }) {
  const health = normalizeHealth(service.healthStatus)
  const isOrphan = service.teamId === null
  const tech = service.techStack ?? []

  return (
    <Link to="/catalog/$serviceId" params={{ serviceId: service.id }} className="block">
      <div
        className={cn(
          'group flex items-center gap-4 rounded-lg border bg-card p-4 transition-all hover:shadow-sm',
          isOrphan ? 'border-[oklch(0.65_0.18_60)] hover:border-[oklch(0.55_0.18_60)]' : 'border-border hover:border-primary/50',
        )}
      >
        <HealthDot health={health} />
        <div className="min-w-0 flex-1">
          <p className="font-medium group-hover:text-primary">{service.name}</p>
          <p className="text-xs text-muted-foreground">{teamName ?? 'No owner'}</p>
        </div>
        <div className="hidden flex-wrap gap-1 sm:flex">
          {tech.slice(0, 2).map((t) => (
            <span key={t} className="rounded-md bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">
              {t}
            </span>
          ))}
        </div>
        {service.lastDeployedAt && (
          <span className="hidden text-xs text-muted-foreground sm:block">
            {new Date(service.lastDeployedAt).toLocaleDateString()}
          </span>
        )}
      </div>
    </Link>
  )
}
