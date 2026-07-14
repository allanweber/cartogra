import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { Plus, Settings2, Users, X } from 'lucide-react'
import { useState } from 'react'

import { AppLayout } from '#/components/AppLayout'
import { TeamDialog } from '#/components/TeamDialog'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Card, CardContent } from '#/components/ui/card'
import { Skeleton } from '#/components/ui/skeleton'
import { ApiError, apiFetch } from '#/lib/api'
import { normalizeHealth } from '#/lib/registry-types'
import { useAuthStore } from '#/stores/useAuthStore'
import { cn } from '#/lib/utils'

import type { PageResult, RegistryService, RegistryTeam, ServiceHealth } from '#/lib/registry-types'

export const Route = createFileRoute('/_authenticated/teams')({
  component: TeamsPage,
})

type RiskExposure = 'low' | 'medium' | 'high' | 'critical'

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

function deriveTeamHealth(services: RegistryService[]): ServiceHealth {
  if (services.length === 0) return 'healthy'
  const healths = services.map((s) => normalizeHealth(s.healthStatus))
  if (healths.includes('down')) return 'down'
  if (healths.includes('degraded')) return 'degraded'
  return 'healthy'
}

function deriveRiskExposure(services: RegistryService[]): RiskExposure {
  if (services.length === 0) return 'low'
  const max = Math.max(...services.map(computeRiskScore))
  if (max >= 70) return 'critical'
  if (max >= 35) return 'high'
  if (max >= 15) return 'medium'
  return 'low'
}

function relativeTime(dateStr: string | null): string | null {
  if (!dateStr) return null
  const secs = Math.floor((Date.now() - new Date(dateStr).getTime()) / 1000)
  if (secs < 3600) return `${Math.max(1, Math.floor(secs / 60))}m ago`
  if (secs < 86400) return `${Math.floor(secs / 3600)}h ago`
  return `${Math.floor(secs / 86400)}d ago`
}

function teamColor(name: string): string {
  const hues = [145, 60, 240, 28, 320]
  const hue = hues[name.charCodeAt(0) % hues.length]
  return `oklch(0.50 0.18 ${hue})`
}

function riskColorClass(score: number): string {
  if (score >= 70) return 'text-critical'
  if (score >= 35) return 'text-warning'
  return 'text-success'
}

function TeamsPage() {
  const [selectedTeamId, setSelectedTeamId] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [manageTeamId, setManageTeamId] = useState<string | null>(null)
  const isAdmin = useAuthStore((s) => s.user?.roles.includes('ADMIN') ?? false)
  const isTeamOwner = useAuthStore((s) => s.user?.roles.includes('TEAM_OWNER') ?? false)
  const canCreateTeam = isAdmin || isTeamOwner

  const { data: teamsPage, isLoading, error } = useQuery({
    queryKey: ['teams'],
    queryFn: () => apiFetch<PageResult<RegistryTeam>>('/v1/registry/teams?limit=200'),
  })

  const { data: servicesPage } = useQuery({
    queryKey: ['services', 'teams-page'],
    queryFn: () => apiFetch<PageResult<RegistryService>>('/v1/registry/services?limit=1000'),
  })

  const { data: myTeamIds } = useQuery({
    queryKey: ['teams', 'mine'],
    queryFn: () => apiFetch<string[]>('/v1/registry/teams/mine'),
    enabled: !isAdmin,
  })
  const myTeamIdSet = new Set(myTeamIds ?? [])
  const canManage = (teamId: string) => isAdmin || myTeamIdSet.has(teamId)

  const teams = teamsPage?.items ?? []
  const totalTeams = teamsPage?.total ?? 0
  const services = servicesPage?.items ?? []
  const totalServices = servicesPage?.total ?? 0

  const teamServiceMap = new Map<string, RegistryService[]>()
  for (const svc of services) {
    if (svc.teamId) {
      const arr = teamServiceMap.get(svc.teamId) ?? []
      arr.push(svc)
      teamServiceMap.set(svc.teamId, arr)
    }
  }

  const unownedCount = services.filter((s) => !s.teamId).length
  const criticalRiskCount = teams.filter((t) => {
    return deriveRiskExposure(teamServiceMap.get(t.id) ?? []) === 'critical'
  }).length

  const selectedTeam = selectedTeamId ? (teams.find((t) => t.id === selectedTeamId) ?? null) : null
  const manageTeam = manageTeamId ? (teams.find((t) => t.id === manageTeamId) ?? null) : null

  return (
    <AppLayout
      title="Teams"
      description="Team ownership, health summary, and risk exposure"
      actions={
        canCreateTeam && (
          <Button onClick={() => setCreateOpen(true)} size="sm" className="gap-1.5">
            <Plus className="size-3.5" aria-hidden="true" />
            Add team
          </Button>
        )
      }
    >
      <div className="space-y-4">
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <StatCard label="Teams" value={totalTeams} />
          <StatCard label="Total Services" value={totalServices} />
          <StatCard label="Unowned Services" value={unownedCount} variant={unownedCount > 0 ? 'warning' : undefined} />
          <StatCard label="Critical Risk Teams" value={criticalRiskCount} variant={criticalRiskCount > 0 ? 'critical' : undefined} />
        </div>

        {isLoading ? (
          <div className="space-y-2">
            {Array.from({ length: 4 }).map((_, i) => (
              <Skeleton key={i} className="h-20 w-full rounded-xl" />
            ))}
          </div>
        ) : error ? (
          <Alert variant="destructive">
            <AlertDescription>
              {error.message}
              {error instanceof ApiError && ` (trace: ${error.traceId})`}
            </AlertDescription>
          </Alert>
        ) : teams.length === 0 ? (
          <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-16 text-center">
            <Users className="mb-3 size-8 text-muted-foreground/50" aria-hidden="true" />
            <p className="text-sm font-medium">No teams yet</p>
            <p className="mt-1 text-xs text-muted-foreground">Create a team to start assigning service ownership</p>
            {canCreateTeam && (
              <Button onClick={() => setCreateOpen(true)} size="sm" className="mt-4 gap-1.5">
                <Plus className="size-3.5" aria-hidden="true" />
                Add team
              </Button>
            )}
          </div>
        ) : (
          <div className={cn('grid gap-4', selectedTeam ? 'lg:grid-cols-[1fr_360px]' : 'grid-cols-1')}>
            <div className="space-y-2">
              {teams.map((team) => {
                const svcs = teamServiceMap.get(team.id) ?? []
                return (
                  <TeamRow
                    key={team.id}
                    team={team}
                    services={svcs}
                    health={deriveTeamHealth(svcs)}
                    riskExposure={deriveRiskExposure(svcs)}
                    selected={selectedTeamId === team.id}
                    canManage={canManage(team.id)}
                    onClick={() =>
                      setSelectedTeamId((prev) => (prev === team.id ? null : team.id))
                    }
                    onManage={(e) => {
                      e.stopPropagation()
                      setManageTeamId(team.id)
                    }}
                  />
                )
              })}
            </div>

            {selectedTeam && (
              <TeamDetailPanel
                team={selectedTeam}
                services={teamServiceMap.get(selectedTeam.id) ?? []}
                health={deriveTeamHealth(teamServiceMap.get(selectedTeam.id) ?? [])}
                riskExposure={deriveRiskExposure(teamServiceMap.get(selectedTeam.id) ?? [])}
                canManage={canManage(selectedTeam.id)}
                onClose={() => setSelectedTeamId(null)}
                onManage={() => setManageTeamId(selectedTeam.id)}
              />
            )}
          </div>
        )}
      </div>

      {canCreateTeam && <TeamDialog open={createOpen} onOpenChange={setCreateOpen} />}

      <TeamDialog
        team={manageTeam ?? undefined}
        open={!!manageTeamId}
        onOpenChange={(open) => {
          if (!open) setManageTeamId(null)
        }}
        onDeleted={() => {
          if (selectedTeamId === manageTeamId) setSelectedTeamId(null)
          setManageTeamId(null)
        }}
      />
    </AppLayout>
  )
}

function StatCard({
  label,
  value,
  variant,
}: {
  label: string
  value: number
  variant?: 'warning' | 'critical'
}) {
  return (
    <Card>
      <CardContent className="p-4">
        <span
          className={cn(
            'text-2xl font-bold',
            variant === 'critical' && 'text-critical',
            variant === 'warning' && 'text-warning',
          )}
        >
          {value}
        </span>
        <p className="mt-0.5 text-xs text-muted-foreground">{label}</p>
      </CardContent>
    </Card>
  )
}

function TeamRow({
  team,
  services,
  health,
  riskExposure,
  selected,
  canManage,
  onClick,
  onManage,
}: {
  team: RegistryTeam
  services: RegistryService[]
  health: ServiceHealth
  riskExposure: RiskExposure
  selected: boolean
  canManage: boolean
  onClick: () => void
  onManage: (e: React.MouseEvent) => void
}) {
  return (
    <div
      role="button"
      tabIndex={0}
      aria-pressed={selected}
      aria-label={`${team.name} team`}
      onClick={onClick}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onClick()
        }
      }}
      className={cn(
        'w-full cursor-pointer rounded-xl border p-4 text-left transition-all',
        selected
          ? 'border-primary bg-primary/5'
          : 'border-border bg-card hover:border-primary/40 hover:bg-muted/50',
      )}
    >
      <div className="flex items-center gap-3">
        <div
          className="flex size-10 shrink-0 items-center justify-center rounded-full text-sm font-bold text-white"
          style={{ background: teamColor(team.name) }}
          aria-hidden="true"
        >
          {team.name.slice(0, 2).toUpperCase()}
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-semibold">{team.name}</span>
            <span className="text-xs text-muted-foreground">{services.length} services</span>
          </div>
          {services.length > 0 && (
            <div className="mt-1.5 flex flex-wrap gap-1">
              {services.map((s) => (
                <Link
                  key={s.id}
                  to="/catalog/$serviceId"
                  params={{ serviceId: s.id }}
                  onClick={(e) => e.stopPropagation()}
                  className="flex items-center gap-1 rounded-md bg-muted px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-muted/70 hover:text-foreground"
                >
                  <HealthDot health={normalizeHealth(s.healthStatus)} />
                  {s.name}
                </Link>
              ))}
            </div>
          )}
        </div>

        <div className="flex shrink-0 flex-col items-end gap-1.5">
          <HealthBadge health={health} />
          <RiskExposureBadge exposure={riskExposure} />
        </div>

        {canManage && (
          <button
            onClick={onManage}
            aria-label={`Manage ${team.name}`}
            data-testid={`manage-btn-${team.id}`}
            className="ml-1 flex shrink-0 items-center justify-center rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring pointer-coarse:size-11"
          >
            <Settings2 className="size-4" aria-hidden="true" />
          </button>
        )}
      </div>
    </div>
  )
}

function TeamDetailPanel({
  team,
  services,
  health,
  riskExposure,
  canManage,
  onClose,
  onManage,
}: {
  team: RegistryTeam
  services: RegistryService[]
  health: ServiceHealth
  riskExposure: RiskExposure
  canManage: boolean
  onClose: () => void
  onManage: () => void
}) {
  return (
    <Card className="sticky top-4 h-fit">
      <CardContent className="p-5">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-3">
            <div
              className="flex size-12 items-center justify-center rounded-full text-base font-bold text-white"
              style={{ background: teamColor(team.name) }}
              aria-hidden="true"
            >
              {team.name.slice(0, 2).toUpperCase()}
            </div>
            <div>
              <p className="text-lg font-bold">{team.name} Team</p>
              <div className="mt-1 flex gap-2">
                <HealthBadge health={health} />
                <RiskExposureBadge exposure={riskExposure} />
              </div>
            </div>
          </div>
          <button
            onClick={onClose}
            className="flex items-center justify-center rounded-md p-1 text-muted-foreground hover:bg-muted pointer-coarse:size-11"
            aria-label="Close panel"
          >
            <X className="size-4" />
          </button>
        </div>

        <div className="mt-4 grid grid-cols-2 gap-3">
          <div className="rounded-lg bg-muted/50 p-3 text-center">
            <p className="text-2xl font-bold">{services.length}</p>
            <p className="text-xs text-muted-foreground">Services</p>
          </div>
          <div className="rounded-lg bg-muted/50 p-3 text-center">
            <p className="text-2xl font-bold capitalize">{riskExposure}</p>
            <p className="text-xs text-muted-foreground">Risk</p>
          </div>
        </div>

        {services.length > 0 && (
          <div className="mt-4">
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              Owned Services
            </p>
            <div className="mt-2 space-y-1.5">
              {services.map((s) => {
                const h = normalizeHealth(s.healthStatus)
                const risk = computeRiskScore(s)
                const deploy = relativeTime(s.lastDeployedAt)
                return (
                  <Link
                    key={s.id}
                    to="/catalog/$serviceId"
                    params={{ serviceId: s.id }}
                    className="flex items-center justify-between rounded-lg bg-muted/50 px-3 py-2 hover:bg-muted"
                  >
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium">{s.name}</p>
                      {deploy && (
                        <p className="text-xs text-muted-foreground">Deploy: {deploy}</p>
                      )}
                    </div>
                    <div className="flex shrink-0 items-center gap-2 pl-2">
                      <HealthDot health={h} />
                      <span className={cn('text-sm font-semibold tabular-nums', riskColorClass(risk))}>
                        {risk}
                      </span>
                    </div>
                  </Link>
                )
              })}
            </div>
          </div>
        )}

        <div className="mt-4 flex gap-2">
          {canManage && (
            <Button onClick={onManage} size="sm" className="flex-1">
              Manage team
            </Button>
          )}
          <Button asChild variant="outline" size="sm" className={canManage ? undefined : 'flex-1'}>
            <Link to="/graph">View graph</Link>
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

function HealthDot({ health }: { health: ServiceHealth }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'inline-block size-1.5 shrink-0 rounded-full',
        health === 'healthy' && 'bg-success',
        health === 'degraded' && 'bg-warning',
        health === 'down' && 'bg-critical',
      )}
    />
  )
}

function HealthBadge({ health }: { health: ServiceHealth }) {
  return (
    <Badge
      variant="outline"
      className={cn(
        'capitalize border-current gap-1',
        health === 'healthy' && 'border-success bg-success-subtle text-success',
        health === 'degraded' && 'border-warning bg-warning-subtle text-warning',
        health === 'down' && 'border-critical bg-critical-subtle text-critical',
      )}
    >
      <HealthDot health={health} />
      {health.charAt(0).toUpperCase() + health.slice(1)}
    </Badge>
  )
}

function RiskExposureBadge({ exposure }: { exposure: RiskExposure }) {
  return (
    <Badge
      variant="outline"
      className={cn(
        'capitalize',
        exposure === 'low' && 'text-muted-foreground',
        exposure === 'medium' && 'border-warning bg-warning-subtle text-warning',
        exposure === 'high' && 'border-[oklch(0.68_0.14_40)] bg-[oklch(0.97_0.05_40)] text-[oklch(0.52_0.18_40)] dark:border-[oklch(0.55_0.14_40)] dark:text-[oklch(0.76_0.16_40)]',
        exposure === 'critical' && 'border-critical bg-critical-subtle text-critical',
      )}
    >
      {exposure} risk
    </Badge>
  )
}
