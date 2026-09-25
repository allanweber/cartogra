import { describe, expect, it } from 'vitest'

import { resolveEditEndpoints } from '#/components/DependencyDialog'

import type { DependencyDirectionEntry } from '#/lib/topology-types'

function entry(overrides: Partial<DependencyDirectionEntry> = {}): DependencyDirectionEntry {
  return {
    id: 'dep-1',
    serviceId: 'other-svc',
    name: 'auth-service',
    teamId: null,
    tier: null,
    healthStatus: 'HEALTHY',
    protocol: 'HTTP',
    metadata: null,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('resolveEditEndpoints', () => {
  it('puts serviceId as the source for a downstream edit', () => {
    const result = resolveEditEndpoints('svc-1', { entry: entry(), direction: 'downstream' })
    expect(result).toEqual({ sourceServiceId: 'svc-1', targetServiceId: 'other-svc' })
  })

  it('puts the entry as the source for an upstream edit', () => {
    const result = resolveEditEndpoints('svc-1', { entry: entry(), direction: 'upstream' })
    expect(result).toEqual({ sourceServiceId: 'other-svc', targetServiceId: 'svc-1' })
  })
})
