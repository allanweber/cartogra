import { describe, expect, it } from 'vitest'

import { computeRiskBreakdown, computeRiskScore, riskBandLabel } from '#/lib/risk-score'

import type { RegistryService } from '#/lib/registry-types'

function service(overrides: Partial<RegistryService> = {}): RegistryService {
  return {
    id: 'svc-1',
    tenantId: 't1',
    name: 'payments-api',
    description: null,
    teamId: 'team-1',
    repositoryUrl: null,
    techStack: null,
    metadata: null,
    healthStatus: 'HEALTHY',
    lastDeployedAt: null,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    externalId: null,
    connectionId: null,
    source: null,
    repositoryRef: null,
    k8sCluster: null,
    k8sNamespace: null,
    k8sDeployment: null,
    healthEndpoint: null,
    lastCommitAt: null,
    lastCommitSha: null,
    healthCheckedAt: null,
    tier: null,
    tags: null,
    slaTarget: null,
    documentationUrl: null,
    runbookUrl: null,
    ...overrides,
  }
}

function daysAgo(days: number): string {
  return new Date(Date.now() - days * 86_400_000).toISOString()
}

describe('computeRiskBreakdown — health', () => {
  it('scores a down service at 60 points with a down-specific label', () => {
    const breakdown = computeRiskBreakdown(service({ healthStatus: 'UNHEALTHY', lastDeployedAt: daysAgo(0.1) }))
    expect(breakdown.factors).toContainEqual({ label: 'Service is down', points: 60 })
  })

  it('scores a degraded service at 35 points with a degraded-specific label', () => {
    const breakdown = computeRiskBreakdown(service({ healthStatus: 'DEGRADED', lastDeployedAt: daysAgo(0.1) }))
    expect(breakdown.factors).toContainEqual({ label: 'Health degraded', points: 35 })
  })

  it('scores a healthy service at 5 points with a passing-specific label', () => {
    const breakdown = computeRiskBreakdown(service({ healthStatus: 'HEALTHY', lastDeployedAt: daysAgo(0.1) }))
    expect(breakdown.factors).toContainEqual({ label: 'Health check passing', points: 5 })
  })
})

describe('computeRiskBreakdown — deploy recency', () => {
  it('adds no factor for a deploy that is still fresh', () => {
    const breakdown = computeRiskBreakdown(service({ lastDeployedAt: daysAgo(0.1) }))
    expect(breakdown.factors.some((f) => f.label.includes('since last deploy'))).toBe(false)
  })

  it('adds a staleness factor scaled by days since the last deploy', () => {
    const breakdown = computeRiskBreakdown(service({ lastDeployedAt: daysAgo(10.5) }))
    expect(breakdown.factors).toContainEqual({ label: '10d since last deploy', points: 15 })
  })

  it('adds a flat 18-point factor when the service has never been deployed', () => {
    const breakdown = computeRiskBreakdown(service({ lastDeployedAt: null }))
    expect(breakdown.factors).toContainEqual({ label: 'Never deployed', points: 18 })
  })
})

describe('computeRiskBreakdown — ownership', () => {
  it('adds an 8-point factor when the service has no owning team', () => {
    const breakdown = computeRiskBreakdown(service({ teamId: null, lastDeployedAt: daysAgo(0.1) }))
    expect(breakdown.factors).toContainEqual({ label: 'No owning team', points: 8 })
  })

  it('adds no ownership factor when the service has a team', () => {
    const breakdown = computeRiskBreakdown(service({ teamId: 'team-1', lastDeployedAt: daysAgo(0.1) }))
    expect(breakdown.factors.some((f) => f.label === 'No owning team')).toBe(false)
  })
})

describe('computeRiskBreakdown — band thresholds', () => {
  it('bands a score of 34 as low', () => {
    const breakdown = computeRiskBreakdown(
      service({ healthStatus: 'HEALTHY', teamId: 'team-1', lastDeployedAt: daysAgo(19.5) }),
    )
    expect(breakdown.score).toBe(34)
    expect(breakdown.band).toBe('low')
  })

  it('bands a score of 35 as moderate', () => {
    const breakdown = computeRiskBreakdown(
      service({ healthStatus: 'HEALTHY', teamId: 'team-1', lastDeployedAt: daysAgo(25) }),
    )
    expect(breakdown.score).toBe(35)
    expect(breakdown.band).toBe('moderate')
  })

  it('bands a score of 69 as moderate', () => {
    const breakdown = computeRiskBreakdown(
      service({ healthStatus: 'DEGRADED', teamId: null, lastDeployedAt: daysAgo(17.5) }),
    )
    expect(breakdown.score).toBe(69)
    expect(breakdown.band).toBe('moderate')
  })

  it('bands a score of 70 as high', () => {
    const breakdown = computeRiskBreakdown(
      service({ healthStatus: 'DEGRADED', teamId: null, lastDeployedAt: daysAgo(18) }),
    )
    expect(breakdown.score).toBe(70)
    expect(breakdown.band).toBe('high')
  })
})

describe('computeRiskScore', () => {
  it('returns just the score half of computeRiskBreakdown', () => {
    const svc = service({ healthStatus: 'HEALTHY', teamId: 'team-1', lastDeployedAt: daysAgo(0.1) })
    expect(computeRiskScore(svc)).toBe(computeRiskBreakdown(svc).score)
  })
})

describe('riskBandLabel', () => {
  it('maps each band to its display label', () => {
    expect(riskBandLabel('high')).toBe('High risk')
    expect(riskBandLabel('moderate')).toBe('Elevated risk')
    expect(riskBandLabel('low')).toBe('Low risk')
  })
})
