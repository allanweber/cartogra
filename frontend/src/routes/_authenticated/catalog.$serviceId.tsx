import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { Activity, ArrowLeft, Clock, Code2, GitBranch, Shield, Users } from 'lucide-react'
import { useState } from 'react'

import { AppLayout } from '#/components/AppLayout'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'
import { Skeleton } from '#/components/ui/skeleton'
import { ApiError, apiFetch } from '#/lib/api'
import { SCM_LABEL, normalizeHealth } from '#/lib/registry-types'
import { cn } from '#/lib/utils'

import type { PageResult, RegistryService, RegistryTeam, ServiceHealth } from '#/lib/registry-types'

export const Route = createFileRoute('/_authenticated/catalog/$serviceId')({
  component: ServiceDetailPage,
})

function ServiceDetailPage() {
  const { serviceId } = Route.useParams()
  const [tab, setTab] = useState<'overview' | 'contracts' | 'activity'>('overview')

  const { data: service, isLoading, error } = useQuery({
    queryKey: ['service', serviceId],
    queryFn: () => apiFetch<RegistryService>(`/v1/services/${serviceId}`),
  })

  const { data: teamsPage } = useQuery({
    queryKey: ['teams'],
    queryFn: () => apiFetch<PageResult<RegistryTeam>>('/v1/teams?limit=200'),
  })

  const teamMap = new Map((teamsPage?.items ?? []).map((t) => [t.id, t.name]))

  if (isLoading) {
    return (
      <AppLayout title="Loading..." eyebrow="Service Catalog">
        <div className="space-y-5">
          <Skeleton className="h-4 w-32" />
          <Skeleton className="h-32 w-full rounded-xl" />
          <div className="flex gap-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-16 w-32 rounded-lg" />
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

  return (
    <AppLayout title={service.name} eyebrow="Service Catalog">
      <div className="space-y-5">
        {/* Breadcrumb */}
        <Link
          to="/catalog"
          className="inline-flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="size-3" />
          Service Catalog
        </Link>

        {/* Header card */}
        <Card>
          <CardContent className="flex flex-wrap items-start gap-4 p-5">
            <div className="flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <h2 className="text-xl font-bold">{service.name}</h2>
                <HealthBadge health={health} />
              </div>
              {service.description && (
                <p className="mt-1 text-sm text-muted-foreground">{service.description}</p>
              )}
            </div>

            <div className="flex flex-wrap gap-6 text-sm">
              <MetaItem
                icon={<Users className="size-3.5" />}
                label="Owner"
                value={teamName ?? 'Unowned'}
              />
              {service.lastDeployedAt && (
                <MetaItem
                  icon={<Clock className="size-3.5" />}
                  label="Last deploy"
                  value={new Date(service.lastDeployedAt).toLocaleDateString()}
                />
              )}
              {service.repositoryUrl && (
                <MetaItem
                  icon={<GitBranch className="size-3.5" />}
                  label="Repository"
                  value={service.repositoryRef ?? service.repositoryUrl}
                />
              )}
              {service.source && (
                <div className="flex items-center gap-1.5">
                  <span className="text-muted-foreground">
                    <Code2 className="size-3.5" aria-hidden="true" />
                  </span>
                  <div>
                    <p className="text-[10px] uppercase tracking-wide text-muted-foreground">SCM</p>
                    <p className="text-sm font-medium">{SCM_LABEL[service.source] ?? service.source}</p>
                  </div>
                </div>
              )}
            </div>
          </CardContent>
        </Card>

        {/* Insight chips */}
        <div className="flex flex-wrap gap-3">
          <InsightChip label="Health" value={service.healthStatus} />
          {tech.length > 0 && <InsightChip label="Tech Stack" value={tech.join(' · ')} />}
          {service.teamId === null && <InsightChip label="Warning" value="orphan" variant="warning" />}
          {service.k8sCluster && <InsightChip label="K8s Cluster" value={service.k8sCluster} />}
          {service.k8sNamespace && <InsightChip label="Namespace" value={service.k8sNamespace} />}
        </div>

        {/* Tab bar */}
        <div role="tablist" className="flex gap-0 overflow-hidden rounded-lg border border-border">
          {(['overview', 'contracts', 'activity'] as const).map((t) => (
            <button
              key={t}
              id={`tab-${t}`}
              role="tab"
              aria-selected={tab === t}
              aria-controls={`panel-${t}`}
              onClick={() => setTab(t)}
              className={cn(
                'flex-1 px-4 py-2.5 text-sm font-medium capitalize transition-colors focus-visible:outline-none focus-visible:ring-inset focus-visible:ring-2 focus-visible:ring-ring',
                tab === t
                  ? 'bg-primary text-primary-foreground'
                  : 'bg-background text-muted-foreground hover:bg-muted hover:text-foreground',
              )}
            >
              {t}
            </button>
          ))}
        </div>

        {/* Overview */}
        {tab === 'overview' && (
          <div id="panel-overview" role="tabpanel" aria-labelledby="tab-overview" className="grid gap-4 md:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle className="text-sm">Tech Stack</CardTitle>
              </CardHeader>
              <CardContent>
                {tech.length === 0 ? (
                  <p className="text-sm text-muted-foreground">No tech stack defined.</p>
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {tech.map((t) => (
                      <Badge key={t} variant="secondary">{t}</Badge>
                    ))}
                  </div>
                )}
              </CardContent>
            </Card>
            <Card>
              <CardHeader>
                <CardTitle className="text-sm">Infrastructure</CardTitle>
              </CardHeader>
              <CardContent className="space-y-2 text-sm">
                {service.k8sCluster && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Cluster</span>
                    <span className="font-medium">{service.k8sCluster}</span>
                  </div>
                )}
                {service.k8sNamespace && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Namespace</span>
                    <span className="font-medium">{service.k8sNamespace}</span>
                  </div>
                )}
                {service.k8sDeployment && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Deployment</span>
                    <span className="font-medium">{service.k8sDeployment}</span>
                  </div>
                )}
                {service.healthEndpoint && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Health endpoint</span>
                    <span className="font-mono text-xs">{service.healthEndpoint}</span>
                  </div>
                )}
                {!service.k8sCluster && !service.k8sNamespace && !service.k8sDeployment && !service.healthEndpoint && (
                  <p className="text-muted-foreground">No infrastructure details.</p>
                )}
              </CardContent>
            </Card>
          </div>
        )}

        {/* Contracts — not yet available */}
        {tab === 'contracts' && (
          <div id="panel-contracts" role="tabpanel" aria-labelledby="tab-contracts">
            <EmptyTab icon={<Shield className="size-8" />} message="Contract data not yet available." />
          </div>
        )}

        {/* Activity — not yet available */}
        {tab === 'activity' && (
          <div id="panel-activity" role="tabpanel" aria-labelledby="tab-activity">
            <EmptyTab icon={<Activity className="size-8" />} message="Activity feed not yet available." />
          </div>
        )}
      </div>
    </AppLayout>
  )
}

function HealthBadge({ health }: { health: ServiceHealth }) {
  return (
    <Badge
      variant="outline"
      className={cn('gap-1.5 capitalize border-current', `bg-health-${health}`)}
    >
      {health}
    </Badge>
  )
}

function MetaItem({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="flex items-center gap-1.5">
      <span className="text-muted-foreground">{icon}</span>
      <div>
        <p className="text-[10px] text-muted-foreground uppercase tracking-wide">{label}</p>
        <p className="font-medium">{value}</p>
      </div>
    </div>
  )
}

function InsightChip({
  label,
  value,
  variant,
}: {
  label: string
  value: string
  variant?: 'warning' | 'critical'
}) {
  return (
    <div
      className={cn(
        'rounded-lg border px-3 py-2',
        variant === 'critical' && 'border-[oklch(0.70_0.15_28)] bg-[oklch(0.97_0.05_28)] dark:border-[oklch(0.55_0.15_28)] dark:bg-[oklch(0.27_0.05_28)]',
        variant === 'warning' && 'border-[oklch(0.72_0.14_60)] bg-[oklch(0.97_0.06_80)] dark:border-[oklch(0.60_0.15_60)] dark:bg-[oklch(0.27_0.06_80)]',
        !variant && 'border-border bg-muted/50',
      )}
    >
      <p className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
      <p
        className={cn(
          'text-sm font-semibold',
          variant === 'critical' && 'text-[oklch(0.50_0.20_28)] dark:text-[oklch(0.72_0.18_28)]',
          variant === 'warning' && 'text-[oklch(0.50_0.15_60)] dark:text-[oklch(0.78_0.14_60)]',
        )}
      >
        {value}
      </p>
    </div>
  )
}

function EmptyTab({ icon, message }: { icon: React.ReactNode; message: string }) {
  return (
    <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-12 text-center">
      <div className="mb-3 text-muted-foreground/50">{icon}</div>
      <p className="text-sm text-muted-foreground">{message}</p>
    </div>
  )
}
