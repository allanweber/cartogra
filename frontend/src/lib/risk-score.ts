import { normalizeHealth } from '#/lib/registry-types'

import type { RegistryService } from '#/lib/registry-types'

export type RiskBand = 'low' | 'moderate' | 'high'

export interface RiskFactor {
  label: string
  points: number
}

export interface RiskBreakdown {
  score: number
  band: RiskBand
  factors: RiskFactor[]
}

// Weights are an internal triage heuristic, not a measured probability — the breakdown
// below is what makes that legible instead of asking users to trust an opaque number.
export function computeRiskBreakdown(service: RegistryService): RiskBreakdown {
  const health = normalizeHealth(service.healthStatus)
  const factors: RiskFactor[] = []

  const healthPoints = health === 'down' ? 60 : health === 'degraded' ? 35 : 5
  factors.push({
    label: health === 'down' ? 'Service is down' : health === 'degraded' ? 'Health degraded' : 'Health check passing',
    points: healthPoints,
  })

  if (service.lastDeployedAt) {
    const days = (Date.now() - new Date(service.lastDeployedAt).getTime()) / 86400000
    const stalePoints = Math.min(30, Math.floor(days * 1.5))
    if (stalePoints > 0) {
      factors.push({ label: `${Math.floor(days)}d since last deploy`, points: stalePoints })
    }
  } else {
    factors.push({ label: 'Never deployed', points: 18 })
  }

  if (!service.teamId) {
    factors.push({ label: 'No owning team', points: 8 })
  }

  const raw = factors.reduce((sum, factor) => sum + factor.points, 0)
  const score = Math.min(99, Math.max(1, raw))
  const band: RiskBand = score >= 70 ? 'high' : score >= 35 ? 'moderate' : 'low'

  return { score, band, factors }
}

export function computeRiskScore(service: RegistryService): number {
  return computeRiskBreakdown(service).score
}

export function riskBandLabel(band: RiskBand): string {
  if (band === 'high') return 'High risk'
  if (band === 'moderate') return 'Elevated risk'
  return 'Low risk'
}
