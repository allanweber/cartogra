import { createFileRoute } from '@tanstack/react-router'
import { X } from 'lucide-react'
import { useState } from 'react'

import { AppLayout } from '#/components/AppLayout'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent } from '#/components/ui/card'
import { MOCK_TEAMS } from '#/lib/mock-data'
import { cn } from '#/lib/utils'

import type { Team } from '#/lib/mock-data'

export const Route = createFileRoute('/_authenticated/teams')({
  component: TeamsPage,
})

function TeamsPage() {
  const [selectedTeam, setSelectedTeam] = useState<Team | null>(null)

  const totalMembers = MOCK_TEAMS.reduce((sum, t) => sum + t.members, 0)
  const unownedServices = 2 // from mock data: Search Service, Report Service
  const criticalRiskTeams = MOCK_TEAMS.filter((t) => t.riskExposure === 'critical').length

  return (
    <AppLayout title="Teams" description="Team ownership, health, and risk exposure">
      <div className="space-y-4">
        {/* Summary stats */}
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <StatCard label="Teams" value={MOCK_TEAMS.length} />
          <StatCard label="Total Members" value={totalMembers} />
          <StatCard label="Unowned Services" value={unownedServices} variant="warning" />
          <StatCard label="Critical Risk Teams" value={criticalRiskTeams} variant={criticalRiskTeams > 0 ? 'critical' : undefined} />
        </div>

        {/* Team list + detail panel */}
        <div
          className={cn(
            'grid gap-4',
            selectedTeam ? 'lg:grid-cols-[1fr_360px]' : 'grid-cols-1',
          )}
        >
          <div className="space-y-2">
            {MOCK_TEAMS.map((team) => (
              <TeamRow
                key={team.id}
                team={team}
                selected={selectedTeam?.id === team.id}
                onClick={() =>
                  setSelectedTeam((prev) => (prev?.id === team.id ? null : team))
                }
              />
            ))}
          </div>

          {selectedTeam && (
            <TeamDetailPanel team={selectedTeam} onClose={() => setSelectedTeam(null)} />
          )}
        </div>
      </div>
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
        <p className="text-2xl font-bold
          {variant === 'critical' && ' text-red-600 dark:text-red-400'}
          {variant === 'warning' && ' text-amber-600 dark:text-amber-400'}
        ">
          <span
            className={cn(
              'text-2xl font-bold',
              variant === 'critical' && 'text-red-600 dark:text-red-400',
              variant === 'warning' && 'text-amber-600 dark:text-amber-400',
            )}
          >
            {value}
          </span>
        </p>
        <p className="mt-0.5 text-xs text-muted-foreground">{label}</p>
      </CardContent>
    </Card>
  )
}

function TeamRow({
  team,
  selected,
  onClick,
}: {
  team: Team
  selected: boolean
  onClick: () => void
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'w-full rounded-xl border p-4 text-left transition-all',
        selected
          ? 'border-primary bg-primary/5'
          : 'border-border bg-card hover:border-primary/40 hover:bg-muted/50',
      )}
    >
      <div className="flex items-center gap-3">
        {/* Team avatar */}
        <div
          className="flex size-10 shrink-0 items-center justify-center rounded-full text-sm font-bold text-white"
          style={{ background: teamGradient(team.name) }}
        >
          {team.name.slice(0, 2).toUpperCase()}
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-semibold">{team.name}</span>
            <span className="text-xs text-muted-foreground">{team.members} members</span>
            <span className="text-xs text-muted-foreground">· {team.lead}</span>
          </div>
          <div className="mt-1.5 flex flex-wrap gap-1">
            {team.services.map((s) => (
              <span
                key={s}
                className="rounded-md bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground"
              >
                {s}
              </span>
            ))}
          </div>
        </div>

        <div className="flex shrink-0 flex-col items-end gap-1.5">
          <HealthBadge health={team.health} />
          <RiskExposureBadge exposure={team.riskExposure} />
        </div>
      </div>
    </button>
  )
}

function TeamDetailPanel({ team, onClose }: { team: Team; onClose: () => void }) {
  return (
    <Card className="sticky top-4 h-fit">
      <CardContent className="p-5">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-3">
            <div
              className="flex size-12 items-center justify-center rounded-full text-base font-bold text-white"
              style={{ background: teamGradient(team.name) }}
            >
              {team.name.slice(0, 2).toUpperCase()}
            </div>
            <div>
              <p className="font-bold text-lg">{team.name}</p>
              <p className="text-xs text-muted-foreground">Lead: {team.lead}</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="rounded-md p-1 text-muted-foreground hover:bg-muted"
            aria-label="Close panel"
          >
            <X className="size-4" />
          </button>
        </div>

        <div className="mt-4 grid grid-cols-2 gap-3">
          <div className="rounded-lg bg-muted/50 p-3 text-center">
            <p className="text-2xl font-bold">{team.members}</p>
            <p className="text-xs text-muted-foreground">Members</p>
          </div>
          <div className="rounded-lg bg-muted/50 p-3 text-center">
            <p className="text-2xl font-bold">{team.services.length}</p>
            <p className="text-xs text-muted-foreground">Services</p>
          </div>
        </div>

        <div className="mt-4 flex gap-2">
          <HealthBadge health={team.health} />
          <RiskExposureBadge exposure={team.riskExposure} />
        </div>

        <div className="mt-4">
          <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            Owned Services
          </p>
          <div className="mt-2 space-y-1.5">
            {team.services.map((s) => (
              <div
                key={s}
                className="rounded-lg bg-muted/50 px-3 py-2 text-sm font-medium"
              >
                {s}
              </div>
            ))}
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

function HealthBadge({ health }: { health: string }) {
  return (
    <Badge
      variant="outline"
      className={cn(
        'capitalize',
        health === 'healthy' && 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-800 dark:bg-emerald-950/50 dark:text-emerald-400',
        health === 'degraded' && 'border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-800 dark:bg-amber-950/50 dark:text-amber-400',
        health === 'down' && 'border-red-200 bg-red-50 text-red-700 dark:border-red-800 dark:bg-red-950/50 dark:text-red-400',
      )}
    >
      {health}
    </Badge>
  )
}

function RiskExposureBadge({ exposure }: { exposure: string }) {
  return (
    <Badge
      variant="outline"
      className={cn(
        'capitalize',
        exposure === 'low' && 'text-muted-foreground',
        exposure === 'medium' && 'border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-800 dark:text-amber-400',
        exposure === 'high' && 'border-orange-200 bg-orange-50 text-orange-700 dark:border-orange-800 dark:text-orange-400',
        exposure === 'critical' && 'border-red-200 bg-red-50 text-red-700 dark:border-red-800 dark:bg-red-950/50 dark:text-red-400',
      )}
    >
      {exposure} risk
    </Badge>
  )
}

function teamGradient(name: string) {
  const gradients = [
    'linear-gradient(135deg, #7c3aed, #4f46e5)',
    'linear-gradient(135deg, #0ea5e9, #06b6d4)',
    'linear-gradient(135deg, #10b981, #059669)',
    'linear-gradient(135deg, #f59e0b, #d97706)',
    'linear-gradient(135deg, #ef4444, #dc2626)',
  ]
  const idx = name.charCodeAt(0) % gradients.length
  return gradients[idx]
}
