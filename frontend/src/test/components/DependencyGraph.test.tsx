import { describe, expect, it } from 'vitest'

import { nodeAppearance, type SimNode } from '#/components/DependencyGraph'

function node(overrides: Partial<SimNode> = {}): SimNode {
  return {
    serviceId: 's1',
    name: 'payments',
    teamId: null,
    tier: null,
    healthStatus: 'HEALTHY',
    ...overrides,
  }
}

describe('nodeAppearance', () => {
  it('describes a healthy node with no tier', () => {
    const appearance = nodeAppearance(node())
    expect(appearance.ariaLabel).toBe('payments, healthy')
    expect(appearance.fill).toBe('var(--color-success)')
    expect(appearance.strokeWidth).toBe(2)
    expect(appearance.strokeDasharray).toBeNull()
    expect(appearance.glyph).toBe('')
    expect(appearance.label).toBe('payments')
  })

  it('appends the lowercased tier to the label when present', () => {
    const appearance = nodeAppearance(node({ tier: 'CRITICAL' }))
    expect(appearance.ariaLabel).toBe('payments, healthy, critical tier')
  })

  it('marks a degraded node with a dashed stroke but no glyph', () => {
    const appearance = nodeAppearance(node({ healthStatus: 'DEGRADED' }))
    expect(appearance.ariaLabel).toBe('payments, degraded')
    expect(appearance.fill).toBe('var(--color-warning)')
    expect(appearance.strokeWidth).toBe(2)
    expect(appearance.strokeDasharray).toBe('3,2')
    expect(appearance.glyph).toBe('')
  })

  it('marks a down node with a thicker stroke and an exclamation glyph', () => {
    const appearance = nodeAppearance(node({ healthStatus: 'UNHEALTHY', tier: 'STANDARD' }))
    expect(appearance.ariaLabel).toBe('payments, down, standard tier')
    expect(appearance.fill).toBe('var(--color-critical)')
    expect(appearance.strokeWidth).toBe(3)
    expect(appearance.strokeDasharray).toBeNull()
    expect(appearance.glyph).toBe('!')
  })
})
