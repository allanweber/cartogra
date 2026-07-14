import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, Check, ChevronDown, ChevronLeft, ChevronRight, LayoutGrid, List, Plus, Search } from 'lucide-react'
import { useState } from 'react'

import { useDebounce } from '#/hooks/useDebounce'
import { AppLayout } from '#/components/AppLayout'
import { RegisterServiceDrawer } from '#/components/RegisterServiceDrawer'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '#/components/ui/dropdown-menu'
import { Input } from '#/components/ui/input'
import { Skeleton } from '#/components/ui/skeleton'
import { ApiError, apiFetch } from '#/lib/api'
import { normalizeHealth, SCM_LABEL } from '#/lib/registry-types'
import { cn } from '#/lib/utils'

import type { PageResult, RegistryService, RegistryTeam, ScmSource, ServiceHealth } from '#/lib/registry-types'

export const Route = createFileRoute('/_authenticated/catalog/')({
  component: CatalogPage,
})

const LIMIT = 100

const HEALTH_OPTIONS: Array<{ label: string; value: ServiceHealth | 'all'; dbValue?: string }> = [
  { label: 'All health', value: 'all' },
  { label: 'Healthy', value: 'healthy', dbValue: 'HEALTHY' },
  { label: 'Degraded', value: 'degraded', dbValue: 'DEGRADED' },
  { label: 'Down', value: 'down', dbValue: 'UNHEALTHY' },
]

const SOURCE_OPTIONS: Array<{ label: string; value: ScmSource | 'all' }> = [
  { label: 'All sources', value: 'all' },
  { label: 'Kubernetes', value: 'kubernetes' },
  { label: 'GitHub', value: 'github' },
  { label: 'GitLab', value: 'gitlab' },
  { label: 'Azure DevOps', value: 'azuredevops' },
  { label: 'Bitbucket', value: 'bitbucket' },
]

function relativeTime(dateStr: string | null): string | null {
  if (!dateStr) return null
  const secs = Math.floor((Date.now() - new Date(dateStr).getTime()) / 1000)
  if (secs < 3600) return `${Math.max(1, Math.floor(secs / 60))}m ago`
  if (secs < 86400) return `${Math.floor(secs / 3600)}h ago`
  return `${Math.floor(secs / 86400)}d ago`
}

function isStale(dateStr: string | null): boolean {
  if (!dateStr) return false
  return Date.now() - new Date(dateStr).getTime() > 14 * 86400 * 1000
}

function computeRiskScore(service: RegistryService): number {
  const health = normalizeHealth(service.healthStatus)
  let score = health === 'down' ? 60 : health === 'degraded' ? 35 : 5
  if (service.lastDeployedAt) {
    const days = (Date.now() - new Date(service.lastDeployedAt).getTime()) / 86400000
    score += Math.min(30, Math.floor(days * 1.5))
  } else {
    score += 18
  }
  if (!service.teamId) score += 8
  return Math.min(99, Math.max(1, score))
}

function riskColorClass(score: number): string {
  if (score >= 70) return 'text-critical'
  if (score >= 35) return 'text-warning'
  return 'text-success'
}

function riskBarClass(score: number): string {
  if (score >= 70) return 'bg-critical'
  if (score >= 35) return 'bg-warning'
  return 'bg-success'
}

function CatalogPage() {
  const [query, setQuery] = useState('')
  const [teamFilter, setTeamFilter] = useState('')
  const [healthFilter, setHealthFilter] = useState<ServiceHealth | 'all'>('all')
  const [sourceFilter, setSourceFilter] = useState<ScmSource | 'all'>('all')
  const [techFilter, setTechFilter] = useState<Set<string>>(new Set())
  const [view, setView] = useState<'grid' | 'list'>('grid')
  const [page, setPage] = useState(0)
  const [registerOpen, setRegisterOpen] = useState(false)

  const dQuery  = useDebounce(query, 500)
  const dTeam   = useDebounce(teamFilter, 500)
  const dHealth = useDebounce(healthFilter, 500)
  const dSource = useDebounce(sourceFilter, 500)
  const dTech   = useDebounce(techFilter, 500)
  const dPage   = useDebounce(page, 500)

  const healthDbValue = HEALTH_OPTIONS.find((f) => f.value === dHealth)?.dbValue

  const UNOWNED_SENTINEL = '__unowned__'

  const { data: pageResult, isLoading, error } = useQuery({
    queryKey: ['services', dTeam, dHealth, dSource, [...dTech].sort(), dQuery, dPage],
    queryFn: () => {
      const params = new URLSearchParams()
      if (dTeam === UNOWNED_SENTINEL) {
        params.set('unowned', 'true')
      } else if (dTeam) {
        params.set('teamId', dTeam)
      }
      if (healthDbValue) params.set('health', healthDbValue)
      if (dSource !== 'all') params.set('source', dSource)
      dTech.forEach((t) => params.append('techStack', t))
      if (dQuery.trim()) params.set('search', dQuery.trim())
      params.set('limit', String(LIMIT))
      params.set('offset', String(dPage * LIMIT))
      return apiFetch<PageResult<RegistryService>>(`/v1/registry/services?${params}`)
    },
    refetchInterval: 5000,
  })

  const { data: teamsPage } = useQuery({
    queryKey: ['teams'],
    queryFn: () => apiFetch<PageResult<RegistryTeam>>('/v1/registry/teams?limit=200'),
  })

  const { data: techStacks } = useQuery({
    queryKey: ['tech-stacks'],
    queryFn: () => apiFetch<string[]>('/v1/registry/services/tech-stacks'),
  })

  const teams = teamsPage?.items ?? []
  const teamMap = new Map(teams.map((t) => [t.id, t.name]))
  const services = pageResult?.items ?? []
  const total = pageResult?.total ?? 0
  const totalPages = Math.ceil(total / LIMIT)

  function resetPage() { setPage(0) }

  const hasActiveFilters = query !== '' || teamFilter !== '' || healthFilter !== 'all' || sourceFilter !== 'all' || techFilter.size > 0

  function clearAllFilters() {
    setQuery('')
    setTeamFilter('')
    setHealthFilter('all')
    setSourceFilter('all')
    setTechFilter(new Set())
    setPage(0)
  }

  const healthCounts = {
    healthy: services.filter((s) => normalizeHealth(s.healthStatus) === 'healthy').length,
    degraded: services.filter((s) => normalizeHealth(s.healthStatus) === 'degraded').length,
    down: services.filter((s) => normalizeHealth(s.healthStatus) === 'down').length,
  }

  const selectedTeamName = teamFilter === UNOWNED_SENTINEL
    ? 'Unowned'
    : (teams.find((t) => t.id === teamFilter)?.name ?? 'All teams')
  const selectedHealthLabel = HEALTH_OPTIONS.find((f) => f.value === healthFilter)?.label ?? 'All health'
  const selectedSourceLabel = SOURCE_OPTIONS.find((f) => f.value === sourceFilter)?.label ?? 'All sources'
  const selectedTechLabel = techFilter.size > 0 ? `All tech (${techFilter.size})` : 'All tech'

  const pageDescription = isLoading ? undefined : `${total} service${total !== 1 ? 's' : ''}`

  return (
    <AppLayout
      title="Service Catalog"
      description={pageDescription}
      actions={
        <Button onClick={() => setRegisterOpen(true)} size="sm" className="gap-1.5">
          <Plus className="size-3.5" aria-hidden="true" />
          Register service
        </Button>
      }
    >
      <div className="space-y-4">
        {/* Search + filter bar */}
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative min-w-44 max-w-64 flex-1">
            <Search className="absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input
              placeholder="Search services..."
              value={query}
              onChange={(e) => { setQuery(e.target.value); resetPage() }}
              className="pl-8 text-sm"
              aria-label="Search services"
            />
          </div>

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                className={cn(
                  'flex items-center gap-1.5 rounded-md border px-3 py-2 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
                  teamFilter ? 'border-primary bg-primary/5 text-foreground' : 'border-input bg-background text-foreground',
                )}
                aria-label="Filter by team"
              >
                {selectedTeamName}
                <ChevronDown className="size-3.5 text-muted-foreground" aria-hidden="true" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="max-h-64 overflow-y-auto">
              <DropdownMenuItem onSelect={() => { setTeamFilter(''); resetPage() }} className="flex items-center gap-2">
                <CheckMark checked={!teamFilter} />
                All teams
              </DropdownMenuItem>
              <DropdownMenuItem onSelect={() => { setTeamFilter(UNOWNED_SENTINEL); resetPage() }} className="flex items-center gap-2">
                <CheckMark checked={teamFilter === UNOWNED_SENTINEL} />
                <AlertTriangle className="size-3.5 text-muted-foreground" aria-hidden="true" />
                Unowned
              </DropdownMenuItem>
              {teams.map((t) => (
                <DropdownMenuItem key={t.id} onSelect={() => { setTeamFilter(t.id); resetPage() }} className="flex items-center gap-2">
                  <CheckMark checked={teamFilter === t.id} />
                  {t.name}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                className={cn(
                  'flex items-center gap-1.5 rounded-md border px-3 py-2 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
                  healthFilter !== 'all' ? 'border-primary bg-primary/5 text-foreground' : 'border-input bg-background text-foreground',
                )}
                aria-label="Filter by health"
              >
                {selectedHealthLabel}
                <ChevronDown className="size-3.5 text-muted-foreground" aria-hidden="true" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start">
              {HEALTH_OPTIONS.map((opt) => (
                <DropdownMenuItem key={opt.value} onSelect={() => { setHealthFilter(opt.value); resetPage() }} className="flex items-center gap-2">
                  <CheckMark checked={healthFilter === opt.value} />
                  {opt.label}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                className={cn(
                  'flex items-center gap-1.5 rounded-md border px-3 py-2 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
                  sourceFilter !== 'all' ? 'border-primary bg-primary/5 text-foreground' : 'border-input bg-background text-foreground',
                )}
                aria-label="Filter by source"
              >
                {selectedSourceLabel}
                <ChevronDown className="size-3.5 text-muted-foreground" aria-hidden="true" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start">
              {SOURCE_OPTIONS.map((opt) => (
                <DropdownMenuItem key={opt.value} onSelect={() => { setSourceFilter(opt.value); resetPage() }} className="flex items-center gap-2">
                  <CheckMark checked={sourceFilter === opt.value} />
                  {opt.label}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                className={cn(
                  'flex items-center gap-1.5 rounded-md border px-3 py-2 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
                  techFilter.size > 0 ? 'border-primary bg-primary/5 text-foreground' : 'border-input bg-background text-foreground',
                )}
                aria-label="Filter by tech stack"
              >
                {selectedTechLabel}
                <ChevronDown className="size-3.5 text-muted-foreground" aria-hidden="true" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="max-h-64 overflow-y-auto">
              {techFilter.size > 0 && (
                <DropdownMenuItem onSelect={() => { setTechFilter(new Set()); resetPage() }} className="text-muted-foreground">
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
                  <CheckMark checked={techFilter.has(tech)} />
                  {tech}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>

          <div className="ml-auto flex overflow-hidden rounded-md border border-border">
            <button
              onClick={() => setView('grid')}
              className={cn(
                'flex items-center px-2.5 py-2 transition-colors focus-visible:outline-none focus-visible:ring-inset focus-visible:ring-2 focus-visible:ring-ring pointer-coarse:px-3.5 pointer-coarse:py-3',
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
                'flex items-center px-2.5 py-2 transition-colors focus-visible:outline-none focus-visible:ring-inset focus-visible:ring-2 focus-visible:ring-ring pointer-coarse:px-3.5 pointer-coarse:py-3',
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

        {/* Health quick-filter chips */}
        <div className="flex flex-wrap gap-2">
          {(['healthy', 'degraded', 'down'] as ServiceHealth[]).map((value) => (
            <button
              key={value}
              onClick={() => { setHealthFilter(healthFilter === value ? 'all' : value); resetPage() }}
              className={cn(
                'flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                healthFilter === value
                  ? 'border-primary bg-primary text-primary-foreground'
                  : 'border-border bg-background text-muted-foreground hover:border-primary/50 hover:text-foreground',
              )}
            >
              <span
                className={cn(
                  'size-1.5 rounded-full',
                  value === 'healthy' && 'bg-success',
                  value === 'degraded' && 'bg-warning',
                  value === 'down' && 'bg-critical',
                )}
              />
              {value.charAt(0).toUpperCase() + value.slice(1)} ({healthCounts[value]})
            </button>
          ))}
        </div>

        {/* Content */}
        {isLoading ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {Array.from({ length: 8 }).map((_, i) => (
              <Skeleton key={i} className="h-44 w-full rounded-xl" />
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
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {services.map((svc) => (
              <ServiceCard key={svc.id} service={svc} teamName={teamMap.get(svc.teamId ?? '') ?? null} />
            ))}
          </div>
        ) : (
          <ServiceListTable services={services} teamMap={teamMap} />
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

      <RegisterServiceDrawer open={registerOpen} onOpenChange={setRegisterOpen} />
    </AppLayout>
  )
}

function ServiceCard({ service, teamName }: { service: RegistryService; teamName: string | null }) {
  const health = normalizeHealth(service.healthStatus)
  const tech = service.techStack ?? []
  const tags = service.tags ?? []
  const deploy = relativeTime(service.lastDeployedAt)
  const stale = isStale(service.lastDeployedAt)
  const isOrphan = service.teamId === null
  const hasBreakingChange = tags.includes('breaking-change')
  const risk = computeRiskScore(service)

  return (
    <Link to="/catalog/$serviceId" params={{ serviceId: service.id }}>
      <div className="group flex h-full cursor-pointer flex-col gap-3 rounded-xl border border-border bg-card p-4 transition-all hover:shadow-md">
        {/* Header */}
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="font-semibold leading-tight group-hover:text-primary">{service.name}</span>
              {service.tier === 'CRITICAL' && (
                <span className="text-xs font-semibold uppercase tracking-wide text-critical">CRITICAL</span>
              )}
            </div>
            <p className="mt-0.5 text-xs text-muted-foreground">
              Team:{' '}
              {isOrphan ? (
                <span className="inline-flex items-center gap-0.5 text-warning">
                  <AlertTriangle className="size-3" aria-hidden="true" />
                  No owner
                </span>
              ) : (
                teamName
              )}
            </p>
          </div>
          <HealthLabel health={health} />
        </div>

        {/* Tech */}
        {tech.length > 0 && (
          <div className="flex flex-wrap gap-1">
            {tech.map((t) => (
              <span key={t} className="rounded-md border border-border bg-background px-1.5 py-0.5 text-xs font-medium text-muted-foreground">
                {t}
              </span>
            ))}
          </div>
        )}

        {/* Footer */}
        <div className="mt-auto flex items-center justify-between border-t border-border pt-2">
          <div className="flex flex-col gap-0.5">
            <span className="text-xs text-muted-foreground">
              {deploy ? `Deploy: ${deploy}` : 'Never deployed'}
            </span>
            {service.source && (
              <span className="text-xs text-muted-foreground">
                {SCM_LABEL[service.source] ?? service.source}
              </span>
            )}
          </div>
          <span className={cn('text-sm font-semibold tabular-nums', riskColorClass(risk))}>
            {risk}
          </span>
        </div>

        {/* Warning tags */}
        {(stale || isOrphan || hasBreakingChange) && (
          <div className="flex flex-wrap gap-1">
            {stale && <WarningTag label="stale" />}
            {hasBreakingChange && <WarningTag label="breaking-change" />}
            {isOrphan && <WarningTag label="orphan" />}
          </div>
        )}
      </div>
    </Link>
  )
}

const LIST_COLS = 'grid-cols-[minmax(0,2fr)_minmax(0,1.2fr)_minmax(0,0.9fr)_minmax(0,1fr)_minmax(0,1.3fr)_minmax(0,0.9fr)_minmax(0,0.65fr)]'

function ServiceListTable({ services, teamMap }: { services: RegistryService[]; teamMap: Map<string, string> }) {
  return (
    <div className="overflow-hidden rounded-xl border border-border bg-card">
      <div className={cn('grid gap-x-4 border-b border-border bg-muted/50 px-5 py-2.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground', LIST_COLS)}>
        <span>Service</span>
        <span>Health</span>
        <span>Source</span>
        <span>Owner</span>
        <span>Tech</span>
        <span>Last deploy</span>
        <span>Risk</span>
      </div>
      <div className="divide-y divide-border">
        {services.map((svc) => (
          <ServiceListRow key={svc.id} service={svc} teamName={teamMap.get(svc.teamId ?? '') ?? null} />
        ))}
      </div>
    </div>
  )
}

function ServiceListRow({ service, teamName }: { service: RegistryService; teamName: string | null }) {
  const health = normalizeHealth(service.healthStatus)
  const tech = service.techStack ?? []
  const deploy = relativeTime(service.lastDeployedAt)
  const isOrphan = service.teamId === null
  const risk = computeRiskScore(service)

  return (
    <Link to="/catalog/$serviceId" params={{ serviceId: service.id }} className="block">
      <div className={cn('group grid gap-x-4 px-5 py-3 transition-colors hover:bg-muted/30', LIST_COLS)}>
        <div className="min-w-0">
          <p className="truncate font-medium group-hover:text-primary">{service.name}</p>
          {service.tier === 'CRITICAL' && (
            <span className="text-xs font-semibold uppercase tracking-wide text-critical">CRITICAL</span>
          )}
        </div>
        <div className="flex items-center">
          <HealthCell health={health} />
        </div>
        <div className="flex items-center">
          <span className="text-sm text-muted-foreground">
            {service.source ? (SCM_LABEL[service.source] ?? service.source) : '—'}
          </span>
        </div>
        <div className="flex items-center">
          {isOrphan ? (
            <span className="inline-flex items-center gap-0.5 text-sm text-warning">
              No owner
            </span>
          ) : (
            <span className="truncate text-sm">{teamName}</span>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-1">
          {tech.slice(0, 2).map((t) => (
            <span key={t} className="rounded-md border border-border bg-background px-1.5 py-0.5 text-xs font-medium text-muted-foreground">
              {t}
            </span>
          ))}
          {tech.length > 2 && (
            <span className="text-xs text-muted-foreground">+{tech.length - 2}</span>
          )}
        </div>
        <div className="flex items-center">
          <span className="text-sm text-muted-foreground">{deploy ?? '—'}</span>
        </div>
        <div className="flex items-center gap-1.5">
          <span className={cn('min-w-[2ch] text-sm font-semibold tabular-nums', riskColorClass(risk))}>
            {risk}
          </span>
          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
            <div
              className={cn('h-full rounded-full transition-all', riskBarClass(risk))}
              style={{ width: `${risk}%` }}
            />
          </div>
        </div>
      </div>
    </Link>
  )
}

function HealthDot({ health }: { health: ServiceHealth }) {
  return (
    <span
      role="img"
      aria-label={`${health} health`}
      className={cn(
        'inline-block size-2 shrink-0 rounded-full',
        health === 'healthy' && 'bg-success',
        health === 'degraded' && 'bg-warning',
        health === 'down' && 'bg-critical',
      )}
    />
  )
}

function HealthLabel({ health }: { health: ServiceHealth }) {
  return (
    <span
      className={cn(
        'flex shrink-0 items-center gap-1 text-xs font-medium',
        health === 'healthy' && 'text-success',
        health === 'degraded' && 'text-warning',
        health === 'down' && 'text-critical',
      )}
    >
      <HealthDot health={health} />
      {health.charAt(0).toUpperCase() + health.slice(1)}
    </span>
  )
}

function HealthCell({ health }: { health: ServiceHealth }) {
  return (
    <div
      className={cn(
        'flex w-full items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium',
        health === 'healthy' && 'text-success',
        health === 'degraded' && 'text-warning',
        health === 'down' && 'text-critical',
      )}
    >
      <HealthDot health={health} />
      {health.charAt(0).toUpperCase() + health.slice(1)}
    </div>
  )
}

function WarningTag({ label }: { label: string }) {
  return (
    <span className="flex items-center gap-0.5 rounded border border-warning bg-warning-subtle px-1.5 py-0.5 text-xs font-medium text-warning">
      <AlertTriangle className="size-2.5" aria-hidden="true" />
      {label}
    </span>
  )
}

function CheckMark({ checked }: { checked: boolean }) {
  return (
    <span
      className={cn(
        'flex size-4 shrink-0 items-center justify-center rounded border',
        checked ? 'border-primary bg-primary text-primary-foreground' : 'border-border',
      )}
      aria-hidden="true"
    >
      {checked && <Check className="size-2.5" />}
    </span>
  )
}
