import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, Check, ChevronLeft, ChevronRight, LayoutGrid, List, Plus, RotateCw, Search, SlidersHorizontal } from 'lucide-react'
import { useEffect, useState } from 'react'
import { z } from 'zod'

import { useDebounce } from '#/hooks/useDebounce'
import { AppLayout } from '#/components/AppLayout'
import { RegisterServiceDrawer } from '#/components/RegisterServiceDrawer'
import { RiskScoreBadge } from '#/components/RiskScoreBadge'
import { TierBadge } from '#/components/TierBadge'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Card } from '#/components/ui/card'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '#/components/ui/dropdown-menu'
import { Input } from '#/components/ui/input'
import { Skeleton } from '#/components/ui/skeleton'
import { ApiError, apiFetch } from '#/lib/api'
import { normalizeHealth, SCM_LABEL } from '#/lib/registry-types'
import { cn, preventNavigationFromPopoverTrigger } from '#/lib/utils'

import type { PageResult, RegistryService, RegistryTeam, ScmSource, ServiceHealth } from '#/lib/registry-types'

const catalogSearchSchema = z.object({
  q: z.string().optional(),
  team: z.string().optional(),
  health: z.enum(['healthy', 'degraded', 'down']).optional(),
  source: z.string().optional(),
  tech: z.array(z.string()).optional(),
  view: z.enum(['grid', 'list']).optional(),
  page: z.number().int().min(0).optional(),
})

export const Route = createFileRoute('/_authenticated/catalog/')({
  component: CatalogPage,
  validateSearch: catalogSearchSchema,
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

const UNOWNED_SENTINEL = '__unowned__'

function useRelativeSeconds(timestamp: number | undefined): number {
  const [, forceTick] = useState(0)
  useEffect(() => {
    const id = setInterval(() => forceTick((n) => n + 1), 1000)
    return () => clearInterval(id)
  }, [])
  if (!timestamp) return 0
  return Math.max(0, Math.floor((Date.now() - timestamp) / 1000))
}

// Isolated so the 1s interval ticking this label re-renders only itself, not the
// full CatalogPage tree (up to 100 service cards/rows) that owns the query state.
function UpdatedAgo({ dataUpdatedAt }: { dataUpdatedAt: number }) {
  const updatedSecondsAgo = useRelativeSeconds(dataUpdatedAt)
  return (
    <p className="shrink-0 text-xs text-muted-foreground">
      Updated {updatedSecondsAgo < 5 ? 'just now' : `${updatedSecondsAgo}s ago`}
    </p>
  )
}

function CatalogPage() {
  const search = Route.useSearch()
  const navigate = Route.useNavigate()
  const [registerOpen, setRegisterOpen] = useState(false)
  const [moreFiltersOpen, setMoreFiltersOpen] = useState(false)

  // Free-text search is debounced (throttles keystrokes); every other filter
  // and pagination control applies immediately — it's a discrete click, not typing.
  const [query, setQuery] = useState(search.q ?? '')
  const dQuery = useDebounce(query, 500)
  useEffect(() => {
    if (dQuery !== (search.q ?? '')) {
      navigate({ search: (prev) => ({ ...prev, q: dQuery || undefined, page: undefined }), replace: true })
    }
    // Deliberately reacting only to dQuery: also depending on search.q would re-fire this
    // effect on browser back/forward before dQuery catches up (debounced), fighting the nav.
  }, [dQuery])
  // Keep the input in sync with the URL on browser back/forward navigation.
  useEffect(() => {
    if ((search.q ?? '') !== query) setQuery(search.q ?? '')
    // Deliberately reacting only to search.q — this effect exists to pull URL changes into
    // local state, not the other way around (that's the effect above).
  }, [search.q])

  const teamFilter = search.team ?? ''
  const healthFilter = search.health ?? 'all'
  const sourceFilter = (search.source ?? 'all') as ScmSource | 'all'
  const techFilter = new Set(search.tech ?? [])
  const view = search.view ?? 'grid'
  const page = search.page ?? 0

  function updateSearch(patch: Partial<z.infer<typeof catalogSearchSchema>>, resetPageNum = true) {
    navigate({
      search: (prev) => ({ ...prev, ...patch, page: resetPageNum ? undefined : (patch.page ?? prev.page) }),
      replace: true,
    })
  }

  const healthDbValue = HEALTH_OPTIONS.find((f) => f.value === healthFilter)?.dbValue

  const { data: pageResult, isLoading, error, dataUpdatedAt, refetch } = useQuery({
    queryKey: ['services', teamFilter, healthFilter, sourceFilter, [...techFilter].sort(), dQuery, page],
    queryFn: () => {
      const params = new URLSearchParams()
      if (teamFilter === UNOWNED_SENTINEL) {
        params.set('unowned', 'true')
      } else if (teamFilter) {
        params.set('teamId', teamFilter)
      }
      if (healthDbValue) params.set('health', healthDbValue)
      if (sourceFilter !== 'all') params.set('source', sourceFilter)
      techFilter.forEach((t) => params.append('techStack', t))
      if (dQuery.trim()) params.set('search', dQuery.trim())
      params.set('limit', String(LIMIT))
      params.set('offset', String(page * LIMIT))
      return apiFetch<PageResult<RegistryService>>(`/v1/registry/services?${params}`)
    },
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

  const hasActiveFilters = query !== '' || teamFilter !== '' || healthFilter !== 'all' || sourceFilter !== 'all' || techFilter.size > 0
  const moreFiltersActive = teamFilter !== '' || sourceFilter !== 'all' || techFilter.size > 0
  const moreFiltersCount = (teamFilter !== '' ? 1 : 0) + (sourceFilter !== 'all' ? 1 : 0) + techFilter.size

  function clearAllFilters() {
    setQuery('')
    navigate({ search: {}, replace: true })
  }

  const healthCounts = {
    healthy: services.filter((s) => normalizeHealth(s.healthStatus) === 'healthy').length,
    degraded: services.filter((s) => normalizeHealth(s.healthStatus) === 'degraded').length,
    down: services.filter((s) => normalizeHealth(s.healthStatus) === 'down').length,
  }

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
              onChange={(e) => setQuery(e.target.value)}
              className="pl-8 text-sm"
              aria-label="Search services"
            />
          </div>

          <DropdownMenu open={moreFiltersOpen} onOpenChange={setMoreFiltersOpen}>
            <DropdownMenuTrigger asChild>
              <Button
                variant="outline"
                className={cn(
                  'h-auto gap-1.5 px-3 py-2 text-sm shadow-sm pointer-coarse:h-11',
                  moreFiltersActive ? 'border-primary bg-primary/5 text-foreground' : 'border-input bg-background text-foreground',
                )}
                aria-label="More filters"
              >
                <SlidersHorizontal className="size-3.5" aria-hidden="true" />
                More filters
                {moreFiltersCount > 0 && (
                  <span className="flex size-4 items-center justify-center rounded-full bg-primary text-[10px] font-semibold text-primary-foreground">
                    {moreFiltersCount}
                  </span>
                )}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="max-h-96 w-64 overflow-y-auto">
              <p className="px-2 pb-1 pt-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                Team
              </p>
              {/* Capped independently of the panel's own scroll region so a large team
                  list can't push the Source/Tech stack sections out of easy reach. */}
              <div className="max-h-40 overflow-y-auto">
                <DropdownMenuItem
                  onSelect={(e) => { e.preventDefault(); updateSearch({ team: undefined }) }}
                  className="flex items-center gap-2"
                >
                  <CheckMark checked={!teamFilter} />
                  All teams
                </DropdownMenuItem>
                <DropdownMenuItem
                  onSelect={(e) => { e.preventDefault(); updateSearch({ team: UNOWNED_SENTINEL }) }}
                  className="flex items-center gap-2"
                >
                  <CheckMark checked={teamFilter === UNOWNED_SENTINEL} />
                  <AlertTriangle className="size-3.5 text-muted-foreground" aria-hidden="true" />
                  Unowned
                </DropdownMenuItem>
                {teams.map((t) => (
                  <DropdownMenuItem
                    key={t.id}
                    onSelect={(e) => { e.preventDefault(); updateSearch({ team: t.id }) }}
                    className="flex items-center gap-2"
                  >
                    <CheckMark checked={teamFilter === t.id} />
                    {t.name}
                  </DropdownMenuItem>
                ))}
              </div>
              <p className="mt-1 border-t border-border px-2 pb-1 pt-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                Source
              </p>
              {SOURCE_OPTIONS.map((opt) => (
                <DropdownMenuItem
                  key={opt.value}
                  onSelect={(e) => { e.preventDefault(); updateSearch({ source: opt.value === 'all' ? undefined : opt.value }) }}
                  className="flex items-center gap-2"
                >
                  <CheckMark checked={sourceFilter === opt.value} />
                  {opt.label}
                </DropdownMenuItem>
              ))}
              <p className="mt-1 border-t border-border px-2 pb-1 pt-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                Tech stack
              </p>
              {(techStacks ?? []).map((tech) => (
                <DropdownMenuItem
                  key={tech}
                  onSelect={(e) => {
                    e.preventDefault()
                    const next = new Set(techFilter)
                    next.has(tech) ? next.delete(tech) : next.add(tech)
                    updateSearch({ tech: next.size > 0 ? [...next] : undefined })
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
            <Button
              variant="ghost"
              onClick={() => updateSearch({ view: 'grid' }, false)}
              className={cn(
                'h-auto rounded-none px-2.5 py-2 pointer-coarse:px-3.5 pointer-coarse:py-3',
                view === 'grid' ? 'bg-primary text-primary-foreground hover:bg-primary' : 'bg-background text-muted-foreground hover:bg-muted',
              )}
              aria-label="Grid view"
              aria-pressed={view === 'grid'}
            >
              <LayoutGrid className="size-4" aria-hidden="true" />
            </Button>
            <Button
              variant="ghost"
              onClick={() => updateSearch({ view: 'list' }, false)}
              className={cn(
                'h-auto rounded-none px-2.5 py-2 pointer-coarse:px-3.5 pointer-coarse:py-3',
                view === 'list' ? 'bg-primary text-primary-foreground hover:bg-primary' : 'bg-background text-muted-foreground hover:bg-muted',
              )}
              aria-label="List view"
              aria-pressed={view === 'list'}
            >
              <List className="size-4" aria-hidden="true" />
            </Button>
          </div>

          {hasActiveFilters && (
            <Button
              variant="outline"
              onClick={clearAllFilters}
              className="h-auto px-3 py-2 text-sm text-muted-foreground hover:border-destructive hover:text-destructive"
            >
              Clear filters
            </Button>
          )}
        </div>

        {/* Health quick-filter chips + freshness indicator */}
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap gap-2">
            {(['healthy', 'degraded', 'down'] as ServiceHealth[]).map((value) => (
              <Button
                key={value}
                variant="ghost"
                onClick={() => updateSearch({ health: healthFilter === value ? undefined : value })}
                className={cn(
                  'h-auto gap-1.5 rounded-full border px-3 py-1 text-xs font-medium',
                  healthFilter === value
                    ? 'border-primary bg-primary text-primary-foreground hover:bg-primary'
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
              </Button>
            ))}
          </div>
          {!isLoading && !error && dataUpdatedAt > 0 && <UpdatedAgo dataUpdatedAt={dataUpdatedAt} />}
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
            <AlertDescription className="flex flex-wrap items-center justify-between gap-2">
              <span>
                {error.message}
                {error instanceof ApiError && ` (trace: ${error.traceId})`}
              </span>
              <Button variant="outline" size="sm" onClick={() => refetch()} className="gap-1.5">
                <RotateCw className="size-3.5" aria-hidden="true" />
                Retry
              </Button>
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
              <Button
                variant="outline"
                onClick={() => updateSearch({ page: Math.max(0, page - 1) || undefined }, false)}
                disabled={page === 0}
                className="h-auto gap-1 px-3 py-1.5 text-xs font-medium disabled:cursor-not-allowed"
              >
                <ChevronLeft className="size-3.5" aria-hidden="true" />
                Previous
              </Button>
              <Button
                variant="outline"
                onClick={() => updateSearch({ page: Math.min(totalPages - 1, page + 1) || undefined }, false)}
                disabled={page >= totalPages - 1}
                className="h-auto gap-1 px-3 py-1.5 text-xs font-medium disabled:cursor-not-allowed"
              >
                Next
                <ChevronRight className="size-3.5" aria-hidden="true" />
              </Button>
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

  return (
    <Link to="/catalog/$serviceId" params={{ serviceId: service.id }} onClick={preventNavigationFromPopoverTrigger}>
      <Card className="group h-full cursor-pointer gap-3 rounded-xl p-4 py-4 transition-all hover:shadow-md">
        {/* Header */}
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="font-semibold leading-tight group-hover:text-primary">{service.name}</span>
              <TierBadge tier={service.tier} />
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
          <RiskScoreBadge service={service} variant="inline" />
        </div>

        {/* Warning tags */}
        {(stale || isOrphan || hasBreakingChange) && (
          <div className="flex flex-wrap gap-1">
            {stale && <WarningTag label="stale" />}
            {hasBreakingChange && <WarningTag label="breaking-change" />}
            {isOrphan && <WarningTag label="orphan" />}
          </div>
        )}
      </Card>
    </Link>
  )
}

// Below `md` only Service/Health/Risk fit legibly; Source/Owner/Tech/Last-deploy join at `md`
// and up, matching the card grid's own threshold for its densest layout (`lg` for 4 columns).
// Keep this in sync with the `hidden md:*` cells in ServiceListRow below — column count must
// match at every breakpoint.
const LIST_COLS = 'grid-cols-[minmax(0,1fr)_auto_minmax(0,4.5rem)] md:grid-cols-[minmax(0,2fr)_minmax(0,1.2fr)_minmax(0,0.9fr)_minmax(0,1fr)_minmax(0,1.3fr)_minmax(0,0.9fr)_minmax(0,0.65fr)]'

function ServiceListTable({ services, teamMap }: { services: RegistryService[]; teamMap: Map<string, string> }) {
  return (
    <Card className="gap-0 overflow-hidden rounded-xl py-0">
      <div className={cn('grid gap-x-4 border-b border-border bg-muted/50 px-5 py-2.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground', LIST_COLS)}>
        <span>Service</span>
        <span>Health</span>
        <span className="hidden md:block">Source</span>
        <span className="hidden md:block">Owner</span>
        <span className="hidden md:block">Tech</span>
        <span className="hidden md:block">Last deploy</span>
        <span>Risk</span>
      </div>
      <div className="divide-y divide-border">
        {services.map((svc) => (
          <ServiceListRow key={svc.id} service={svc} teamName={teamMap.get(svc.teamId ?? '') ?? null} />
        ))}
      </div>
    </Card>
  )
}

function ServiceListRow({ service, teamName }: { service: RegistryService; teamName: string | null }) {
  const health = normalizeHealth(service.healthStatus)
  const tech = service.techStack ?? []
  const deploy = relativeTime(service.lastDeployedAt)
  const isOrphan = service.teamId === null

  return (
    <Link
      to="/catalog/$serviceId"
      params={{ serviceId: service.id }}
      className="block"
      onClick={preventNavigationFromPopoverTrigger}
    >
      <div className={cn('group grid gap-x-4 px-5 py-3 transition-colors hover:bg-muted/30', LIST_COLS)}>
        <div className="min-w-0">
          <p className="truncate font-medium group-hover:text-primary">{service.name}</p>
          <TierBadge tier={service.tier} />
        </div>
        <div className="flex items-center">
          <HealthCell health={health} />
        </div>
        <div className="hidden items-center md:flex">
          <span className="text-sm text-muted-foreground">
            {service.source ? (SCM_LABEL[service.source] ?? service.source) : '—'}
          </span>
        </div>
        <div className="hidden items-center md:flex">
          {isOrphan ? (
            <span className="inline-flex items-center gap-0.5 text-sm text-warning">
              No owner
            </span>
          ) : (
            <span className="truncate text-sm">{teamName}</span>
          )}
        </div>
        <div className="hidden flex-wrap items-center gap-1 md:flex">
          {tech.slice(0, 2).map((t) => (
            <span key={t} className="rounded-md border border-border bg-background px-1.5 py-0.5 text-xs font-medium text-muted-foreground">
              {t}
            </span>
          ))}
          {tech.length > 2 && (
            <span className="text-xs text-muted-foreground">+{tech.length - 2}</span>
          )}
        </div>
        <div className="hidden items-center md:flex">
          <span className="text-sm text-muted-foreground">{deploy ?? '—'}</span>
        </div>
        <div className="flex items-center gap-1.5">
          <RiskScoreBadge service={service} variant="bar" />
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
