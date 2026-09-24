import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { Activity, AlertTriangle, Clock, Pencil, Shield, Zap } from 'lucide-react'
import { useRef, useState } from 'react'
import { z } from 'zod'

import { AppLayout } from '#/components/AppLayout'
import { DependenciesList } from '#/components/DependenciesList'
import { EditServiceDrawer } from '#/components/EditServiceDrawer'
import { RiskScoreRing } from '#/components/RiskScoreBadge'
import { TierBadge } from '#/components/TierBadge'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'
import { Skeleton } from '#/components/ui/skeleton'
import { ApiError, apiFetch } from '#/lib/api'
import { normalizeHealth, SCM_LABEL } from '#/lib/registry-types'
import { computeRiskScore } from '#/lib/risk-score'
import { useAuthStore } from '#/stores/useAuthStore'
import { cn } from '#/lib/utils'

import type { PageResult, RegistryService, RegistryTeam, ServiceHealth } from '#/lib/registry-types'

type TabId = 'overview' | 'dependencies' | 'contracts' | 'activity'

export const Route = createFileRoute('/_authenticated/catalog/$serviceId')({
  component: ServiceDetailPage,
  validateSearch: z.object({
    tab: z.enum(['overview', 'dependencies', 'contracts', 'activity']).optional(),
  }),
})

const TABS: { id: TabId; label: string }[] = [
  { id: 'overview', label: 'Overview' },
  { id: 'dependencies', label: 'Dependencies' },
  { id: 'contracts', label: 'Contracts' },
  { id: 'activity', label: 'Activity' },
]

function relativeTime(dateStr: string): string {
  const ms = Date.now() - new Date(dateStr).getTime()
  const mins = Math.floor(ms / 60000)
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  return `${Math.floor(hrs / 24)}d ago`
}

function healthDotClass(health: ServiceHealth): string {
  if (health === 'down') return 'bg-critical'
  if (health === 'degraded') return 'bg-warning'
  return 'bg-success'
}

function healthTextClass(health: ServiceHealth): string {
  if (health === 'down') return 'text-critical'
  if (health === 'degraded') return 'text-warning'
  return 'text-success'
}

interface Insight {
  icon: React.ReactNode
  label: string
  description: string
  severity: 'critical' | 'warning' | 'info'
}

function buildInsights(service: RegistryService, riskScore: number): Insight[] {
  const insights: Insight[] = []
  const health = normalizeHealth(service.healthStatus)

  if (health === 'down') {
    insights.push({
      icon: <Zap className="size-4" />,
      label: 'Service Down',
      description: 'Health checks are failing',
      severity: 'critical',
    })
  }

  if (service.lastDeployedAt) {
    const days = (Date.now() - new Date(service.lastDeployedAt).getTime()) / 86400000
    if (days > 7) {
      insights.push({
        icon: <Clock className="size-4" />,
        label: 'Stale',
        description: `Last deploy ${Math.floor(days)}d ago — deps may drift`,
        severity: 'warning',
      })
    }
  }

  if (riskScore >= 70) {
    insights.push({
      icon: <AlertTriangle className="size-4" />,
      label: 'High Risk',
      description: `Risk score ${riskScore}/100`,
      severity: 'warning',
    })
  }

  return insights
}

function ServiceDetailPage() {
  const { serviceId } = Route.useParams()
  const search = Route.useSearch()
  const navigate = Route.useNavigate()
  const tab = search.tab ?? 'overview'
  const setTab = (next: TabId) => navigate({ search: { tab: next === 'overview' ? undefined : next }, replace: true })
  const tabRefs = useRef<Record<TabId, HTMLButtonElement | null>>({
    overview: null,
    dependencies: null,
    contracts: null,
    activity: null,
  })
  const [editOpen, setEditOpen] = useState(false)
  const isAdmin = useAuthStore((s) => s.user?.roles.includes('ADMIN') ?? false)

  const { data: service, isLoading, error } = useQuery({
    queryKey: ['service', serviceId],
    queryFn: () => apiFetch<RegistryService>(`/v1/registry/services/${serviceId}`),
  })

  const { data: teamsPage } = useQuery({
    queryKey: ['teams'],
    queryFn: () => apiFetch<PageResult<RegistryTeam>>('/v1/registry/teams?limit=200'),
  })

  const { data: myTeamIds } = useQuery({
    queryKey: ['teams', 'mine'],
    queryFn: () => apiFetch<string[]>('/v1/registry/teams/mine'),
    enabled: !isAdmin,
  })

  const teamMap = new Map((teamsPage?.items ?? []).map((t) => [t.id, t.name]))

  if (isLoading) {
    return (
      <AppLayout title="Loading..." eyebrow="Service Catalog">
        <div className="space-y-5">
          <Skeleton className="h-4 w-48" />
          <Skeleton className="h-28 w-full rounded-xl" />
          <div className="flex gap-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-20 flex-1 rounded-lg" />
            ))}
          </div>
        </div>
      </AppLayout>
    )
  }

  if (error || !service) {
    return (
      <AppLayout title="Error" eyebrow="Service Catalog">
        <Alert variant="destructive">
          <AlertDescription>
            {error?.message ?? 'Service not found.'}
            {error instanceof ApiError && ` (trace: ${error.traceId})`}
          </AlertDescription>
        </Alert>
      </AppLayout>
    )
  }

  const health = normalizeHealth(service.healthStatus)
  const teamName = teamMap.get(service.teamId ?? '') ?? null
  const tech = service.techStack ?? []
  const riskScore = computeRiskScore(service)
  const insights = buildInsights(service, riskScore)
  const lastDeploy = service.lastDeployedAt ? relativeTime(service.lastDeployedAt) : null
  const canEdit =
    isAdmin || (!!service.teamId && (myTeamIds ?? []).includes(service.teamId))

  return (
    <AppLayout
      title={service.name}
      eyebrow="Service Catalog"
      actions={
        canEdit && (
          <Button size="sm" onClick={() => setEditOpen(true)} className="gap-1.5">
            <Pencil className="size-3.5" />
            Edit
          </Button>
        )
      }
    >
      <div className="space-y-4">
        {/* Breadcrumb */}
        <nav className="flex items-center gap-1.5 text-sm text-muted-foreground">
          <Link to="/catalog" className="hover:text-foreground">
            Service Catalog
          </Link>
          <span>/</span>
          <span className="text-foreground">{service.name}</span>
        </nav>

        {/* Header card */}
        <Card>
          <CardContent className="flex items-start justify-between gap-4 px-6 py-5">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="text-2xl font-bold">{service.name}</h1>
                <TierBadge tier={service.tier} />
                <span className={cn('inline-flex items-center gap-1.5', healthTextClass(health))}>
                  <span className={cn('size-2 rounded-full', healthDotClass(health))} aria-hidden="true" />
                  <span className="capitalize">{health}</span>
                </span>
              </div>
              <div className="mt-2 flex flex-wrap items-center gap-4 text-sm text-muted-foreground">
                {teamName && (
                  <span>
                    Owner:{' '}
                    <Link
                      to="/teams"
                      className="font-medium text-foreground hover:text-primary hover:underline"
                    >
                      {teamName}
                    </Link>
                  </span>
                )}
                {lastDeploy && (
                  <span>
                    Last deploy:{' '}
                    <span className="font-medium text-foreground">{lastDeploy}</span>
                  </span>
                )}
              </div>
            </div>
            <div className="flex shrink-0 flex-col items-center gap-1">
              <span className="text-xs text-muted-foreground">Risk Score</span>
              <RiskScoreRing service={service} />
            </div>
          </CardContent>
        </Card>

        {/* Active Insights */}
        {insights.length > 0 && (
          <section aria-label="Active Insights">
            <p className="mb-2 text-sm font-medium">Active Insights</p>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              {insights.map((insight) => (
                <InsightCard key={insight.label} insight={insight} />
              ))}
            </div>
          </section>
        )}

        {/* Two-column: left (tabs+content) + right sidebar */}
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1fr_320px]">
          {/* Left column */}
          <div className="min-w-0 space-y-4">
            {/* Tab bar */}
            <div
              role="tablist"
              className="flex border-b border-border"
              onKeyDown={(e) => {
                if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
                e.preventDefault()
                const idx = TABS.findIndex((t) => t.id === tab)
                const nextIdx = e.key === 'ArrowRight'
                  ? (idx + 1) % TABS.length
                  : (idx - 1 + TABS.length) % TABS.length
                const nextTab = TABS[nextIdx]
                setTab(nextTab.id)
                tabRefs.current[nextTab.id]?.focus()
              }}
            >
              {TABS.map((t) => (
                <Button
                  key={t.id}
                  ref={(el) => { tabRefs.current[t.id] = el }}
                  variant="ghost"
                  id={`tab-${t.id}`}
                  role="tab"
                  tabIndex={tab === t.id ? 0 : -1}
                  aria-selected={tab === t.id}
                  aria-controls={`panel-${t.id}`}
                  onClick={() => setTab(t.id)}
                  className={cn(
                    'h-auto rounded-none px-4 py-2.5 text-sm font-medium transition-colors hover:bg-transparent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring',
                    tab === t.id
                      ? '-mb-px border-b-2 border-primary text-foreground'
                      : 'text-muted-foreground hover:text-foreground',
                  )}
                >
                  {t.label}
                </Button>
              ))}
            </div>

            {/* Overview */}
            {tab === 'overview' && (
              <div id="panel-overview" role="tabpanel" aria-labelledby="tab-overview" className="space-y-4">
                {service.description && (
                  <Card>
                    <CardHeader className="pb-2 pt-5">
                      <CardTitle className="text-sm font-semibold">Description</CardTitle>
                    </CardHeader>
                    <CardContent className="pb-5 pt-0 text-sm text-foreground">
                      {service.description}
                    </CardContent>
                  </Card>
                )}

                <Card>
                  <CardHeader className="pb-2 pt-5">
                    <CardTitle className="text-sm font-semibold">Tech Stack</CardTitle>
                  </CardHeader>
                  <CardContent className="pb-5 pt-0">
                    {tech.length === 0 ? (
                      <p className="text-sm text-muted-foreground">No tech stack defined.</p>
                    ) : (
                      <div className="flex flex-wrap gap-2">
                        {tech.map((t) => (
                          <span
                            key={t}
                            className="rounded-md border border-border bg-background px-2 py-0.5 text-xs font-medium text-foreground"
                          >
                            {t}
                          </span>
                        ))}
                      </div>
                    )}
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader className="pb-2 pt-5">
                    <CardTitle className="text-sm font-semibold">Details</CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-5 pb-5 pt-0">

                    {/* Identity */}
                    <MetaSection>
                      <MetaField label="Service ID" value={service.id} mono />
                      {service.source && <MetaField label="Source" value={SCM_LABEL[service.source] ?? service.source} />}
                      {service.tier && <MetaField label="Tier" value={service.tier} capitalize />}
                      {service.slaTarget != null && <MetaField label="SLA Target" value={`${service.slaTarget}%`} />}
                      {teamName && (
                        <div className="space-y-0.5">
                          <p className="text-xs uppercase tracking-wide text-muted-foreground">Owner Team</p>
                          <Link to="/teams" className="block font-medium hover:text-primary hover:underline">
                            {teamName}
                          </Link>
                        </div>
                      )}
                      {service.tags && service.tags.length > 0 && (
                        <div className="col-span-2 space-y-1.5">
                          <p className="text-xs uppercase tracking-wide text-muted-foreground">Tags</p>
                          <div className="flex flex-wrap gap-1.5">
                            {service.tags.map((tag) => (
                              <span key={tag} className="rounded-md border border-border bg-background px-2 py-0.5 text-xs font-medium text-foreground">
                                {tag}
                              </span>
                            ))}
                          </div>
                        </div>
                      )}
                    </MetaSection>

                    {/* Health */}
                    <MetaSection label="Health">
                      <MetaField label="Status" value={health} capitalize />
                      <MetaField label="Risk Score" value={String(riskScore)} />
                      {service.healthCheckedAt && (
                        <MetaField label="Last Checked" value={relativeTime(service.healthCheckedAt)} />
                      )}
                      {service.healthEndpoint && (
                        <div className="col-span-2 space-y-0.5">
                          <p className="text-xs uppercase tracking-wide text-muted-foreground">Health Endpoint</p>
                          <a
                            href={service.healthEndpoint}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="block truncate font-mono text-xs text-primary hover:underline"
                          >
                            {service.healthEndpoint}
                          </a>
                        </div>
                      )}
                    </MetaSection>

                    {/* Repository */}
                    {(service.repositoryUrl || service.repositoryRef || service.lastCommitAt || service.lastDeployedAt) && (
                      <MetaSection label="Repository">
                        {service.repositoryRef && <MetaField label="Branch / Ref" value={service.repositoryRef} mono />}
                        {service.lastDeployedAt && <MetaField label="Last Deploy" value={relativeTime(service.lastDeployedAt)} />}
                        {service.lastCommitAt && <MetaField label="Last Commit" value={relativeTime(service.lastCommitAt)} />}
                        {service.lastCommitSha && <MetaField label="Commit SHA" value={service.lastCommitSha.slice(0, 7)} mono />}
                        {service.repositoryUrl && (
                          <div className="col-span-2 space-y-0.5">
                            <p className="text-xs uppercase tracking-wide text-muted-foreground">Repository</p>
                            <a
                              href={service.repositoryUrl}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="block truncate font-mono text-xs text-primary hover:underline"
                            >
                              {service.repositoryUrl}
                            </a>
                          </div>
                        )}
                      </MetaSection>
                    )}

                    {/* Kubernetes */}
                    {(service.k8sCluster || service.k8sNamespace || service.k8sDeployment || service.externalId) && (
                      <MetaSection label="Kubernetes">
                        {service.k8sCluster && <MetaField label="Cluster" value={service.k8sCluster} />}
                        {service.k8sNamespace && <MetaField label="Namespace" value={service.k8sNamespace} mono />}
                        {service.k8sDeployment && <MetaField label="Deployment" value={service.k8sDeployment} mono />}
                        {service.externalId && <MetaField label="External ID" value={service.externalId} mono />}
                      </MetaSection>
                    )}

                    {/* Links */}
                    {(service.documentationUrl || service.runbookUrl) && (
                      <MetaSection label="Links">
                        {service.documentationUrl && (
                          <div className="space-y-0.5">
                            <p className="text-xs uppercase tracking-wide text-muted-foreground">Documentation</p>
                            <a href={service.documentationUrl} target="_blank" rel="noopener noreferrer" className="block truncate font-mono text-xs text-primary hover:underline">
                              {service.documentationUrl}
                            </a>
                          </div>
                        )}
                        {service.runbookUrl && (
                          <div className="space-y-0.5">
                            <p className="text-xs uppercase tracking-wide text-muted-foreground">Runbook</p>
                            <a href={service.runbookUrl} target="_blank" rel="noopener noreferrer" className="block truncate font-mono text-xs text-primary hover:underline">
                              {service.runbookUrl}
                            </a>
                          </div>
                        )}
                      </MetaSection>
                    )}

                    {/* Timestamps */}
                    <MetaSection label="Timestamps">
                      <MetaField label="Registered" value={relativeTime(service.createdAt)} />
                      <MetaField label="Updated" value={relativeTime(service.updatedAt)} />
                    </MetaSection>

                  </CardContent>
                </Card>
              </div>
            )}

            {/* Dependencies */}
            {tab === 'dependencies' && (
              <div id="panel-dependencies" role="tabpanel" aria-labelledby="tab-dependencies">
                <DependenciesList serviceId={serviceId} canManage={canEdit} />
              </div>
            )}

            {/* Contracts */}
            {tab === 'contracts' && (
              <div id="panel-contracts" role="tabpanel" aria-labelledby="tab-contracts">
                <EmptyTab
                  icon={<Shield className="size-8" />}
                  message="Per-service contract detail isn't built yet."
                  linkTo="/contracts"
                  linkLabel="See all contracts"
                />
              </div>
            )}

            {/* Activity */}
            {tab === 'activity' && (
              <div id="panel-activity" role="tabpanel" aria-labelledby="tab-activity">
                <EmptyTab
                  icon={<Activity className="size-8" />}
                  message="Per-service activity detail isn't built yet."
                  linkTo="/timeline"
                  linkLabel="See the full activity timeline"
                />
              </div>
            )}
          </div>

          {/* Right sidebar */}
          <div className="space-y-4">
            <Card className="border-dashed">
              <CardHeader className="pb-2 pt-5">
                <CardTitle className="text-sm font-semibold text-muted-foreground">Active Risks</CardTitle>
              </CardHeader>
              <CardContent className="pb-5 pt-0">
                <p className="text-sm text-muted-foreground">
                  Risk detection isn't available for individual services yet. See{' '}
                  <Link to="/risks" className="text-primary hover:underline">the Risks page</Link> for
                  org-wide risk signals.
                </p>
              </CardContent>
            </Card>

            <Card className="border-dashed">
              <CardHeader className="pb-2 pt-5">
                <CardTitle className="text-sm font-semibold text-muted-foreground">Contract Impact</CardTitle>
              </CardHeader>
              <CardContent className="pb-5 pt-0">
                <p className="text-sm text-muted-foreground">
                  Contract impact analysis isn't available for individual services yet. See{' '}
                  <Link to="/contracts" className="text-primary hover:underline">the Contracts page</Link>.
                </p>
              </CardContent>
            </Card>

            <Card className="border-dashed">
              <CardHeader className="pb-2 pt-5">
                <CardTitle className="text-sm font-semibold text-muted-foreground">Health History</CardTitle>
              </CardHeader>
              <CardContent className="pb-5 pt-0">
                <p className="text-sm text-muted-foreground">
                  Historical health trend isn't tracked yet — this card will show a 7-day trend once
                  health snapshots are recorded over time. Current status:{' '}
                  <span className={cn('font-medium capitalize', healthTextClass(health))}>{health}</span>.
                </p>
              </CardContent>
            </Card>
          </div>
        </div>
      </div>

      <EditServiceDrawer service={service} open={editOpen} onOpenChange={setEditOpen} />
    </AppLayout>
  )
}

function InsightCard({ insight }: { insight: Insight }) {
  const isCritical = insight.severity === 'critical'
  const isWarning = insight.severity === 'warning'
  return (
    <div
      className={cn(
        'rounded-lg border p-4',
        isCritical && 'border-critical bg-critical-subtle',
        isWarning && 'border-warning bg-warning-subtle',
        !isCritical && !isWarning && 'border-border bg-muted/40',
      )}
    >
      <div
        className={cn(
          'mb-1',
          isCritical && 'text-critical',
          isWarning && 'text-warning',
          !isCritical && !isWarning && 'text-muted-foreground',
        )}
      >
        {insight.icon}
      </div>
      <p
        className={cn(
          'text-sm font-semibold',
          isCritical && 'text-critical',
          isWarning && 'text-warning',
        )}
      >
        {insight.label}
      </p>
      <p className="mt-0.5 text-xs text-muted-foreground">{insight.description}</p>
    </div>
  )
}

function MetaSection({ label, children }: { label?: string; children: React.ReactNode }) {
  return (
    <div className="space-y-3">
      {label && <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground/60">{label}</p>}
      <div className="grid grid-cols-2 gap-x-8 gap-y-4 text-sm">
        {children}
      </div>
    </div>
  )
}

function MetaField({
  label,
  value,
  mono,
  capitalize,
}: {
  label: string
  value: string
  mono?: boolean
  capitalize?: boolean
}) {
  return (
    <div className="space-y-0.5">
      <p className="text-xs uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className={cn('font-medium', mono && 'font-mono text-xs', capitalize && 'capitalize')}>{value}</p>
    </div>
  )
}

function EmptyTab({
  icon,
  message,
  linkTo,
  linkLabel,
}: {
  icon: React.ReactNode
  message: string
  linkTo?: string
  linkLabel?: string
}) {
  return (
    <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-12 text-center">
      <div className="mb-3 text-muted-foreground/50">{icon}</div>
      <p className="text-sm text-muted-foreground">{message}</p>
      {linkTo && linkLabel && (
        <Button asChild variant="outline" size="sm" className="mt-4">
          <Link to={linkTo}>{linkLabel}</Link>
        </Button>
      )}
    </div>
  )
}
