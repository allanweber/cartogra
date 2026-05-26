import { createFileRoute, Link, notFound } from '@tanstack/react-router'
import { Activity, ArrowLeft, Clock, Code2, GitBranch, Shield, Users } from 'lucide-react'
import { useState } from 'react'

import { AppLayout } from '#/components/AppLayout'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'
import { MOCK_CONTRACTS, MOCK_SERVICES, MOCK_TIMELINE } from '#/lib/mock-data'
import { cn } from '#/lib/utils'

import type { ScmProvider, ServiceHealth } from '#/lib/mock-data'

export const Route = createFileRoute('/_authenticated/catalog/$serviceId')({
  component: ServiceDetailPage,
  loader: ({ params }) => {
    const service = MOCK_SERVICES.find((s) => s.id === params.serviceId)
    if (!service) throw notFound()
    return { service }
  },
})

function ServiceDetailPage() {
  const { service } = Route.useLoaderData()
  const [tab, setTab] = useState<'overview' | 'contracts' | 'activity'>('overview')

  const contracts = MOCK_CONTRACTS.filter((c) => c.service === service.name)
  const activity = MOCK_TIMELINE.filter((e) => e.service === service.name)

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
                <HealthBadge health={service.health} />
                <Badge variant="secondary" className="capitalize">
                  {service.tier}
                </Badge>
              </div>
              <p className="mt-1 text-sm text-muted-foreground">{service.description}</p>
            </div>

            <div className="flex flex-wrap gap-6 text-sm">
              <MetaItem icon={<Users className="size-3.5" />} label="Owner" value={service.owner ?? 'Unowned'} />
              <MetaItem icon={<Clock className="size-3.5" />} label="Last deploy" value={service.lastDeploy} />
              <MetaItem icon={<GitBranch className="size-3.5" />} label="Dependencies" value={String(service.deps)} />
              <div className="flex items-center gap-1.5">
                <span className="text-muted-foreground">
                  <Code2 className="size-3.5" aria-hidden="true" />
                </span>
                <div>
                  <p className="text-[10px] uppercase tracking-wide text-muted-foreground">SCM</p>
                  <ScmBadge provider={service.scmProvider} />
                </div>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Insight chips */}
        <div className="flex flex-wrap gap-3">
          <InsightChip
            label="Risk Score"
            value={String(service.riskScore)}
            variant={service.riskScore >= 70 ? 'critical' : service.riskScore >= 40 ? 'warning' : 'ok'}
          />
          <InsightChip label="Tech Stack" value={service.tech.join(' · ')} />
          {service.warnings.map((w) => (
            <InsightChip key={w} label="Warning" value={w} variant="warning" />
          ))}
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

        {/* Tab panels */}
        {tab === 'overview' && (
          <div id="panel-overview" role="tabpanel" aria-labelledby="tab-overview" className="grid gap-4 md:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle className="text-sm">Tech Stack</CardTitle>
              </CardHeader>
              <CardContent className="flex flex-wrap gap-2">
                {service.tech.map((t) => (
                  <Badge key={t} variant="secondary">
                    {t}
                  </Badge>
                ))}
              </CardContent>
            </Card>
            <Card>
              <CardHeader>
                <CardTitle className="text-sm">Warnings</CardTitle>
              </CardHeader>
              <CardContent>
                {service.warnings.length === 0 ? (
                  <p className="text-sm text-muted-foreground">No warnings.</p>
                ) : (
                  <div className="space-y-2">
                    {service.warnings.map((w) => (
                      <div
                        key={w}
                        className="flex items-center gap-2 rounded-lg border-l-2 border-l-amber-500 bg-amber-50 px-3 py-2 text-sm dark:bg-amber-950/30"
                      >
                        {w}
                      </div>
                    ))}
                  </div>
                )}
              </CardContent>
            </Card>
          </div>
        )}

        {tab === 'contracts' && (
          <div id="panel-contracts" role="tabpanel" aria-labelledby="tab-contracts" className="space-y-2">
            {contracts.length === 0 ? (
              <EmptyTab icon={<Shield className="size-8" />} message="No contracts for this service." />
            ) : (
              contracts.map((c) => (
                <Card key={c.id}>
                  <CardContent className="flex items-center gap-4 p-4">
                    <div className="flex-1">
                      <p className="font-medium">{c.name}</p>
                      <p className="text-xs text-muted-foreground">{c.version}</p>
                    </div>
                    <ContractStatusBadge status={c.status} />
                    <p className="text-xs text-muted-foreground">{c.consumers} consumers</p>
                    <p className="text-xs text-muted-foreground">{c.lastChanged}</p>
                  </CardContent>
                </Card>
              ))
            )}
          </div>
        )}

        {tab === 'activity' && (
          <div id="panel-activity" role="tabpanel" aria-labelledby="tab-activity" className="space-y-2">
            {activity.length === 0 ? (
              <EmptyTab icon={<Activity className="size-8" />} message="No recent activity." />
            ) : (
              activity.map((event) => (
                <div
                  key={event.id}
                  className="flex items-start gap-3 rounded-lg border border-border bg-card p-3"
                >
                  <div className={cn('mt-1.5 size-2 rounded-full', typeColor(event.type))} aria-hidden="true" />
                  <div className="min-w-0 flex-1">
                    <p className="text-sm">{event.msg}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      {event.actor} · {event.time}
                    </p>
                  </div>
                  <Badge variant="outline" className="shrink-0 text-[10px] capitalize">
                    {event.type}
                  </Badge>
                </div>
              ))
            )}
          </div>
        )}
      </div>
    </AppLayout>
  )
}

function typeColor(type: string) {
  const map: Record<string, string> = {
    deploy: 'bg-[oklch(0.55_0.18_145)]',
    contract: 'bg-[oklch(0.55_0.18_260)]',
    risk: 'bg-[oklch(0.58_0.23_28)]',
    ownership: 'bg-[oklch(0.65_0.18_60)]',
    dependency: 'bg-[oklch(0.60_0.18_240)]',
  }
  return map[type] ?? 'bg-muted-foreground'
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

function ContractStatusBadge({ status }: { status: string }) {
  return (
    <Badge
      variant="outline"
      className={cn(
        'capitalize',
        status === 'compatible' && 'border-emerald-200 text-emerald-700 dark:text-emerald-400',
        status === 'breaking' && 'border-red-200 text-red-700 dark:text-red-400',
        status === 'warning' && 'border-amber-200 text-amber-700 dark:text-amber-400',
        status === 'stale' && 'text-muted-foreground',
      )}
    >
      {status}
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
  variant?: 'ok' | 'warning' | 'critical'
}) {
  return (
    <div
      className={cn(
        'rounded-lg border px-3 py-2',
        variant === 'critical' && 'border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-950/30',
        variant === 'warning' && 'border-amber-200 bg-amber-50 dark:border-amber-800 dark:bg-amber-950/30',
        !variant && 'border-border bg-muted/50',
      )}
    >
      <p className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">{label}</p>
      <p
        className={cn(
          'text-sm font-semibold',
          variant === 'critical' && 'text-red-700 dark:text-red-400',
          variant === 'warning' && 'text-amber-700 dark:text-amber-400',
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

const SCM_LABELS: Record<ScmProvider, string> = {
  github: 'GitHub',
  gitlab: 'GitLab',
  'azure-devops': 'Azure DevOps',
  bitbucket: 'Bitbucket',
}

function ScmBadge({ provider }: { provider: ScmProvider }) {
  return (
    <span className="text-sm font-medium">{SCM_LABELS[provider]}</span>
  )
}
